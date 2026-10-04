package com.example.gsb.recompress;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Coordinates tiered encoding, background recompression, atomic switch-over,
 * crash recovery and statistics.
 *
 * <p>Recompression of one record is a crash-safe pipeline:
 * <ol>
 *   <li>decode the current blob (proves the old copy is intact)</li>
 *   <li>re-encode the plaintext with the cold (high-ratio) profile</li>
 *   <li>journal STARTED, stage the new blob next to the live one, journal STAGED</li>
 *   <li>atomically rename the staged file over the live one (the commit point)</li>
 *   <li>decode the committed blob and compare it byte-for-byte with the source</li>
 *   <li>journal DONE, flip the tier in metadata, update statistics</li>
 * </ol>
 * Until the rename, readers are served by the untouched old blob; after it, by
 * the complete new blob. A crash anywhere is resolved on the next startup by
 * {@link #recoverFromCrash()} using the journal plus the store's commit marker.
 */
public final class RecompressionManager implements AutoCloseable {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final FileBlobStore blobStore;
    private final MetaStore metaStore;
    private final BlobCodec codec;
    private final RecompressionJournal journal;
    private final TieringPolicy policy;
    private final Clock clock;
    private final Map<String, RecordMeta> metas = new ConcurrentHashMap<>();

    private final AtomicLong recompressedRecords = new AtomicLong();
    private final AtomicLong bytesBefore = new AtomicLong();
    private final AtomicLong bytesAfter = new AtomicLong();
    private final AtomicLong totalDurationNanos = new AtomicLong();
    private final AtomicLong interruptedRunsRecovered = new AtomicLong();
    private final AtomicLong recoveredByCompleting = new AtomicLong();
    private final AtomicLong recoveredByDiscarding = new AtomicLong();

    private volatile FaultHook faultHook;
    private ScheduledExecutorService background;

    public RecompressionManager(java.nio.file.Path root, byte[] keyBytes,
                                TieringPolicy policy, Clock clock) throws IOException {
        this.blobStore = new FileBlobStore(root);
        this.metaStore = new MetaStore(root);
        this.codec = new BlobCodec(new CryptoService(keyBytes));
        this.journal = new RecompressionJournal(root.resolve("recompression.journal"));
        this.policy = policy;
        this.clock = clock;
        blobStore.recover();
        for (RecordMeta meta : metaStore.loadAll()) {
            metas.put(meta.id(), meta);
        }
        recoverFromCrash();
        journal.reset();
    }

    /** Test-only: simulate crashes / observe the pipeline. */
    public void setFaultHook(FaultHook hook) {
        this.faultHook = hook;
        this.blobStore.setFaultHook(hook);
    }

    // ------------------------------------------------------------------ writes

    /** Stores a new record, encoded with the profile of its current tier. */
    public void put(String id, byte[] plaintext) {
        validateId(id);
        RecordMeta meta = new RecordMeta(id, Tier.HOT, clock.instant());
        Tier tier = policy.classify(meta, clock);
        meta.setTier(tier);
        byte[] blob = codec.encode(plaintext, tier);
        runIoVoid(() -> blobStore.put(id, blob));
        metas.put(id, meta);
        runIoVoid(() -> metaStore.save(meta));
    }

    // ------------------------------------------------------------------ reads

    /**
     * Reads and decrypts/decompresses a record. Safe to call while the record
     * is being recompressed: either the complete old or the complete new blob
     * is returned. Updates access statistics used by the tiering policy.
     *
     * @return the plaintext, or null if the id is unknown
     */
    public byte[] get(String id) {
        validateId(id);
        Optional<byte[]> blob = runIo(() -> blobStore.read(id));
        if (blob.isEmpty()) {
            return null;
        }
        byte[] plaintext = codec.decode(blob.get());
        RecordMeta meta = metas.get(id);
        if (meta != null) {
            meta.recordAccess(clock.instant());
            runIoVoid(() -> metaStore.save(meta));
        }
        return plaintext;
    }

    public Optional<RecordMeta> metaOf(String id) {
        return Optional.ofNullable(metas.get(id));
    }

    public Tier storedTier(String id) {
        return runIo(() -> codec.tierOf(blobStore.read(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown id: " + id))));
    }

    // ------------------------------------------------------------- recompress

    /** Ids of records currently classified as cold and not yet cold-encoded. */
    public List<String> scanColdCandidates() {
        List<String> candidates = new ArrayList<>();
        for (RecordMeta meta : metas.values()) {
            if (meta.tier() != Tier.COLD && policy.classify(meta, clock) == Tier.COLD) {
                candidates.add(meta.id());
            }
        }
        candidates.sort(String::compareTo);
        return candidates;
    }

    /** Recompresses up to {@code limit} cold candidates. Returns how many were done. */
    public int recompressBatch(int limit) {
        int done = 0;
        for (String id : scanColdCandidates()) {
            if (done >= limit) {
                break;
            }
            try {
                recompress(id);
                done++;
            } catch (RuntimeException e) {
                System.err.println("recompression of " + id + " failed: " + e.getMessage());
            }
        }
        return done;
    }

    /**
     * Recompresses one record to the cold profile with an atomic switch-over.
     * The record stays readable throughout.
     */
    public void recompress(String id) {
        validateId(id);
        RecordMeta meta = metas.get(id);
        if (meta == null) {
            throw new IllegalArgumentException("unknown id: " + id);
        }
        long startNanos = System.nanoTime();

        byte[] oldBlob = runIo(() -> blobStore.read(id)
                .orElseThrow(() -> new IllegalStateException("blob vanished: " + id)));
        byte[] plaintext = codec.decode(oldBlob);
        byte[] newBlob = codec.encode(plaintext, Tier.COLD);
        fireHook(FaultHook.Phase.AFTER_ENCODE, id);

        long oldCrc = FileBlobStore.crc32(oldBlob);
        runIoVoid(() -> journal.appendStarted(id, oldCrc, oldBlob.length));
        long newCrc = runIo(() -> blobStore.stageReplacement(id, newBlob));
        runIoVoid(() -> journal.appendStaged(id, newCrc, newBlob.length));

        boolean committed = runIo(() -> blobStore.commitReplacement(id, oldCrc));
        if (!committed) {
            runIoVoid(() -> blobStore.discardStaged(id));
            throw new IllegalStateException("record " + id + " was modified during recompression");
        }

        // Post-switch verification: the committed blob must decode to the
        // exact same plaintext as before.
        byte[] committedBlob = runIo(() -> blobStore.read(id).orElseThrow());
        byte[] committedPlaintext = codec.decode(committedBlob);
        if (!Arrays.equals(plaintext, committedPlaintext)) {
            throw new CorruptStorageException("content mismatch after recompression of " + id);
        }

        long duration = System.nanoTime() - startNanos;
        runIoVoid(() -> journal.appendDone(id, duration));
        meta.setTier(Tier.COLD);
        runIoVoid(() -> metaStore.save(meta));

        recompressedRecords.incrementAndGet();
        bytesBefore.addAndGet(oldBlob.length);
        bytesAfter.addAndGet(newBlob.length);
        totalDurationNanos.addAndGet(duration);
    }

    // -------------------------------------------------------------- background

    /** Starts a background thread that recompresses cold candidates every {@code interval}. */
    public synchronized void startBackgroundRecompression(Duration interval, int batchLimit) {
        if (background != null) {
            return;
        }
        background = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cold-recompression");
            t.setDaemon(true);
            return t;
        });
        background.scheduleWithFixedDelay(() -> {
            try {
                recompressBatch(batchLimit);
            } catch (RuntimeException e) {
                System.err.println("background recompression pass failed: " + e.getMessage());
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Stops the background thread, waiting briefly for the in-flight record. */
    public synchronized void stopBackgroundRecompression() {
        if (background == null) {
            return;
        }
        background.shutdown();
        try {
            if (!background.awaitTermination(10, TimeUnit.SECONDS)) {
                background.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            background.shutdownNow();
        }
        background = null;
    }

    // ---------------------------------------------------------------- recovery

    /**
     * Resolves interrupted recompression runs from the journal. For every id
     * whose last journal event is not DONE we either finish the switch (the
     * commit rename already happened or the staged copy is fully durable) or
     * discard the staged half-product. The old blob is never removed before
     * the commit point, so every record stays readable across a crash.
     */
    private void recoverFromCrash() throws IOException {
        for (Map.Entry<String, RecompressionJournal.Event> entry : journal.lastEventPerId().entrySet()) {
            String id = entry.getKey();
            RecompressionJournal.Event event = entry.getValue();
            switch (event.type()) {
                case DONE -> {
                    // Finished cleanly; nothing to do.
                }
                case STAGED -> {
                    long newCrc = event.arg1();
                    Optional<Long> current = currentChecksum(id);
                    if (current.isPresent() && current.get() == newCrc) {
                        // Commit happened but DONE was never written.
                        markRecovered(id, true);
                    } else {
                        // Staged but never committed: drop the half-product.
                        blobStore.discardStaged(id);
                        markRecovered(id, false);
                    }
                }
                case STARTED -> {
                    long oldCrc = event.arg1();
                    Optional<Long> current = currentChecksum(id);
                    if (current.isEmpty()) {
                        blobStore.discardStaged(id);
                        markRecovered(id, false);
                    } else if (current.get() == oldCrc) {
                        // Crash before staging finished; old blob intact.
                        blobStore.discardStaged(id);
                        markRecovered(id, false);
                    } else {
                        // Crash between the store's commit rename and the
                        // STAGED journal record. store.recover() already
                        // completed the rename; verify the new blob is whole.
                        byte[] blob = blobStore.read(id).orElseThrow();
                        codec.decode(blob);
                        markRecovered(id, true);
                    }
                }
            }
        }
    }

    private void markRecovered(String id, boolean completed) throws IOException {
        interruptedRunsRecovered.incrementAndGet();
        if (completed) {
            recoveredByCompleting.incrementAndGet();
        } else {
            recoveredByDiscarding.incrementAndGet();
        }
        RecordMeta meta = metas.get(id);
        if (meta != null && completed && meta.tier() != Tier.COLD) {
            meta.setTier(Tier.COLD);
            metaStore.save(meta);
        }
    }

    private Optional<Long> currentChecksum(String id) throws IOException {
        var checksum = blobStore.checksum(id);
        return checksum.isPresent() ? Optional.of(checksum.getAsLong()) : Optional.empty();
    }

    // ------------------------------------------------------------------ stats

    public RecompressionStats stats() {
        return new RecompressionStats(
                recompressedRecords.get(),
                bytesBefore.get(),
                bytesAfter.get(),
                totalDurationNanos.get(),
                interruptedRunsRecovered.get(),
                recoveredByCompleting.get(),
                recoveredByDiscarding.get());
    }

    /** Total bytes currently occupied by live blobs on disk. */
    public long storedBytes() {
        return runIo(blobStore::totalBytes);
    }

    public long recordCount() {
        return metas.size();
    }

    // ------------------------------------------------------------------ misc

    private void fireHook(FaultHook.Phase phase, String id) {
        FaultHook hook = faultHook;
        if (hook != null) {
            try {
                hook.onPhase(phase, id);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void validateId(String id) {
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("illegal record id: " + id);
        }
    }

    private interface IoAction<T> {
        T run() throws IOException;
    }

    private static <T> T runIo(IoAction<T> action) {
        try {
            return action.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

    private static void runIoVoid(IoRunnable action) {
        try {
            action.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        stopBackgroundRecompression();
    }
}
