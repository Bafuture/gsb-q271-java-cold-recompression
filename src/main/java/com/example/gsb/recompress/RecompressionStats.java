package com.example.gsb.recompress;

/** Immutable snapshot of recompression statistics. */
public record RecompressionStats(
        long recompressedRecords,
        long bytesBefore,
        long bytesAfter,
        long totalDurationNanos,
        long interruptedRunsRecovered,
        long recoveredByCompleting,
        long recoveredByDiscarding) {

    public static RecompressionStats empty() {
        return new RecompressionStats(0, 0, 0, 0, 0, 0, 0);
    }

    /** bytesAfter / bytesBefore; 1.0 when nothing was recompressed yet. */
    public double compressionRatioChange() {
        return bytesBefore == 0 ? 1.0 : (double) bytesAfter / (double) bytesBefore;
    }

    /** Fraction of space saved by recompression, e.g. 0.25 means 25% smaller. */
    public double spaceSavedFraction() {
        return bytesBefore == 0 ? 0.0 : 1.0 - (double) bytesAfter / (double) bytesBefore;
    }

    public double averageDurationMillis() {
        return recompressedRecords == 0 ? 0.0
                : (double) totalDurationNanos / 1_000_000.0 / recompressedRecords;
    }
}
