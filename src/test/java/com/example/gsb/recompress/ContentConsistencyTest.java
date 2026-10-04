package com.example.gsb.recompress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 5: decrypted+decompressed content must be identical, record by
 * record, before and after recompression.
 */
class ContentConsistencyTest {

    @TempDir
    Path dir;

    @Test
    void everyRecordIsByteIdenticalAfterRecompression() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TieringPolicy policy = new TieringPolicy(Duration.ofDays(30), 0, Duration.ofDays(7));
        List<byte[]> originals = new ArrayList<>();
        int count = 300;
        try (RecompressionManager manager =
                     new RecompressionManager(dir, TestSupport.TEST_KEY, policy, clock)) {
            for (int i = 0; i < count; i++) {
                byte[] payload = switch (i % 4) {
                    case 0 -> TestSupport.compressiblePayload("c" + i, 4096);
                    case 1 -> TestSupport.randomPayload(i, 2048);
                    case 2 -> new byte[0];
                    default -> ColdRecompressionBenchmark.generatePayload(i);
                };
                originals.add(payload);
                manager.put("rec" + i, payload);
            }
            // snapshot of the decrypted content BEFORE recompression
            List<byte[]> before = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                before.add(manager.get("rec" + i));
            }
            clock.advance(Duration.ofDays(40));
            assertThat(manager.recompressBatch(count)).isEqualTo(count);

            for (int i = 0; i < count; i++) {
                assertThat(manager.storedTier("rec" + i)).isEqualTo(Tier.COLD);
                assertThat(manager.get("rec" + i))
                        .as("record rec%s must be byte-identical after recompression", i)
                        .isEqualTo(before.get(i))
                        .isEqualTo(originals.get(i));
            }
        }
        // and once more after a full restart (reads hit cold-encoded blobs)
        try (RecompressionManager reopened =
                     new RecompressionManager(dir, TestSupport.TEST_KEY, policy, clock)) {
            for (int i = 0; i < count; i++) {
                assertThat(reopened.get("rec" + i)).isEqualTo(originals.get(i));
            }
        }
    }
}
