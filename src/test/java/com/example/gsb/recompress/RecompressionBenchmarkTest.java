package com.example.gsb.recompress;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Benchmark: measures space saved and elapsed time when recompressing cold
 * datasets of different sizes. Results are printed to the test console and
 * mirrored in the README. fsync is disabled so the numbers reflect re-encode
 * cost rather than disk durability cost.
 */
class RecompressionBenchmarkTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final int PAYLOAD_SIZE = 16 * 1024;

    private static final String[] ACTIONS = {
            "GET /api/v1/orders", "GET /api/v1/products", "POST /api/v1/cart",
            "PUT /api/v1/profile", "DELETE /api/v1/session", "GET /api/v1/inventory",
            "POST /api/v1/checkout", "GET /api/v1/recommendations"
    };

    @TempDir
    Path dir;

    @Test
    void benchmarkAcrossDatasetSizes() {
        int[] sizes = {1_000, 5_000, 10_000};
        byte[] key = Encryptor.generateKey();
        System.out.println();
        System.out.println("Cold-data recompression benchmark (payload ~16 KiB/record, fsync off)");
        System.out.println("| records | plaintext (MB) | hot bytes (MB) | cold bytes (MB)"
                + " | saved (MB) | saved % | ratio hot -> cold | time (ms) | records/s |");
        System.out.println("|---|---|---|---|---|---|---|---|---|");
        for (int count : sizes) {
            Row row = runOne(count, key);
            System.out.printf(Locale.ROOT,
                    "| %,d | %.2f | %.2f | %.2f | %.2f | %.1f%% | %.4f -> %.4f | %,d | %,d |%n",
                    row.records(), row.plaintextMb(), row.beforeMb(), row.afterMb(),
                    row.savedMb(), 100.0 * row.savedMb() / row.beforeMb(),
                    row.ratioBefore(), row.ratioAfter(), row.elapsedMillis(), row.throughput());

            assertThat(row.savedBytes()).as("high-ratio codec must save space").isPositive();
            // Spot-check content integrity after recompression (payloads are deterministic).
            try (ColdDataStore store = row.store()) {
                for (int i = 0; i < count; i += Math.max(1, count / 50)) {
                    assertThat(store.get("rec-" + i)).isEqualTo(generatePayload(i));
                }
            }
        }
        System.out.println();
    }

    private Row runOne(int count, byte[] key) {
        MutableClock clock = new MutableClock(T0);
        ColdDataStore store = new ColdDataStore(dir.resolve("n" + count),
                new TierPolicy(Duration.ofDays(30), 3),
                new Encryptor(key), clock, RecompressionHook.NONE, false);
        for (int i = 0; i < count; i++) {
            store.put("rec-" + i, generatePayload(i));
        }
        clock.advance(Duration.ofDays(31));
        long start = System.nanoTime();
        RecompressionStats stats = store.recompressCold();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(stats.recordsRecompressed()).isEqualTo(count);
        return new Row(store, count, stats.plaintextBytes() / 1048576.0,
                stats.bytesBefore() / 1048576.0, stats.bytesAfter() / 1048576.0,
                stats.spaceSavedBytes(), stats.compressionRatioBefore(),
                stats.compressionRatioAfter(), elapsedMillis,
                elapsedMillis == 0 ? 0 : count * 1000L / elapsedMillis);
    }

    /** Deterministic ~16 KiB log-like payload; index-seeded so tests can regenerate it. */
    static byte[] generatePayload(int index) {
        Random random = new Random(1234L + index);
        StringBuilder builder = new StringBuilder(PAYLOAD_SIZE + 256);
        int line = 0;
        while (builder.length() < PAYLOAD_SIZE) {
            builder.append("2026-09-30T12:").append(String.format("%02d", line % 60))
                    .append(":00Z INFO  [worker-").append(line % 8)
                    .append("] request completed id=").append(index)
                    .append(" user=user-").append(random.nextInt(100_000))
                    .append(' ').append(ACTIONS[random.nextInt(ACTIONS.length)])
                    .append(" status=200 latencyMs=").append(random.nextInt(500))
                    .append(" session=").append(String.format("sess-%08d", random.nextInt(1_000_000)))
                    .append(" region=cn-shanghai az=a pool=web-frontend version=v2.3.1")
                    .append(" trace=").append(String.format("%016x", random.nextLong()))
                    .append('\n');
            line++;
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private record Row(ColdDataStore store, int records, double plaintextMb,
            double beforeMb, double afterMb, long savedBytes, double ratioBefore,
            double ratioAfter, long elapsedMillis, long throughput) {

        private double savedMb() {
            return beforeMb - afterMb;
        }
    }
}
