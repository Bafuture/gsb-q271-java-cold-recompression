package com.example.gsb.recompress;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * File-backed key/value store with two compression tiers.
 *
 * <p>Hot records are stored compressed with a fast codec; once {@link TierPolicy}
 * classifies a record as cold, {@link #recompressCold()} re-encodes it with the
 * high-ratio codec in the background. Reads always go through the committed
 * record file, so they never observe a half-written file:
 * <ol>
 *   <li>the new encoding is fully written to {@code staging/<id>.new} and fsynced;</li>
 *   <li>a {@code journal/<id>.jrn} entry carrying the staged CRC is fsynced;</li>
 *   <li>the committed file is replaced with one atomic move;</li>
 *   <li>the journal is removed.</li>
 * </ol>
 *
 * <p>On open, journals are replayed: an intact, CRC-matching staging file is
 * swapped into place (continue); otherwise the staging file is discarded and
 * the old copy keeps serving reads.
 */
public final class ColdDataStore implements AutoCloseable {

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final Path dir;
    private final Path recordsDir;
    private final Path metaDir;
    private final Path stagingDir;
    private final Path journalDir;
    private final TierPolicy policy;
    private final Encryptor encryptor;
    private final Clock clock;
    private final RecompressionHook hook;
    private final boolean fsync;
    private final RecompressionStats stats = new RecompressionStats();
    private final ConcurrentMap<String, RecordMeta> catalog = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> recordLocks = new ConcurrentHashMap<>();
    private final ExecutorService recompressExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cold-data-recompressor");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean closed;

    public ColdDataStore(Path dir, TierPolicy policy, Encryptor encryptor, Clock clock) {
        this(dir, policy, encryptor, clock, RecompressionHook.NONE, true);
    }

    public ColdDataStore(Path dir, TierPolicy policy, Encryptor encryptor, Clock clock,
            RecompressionHook hook, boolean fsync) {
        this.dir = dir;
        this.recordsDir = dir.resolve("records");
        this.metaDir = dir.resolve("meta");
        this.stagingDir = dir.resolve("staging");
        this.journalDir = dir.resolve("journal");
        this.policy = policy;
        this.encryptor = encryptor;
        this.clock = clock;
        this.hook = hook;
        this.fsync = fsync;
        createDirectories();
        recoverInterruptedRecompression();
        loadCatalog();
    }

    // ---------------------------------------------------------------- writes

    /** Stores {@code plaintext} for {@code id}, picking the codec of its tier. */
    public void put(String id, byte[] plaintext) {
        ensureOpen();
        validateId(id);
        RecordMeta meta = new RecordMeta(id, clock.instant());
        CompressionCodec codec = codecFor(policy.classify(meta, clock.instant()));
        byte[] payload = encryptor.encrypt(codec.compress(plaintext));
        writeDurably(recordPath(id), RecordFile.encode(codec.id(), payload));
        writeDurably(metaPath(id), meta.encode());
        catalog.put(id, meta);
    }

    /**
     * Reads and returns the decrypted, decompressed content. The committed
     * file is replaced atomically, so concurrent recompression never yields a
     * partial result: the caller gets either the complete old copy or the
     * complete new copy.
     */
    public byte[] get(String id) {
        ensureOpen();
        validateId(id);
        RecordMeta meta = catalog.get(id);
        if (meta == null) {
            throw new StoreException("unknown record id: " + id);
        }
        RecordFile.Decoded record = RecordFile.decode(readAllBytes(recordPath(id)));
        byte[] plaintext = Codecs.byId(record.codecId())
                .decompress(encryptor.decrypt(record.payload()));
        meta.recordAccess(clock.instant());
        return plaintext;
    }

    public Tier tierOf(String id) {
        validateId(id);
        RecordMeta meta = catalog.get(id);
        if (meta == null) {
            throw new StoreException("unknown record id: " + id);
        }
        return policy.classify(meta, clock.instant());
    }

    /** Codec id recorded in the committed file header, e.g. for tests. */
    public int codecIdOf(String id) {
        validateId(id);
        return RecordFile.decode(readAllBytes(recordPath(id))).codecId();
    }

    public RecompressionStats stats() {
        return stats;
    }

    // -------------------------------------------------------- recompression

    /**
     * Synchronously re-encodes every record currently classified COLD that is
     * still stored with the hot codec.
     */
    public RecompressionStats recompressCold() {
        ensureOpen();
        java.time.Instant now = clock.instant();
        for (RecordMeta meta : List.copyOf(catalog.values())) {
            if (policy.classify(meta, now) == Tier.COLD) {
                recompressOne(meta.id());
            }
        }
        return stats;
    }

    /** Runs {@link #recompressCold()} on a single background thread. */
    public CompletableFuture<RecompressionStats> recompressColdAsync() {
        ensureOpen();
        return CompletableFuture.supplyAsync(this::recompressCold, recompressExecutor);
    }

    private void recompressOne(String id) {
        synchronized (lockFor(id)) {
            Path committed = recordPath(id);
            byte[] oldFile = readAllBytesIfExists(committed);
            if (oldFile == null) {
                return; // deleted concurrently
            }
            RecordFile.Decoded oldRecord = RecordFile.decode(oldFile);
            if (oldRecord.codecId() == Codecs.HIGH_RATIO.id()) {
                return; // already encoded with the cold codec
            }

            long startNanos = System.nanoTime();
            byte[] plaintext = Codecs.byId(oldRecord.codecId())
                    .decompress(encryptor.decrypt(oldRecord.payload()));
            byte[] newPayload = encryptor.encrypt(Codecs.HIGH_RATIO.compress(plaintext));
            byte[] newFile = RecordFile.encode(Codecs.HIGH_RATIO.id(), newPayload);

            Path staging = stagingPath(id);
            Path journal = journalPath(id);
            writeDurably(staging, newFile);
            hook.afterStagingWritten(id);
            writeDurably(journal, Journal.encode(id, RecordFile.crc32(newFile)));
            hook.afterJournalWritten(id);
            atomicMove(staging, committed);
            hook.afterAtomicSwap(id);
            deleteIfExists(journal);

            stats.recordRecompression(plaintext.length, oldFile.length, newFile.length,
                    System.nanoTime() - startNanos);
        }
    }

    // -------------------------------------------------------------- recovery

    private void recoverInterruptedRecompression() {
        // Replay journals: complete the swap when the staging file is intact,
        // otherwise discard it. The old committed copy is never touched until
        // a valid replacement exists.
        try (Stream<Path> journals = Files.list(journalDir)) {
            for (Path journalFile : journals.sorted().toList()) {
                String fileName = journalFile.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - ".jrn".length());
                Journal.Entry entry;
                try {
                    entry = Journal.decode(readAllBytes(journalFile));
                } catch (RuntimeException e) {
                    entry = null;
                }

                boolean completed = false;
                Path staging = stagingPath(entry == null ? id : entry.recordId());
                byte[] staged = readAllBytesIfExists(staging);
                if (entry != null && staged != null
                        && RecordFile.crc32(staged) == entry.stagedCrc()
                        && RecordFile.isValid(staged)) {
                    atomicMove(staging, recordPath(entry.recordId()));
                    completed = true;
                } else if (staged != null) {
                    deleteIfExists(staging);
                }
                if (completed) {
                    stats.recordRecoveryCompleted();
                } else {
                    stats.recordRecoveryDiscarded();
                }
                deleteIfExists(journalFile);
            }
        } catch (IOException e) {
            throw new StoreException("failed to scan journals", e);
        }

        // Staging files without a journal are remnants of crashes before the
        // journal was durable: nothing promised them, so discard them.
        try (Stream<Path> stagedFiles = Files.list(stagingDir)) {
            for (Path staging : stagedFiles.toList()) {
                if (deleteIfExists(staging)) {
                    stats.recordRecoveryDiscarded();
                }
            }
        } catch (IOException e) {
            throw new StoreException("failed to scan staging directory", e);
        }
    }

    private void loadCatalog() {
        try (Stream<Path> metaFiles = Files.list(metaDir)) {
            for (Path metaFile : metaFiles.toList()) {
                String fileName = metaFile.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - ".meta".length());
                if (!VALID_ID.matcher(id).matches()) {
                    continue;
                }
                byte[] raw = readAllBytes(metaFile);
                if (raw.length > 0) {
                    catalog.put(id, RecordMeta.decode(id, raw));
                }
            }
        } catch (IOException e) {
            throw new StoreException("failed to load catalog", e);
        }
    }

    // ------------------------------------------------------------- plumbing

    private void writeDurably(Path target, byte[] content) {
        try {
            try (FileChannel channel = FileChannel.open(target,
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                if (fsync) {
                    channel.force(true);
                }
            }
        } catch (IOException e) {
            throw new StoreException("failed to write " + target, e);
        }
    }

    private void atomicMove(Path source, Path target) {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StoreException("failed to atomically replace " + target, e);
        }
    }

    private void createDirectories() {
        try {
            Files.createDirectories(recordsDir);
            Files.createDirectories(metaDir);
            Files.createDirectories(stagingDir);
            Files.createDirectories(journalDir);
        } catch (IOException e) {
            throw new StoreException("failed to initialize store at " + dir, e);
        }
    }

    private byte[] readAllBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new StoreException("failed to read " + file, e);
        }
    }

    private byte[] readAllBytesIfExists(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean deleteIfExists(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new StoreException("failed to delete " + file, e);
        }
    }

    private Object lockFor(String id) {
        return recordLocks.computeIfAbsent(id, key -> new Object());
    }

    private CompressionCodec codecFor(Tier tier) {
        return tier == Tier.COLD ? Codecs.HIGH_RATIO : Codecs.FAST;
    }

    private Path recordPath(String id) {
        return recordsDir.resolve(id + ".rec");
    }

    private Path metaPath(String id) {
        return metaDir.resolve(id + ".meta");
    }

    private Path stagingPath(String id) {
        return stagingDir.resolve(id + ".new");
    }

    private Path journalPath(String id) {
        return journalDir.resolve(id + ".jrn");
    }

    private static void validateId(String id) {
        if (id == null || !VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("invalid record id: " + id);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new StoreException("store is closed");
        }
    }

    @Override
    public void close() {
        closed = true;
        recompressExecutor.shutdownNow();
        // Persist access-frequency updates collected while running.
        for (RecordMeta meta : catalog.values()) {
            synchronized (lockFor(meta.id())) {
                if (meta.isDirty()) {
                    writeDurably(metaPath(meta.id()), meta.encode());
                    meta.markClean();
                }
            }
        }
    }

}
