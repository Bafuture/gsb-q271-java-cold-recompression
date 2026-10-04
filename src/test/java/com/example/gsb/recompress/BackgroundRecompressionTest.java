package com.example.gsb.recompress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class BackgroundRecompressionTest {

    @TempDir
    Path dir;

    @Test
    void backgroundThreadRecompressesAllColdRecords() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TieringPolicy policy = new TieringPolicy(Duration.ofDays(30), 0, Duration.ofDays(7));
        try (RecompressionManager manager =
                     new RecompressionManager(dir, TestSupport.TEST_KEY, policy, clock)) {
            for (int i = 0; i < 20; i++) {
                manager.put("rec" + i, TestSupport.compressiblePayload("r" + i, 2048));
            }
            manager.startBackgroundRecompression(Duration.ofMillis(20), 5);
            clock.advance(Duration.ofDays(40));

            waitUntil(() -> manager.scanColdCandidates().isEmpty(), 10_000);

            for (int i = 0; i < 20; i++) {
                assertThat(manager.storedTier("rec" + i)).isEqualTo(Tier.COLD);
                assertThat(manager.get("rec" + i))
                        .isEqualTo(TestSupport.compressiblePayload("r" + i, 2048));
            }
            manager.stopBackgroundRecompression();
            assertThat(manager.stats().recompressedRecords()).isEqualTo(20);
        }
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMillis + " ms");
            }
            Thread.sleep(20);
        }
    }
}
