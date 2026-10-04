package com.example.gsb.recompress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RecompressionStatsTest {

    @TempDir
    Path dir;

    @Test
    void statsTrackCountRatioAndDuration() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TieringPolicy policy = new TieringPolicy(Duration.ofDays(1), 0, Duration.ofDays(1));
        try (RecompressionManager manager =
                     new RecompressionManager(dir, TestSupport.TEST_KEY, policy, clock)) {
            assertThat(manager.stats()).isEqualTo(RecompressionStats.empty());

            for (int i = 0; i < 10; i++) {
                manager.put("rec" + i, TestSupport.compressiblePayload("s" + i, 4096));
            }
            clock.advance(Duration.ofDays(2));
            manager.recompressBatch(10);

            RecompressionStats stats = manager.stats();
            assertThat(stats.recompressedRecords()).isEqualTo(10);
            assertThat(stats.bytesBefore()).isGreaterThan(0);
            assertThat(stats.bytesAfter()).isGreaterThan(0);
            assertThat(stats.bytesAfter()).isLessThanOrEqualTo(stats.bytesBefore());
            assertThat(stats.compressionRatioChange()).isBetween(0.0, 1.0);
            assertThat(stats.spaceSavedFraction()).isBetween(0.0, 1.0);
            assertThat(stats.totalDurationNanos()).isGreaterThan(0);
            assertThat(stats.averageDurationMillis()).isGreaterThan(0.0);
            assertThat(stats.interruptedRunsRecovered()).isZero();
        }
    }

    @Test
    void emptyStatsHaveNeutralValues() {
        RecompressionStats stats = RecompressionStats.empty();
        assertThat(stats.compressionRatioChange()).isEqualTo(1.0);
        assertThat(stats.spaceSavedFraction()).isEqualTo(0.0);
        assertThat(stats.averageDurationMillis()).isEqualTo(0.0);
    }
}
