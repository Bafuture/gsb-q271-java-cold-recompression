package com.example.gsb.recompress;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe counters describing recompression work and crash recoveries. */
public final class RecompressionStats {

    private final AtomicLong recordsRecompressed = new AtomicLong();
    private final AtomicLong plaintextBytes = new AtomicLong();
    private final AtomicLong bytesBefore = new AtomicLong();
    private final AtomicLong bytesAfter = new AtomicLong();
    private final AtomicLong recompressNanos = new AtomicLong();
    private final AtomicLong recoveriesCompleted = new AtomicLong();
    private final AtomicLong recoveriesDiscarded = new AtomicLong();

    void recordRecompression(long plaintextLen, long beforeBytes, long afterBytes, long nanos) {
        recordsRecompressed.incrementAndGet();
        plaintextBytes.addAndGet(plaintextLen);
        bytesBefore.addAndGet(beforeBytes);
        bytesAfter.addAndGet(afterBytes);
        recompressNanos.addAndGet(nanos);
    }

    void recordRecoveryCompleted() {
        recoveriesCompleted.incrementAndGet();
    }

    void recordRecoveryDiscarded() {
        recoveriesDiscarded.incrementAndGet();
    }

    /** Number of records re-encoded with the cold codec. */
    public long recordsRecompressed() {
        return recordsRecompressed.get();
    }

    /** Total uncompressed size of recompressed records. */
    public long plaintextBytes() {
        return plaintextBytes.get();
    }

    /** On-disk bytes before recompression. */
    public long bytesBefore() {
        return bytesBefore.get();
    }

    /** On-disk bytes after recompression. */
    public long bytesAfter() {
        return bytesAfter.get();
    }

    public long spaceSavedBytes() {
        return bytesBefore.get() - bytesAfter.get();
    }

    /** compressed/plaintext before recompression; NaN when nothing recompressed. */
    public double compressionRatioBefore() {
        long plain = plaintextBytes.get();
        return plain == 0 ? Double.NaN : (double) bytesBefore.get() / plain;
    }

    /** compressed/plaintext after recompression; NaN when nothing recompressed. */
    public double compressionRatioAfter() {
        long plain = plaintextBytes.get();
        return plain == 0 ? Double.NaN : (double) bytesAfter.get() / plain;
    }

    public long recompressNanos() {
        return recompressNanos.get();
    }

    public double recompressMillis() {
        return recompressNanos.get() / 1_000_000.0;
    }

    /** Recoveries where the staged file was intact and the swap was completed. */
    public long recoveriesCompleted() {
        return recoveriesCompleted.get();
    }

    /** Recoveries where the staged file was missing/corrupt and was discarded. */
    public long recoveriesDiscarded() {
        return recoveriesDiscarded.get();
    }

    /** Total number of interrupted recompressions recovered at open time. */
    public long recoveryCount() {
        return recoveriesCompleted.get() + recoveriesDiscarded.get();
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "RecompressionStats{records=%d, plaintext=%dB, before=%dB, after=%dB, saved=%dB,"
                        + " ratio %.4f -> %.4f, elapsed=%.1fms, recoveries=%d (completed=%d, discarded=%d)}",
                recordsRecompressed(), plaintextBytes(), bytesBefore(), bytesAfter(), spaceSavedBytes(),
                compressionRatioBefore(), compressionRatioAfter(), recompressMillis(),
                recoveryCount(), recoveriesCompleted(), recoveriesDiscarded());
    }
}
