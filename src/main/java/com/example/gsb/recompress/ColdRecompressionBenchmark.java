package com.example.gsb.recompress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Measures space saving and latency of hot (level 1) vs cold (level 9)
 * encoding at different data volumes. Data is log-like: mostly repetitive
 * structured text with a small random fraction.
 */
public final class ColdRecompressionBenchmark {

    public record Row(
            int recordCount,
            int recordBytes,
            long hotBytesOnDisk,
            long coldBytesOnDisk,
            double spaceSavedPercent,
            long hotWriteMillis,
            long recompressMillis,
            double recompressMicrosPerRecord) {
    }

    private static final int RECORD_BYTES = 1500;
    private static final int[] SIZES = {100, 1_000, 5_000};

    private ColdRecompressionBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("cold-recompress-bench");
        try {
            List<Row> rows = run(dir);
            System.out.println(toMarkdown(rows));
        } finally {
            deleteRecursively(dir);
        }
    }

    public static List<Row> run(Path root) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (int count : SIZES) {
            rows.add(measure(root.resolve("n" + count), count));
        }
        return rows;
    }

    static Row measure(Path dir, int count) throws IOException {
        byte[] key = new byte[32];
        // Cold age far in the future so initial inserts are all hot-encoded;
        // recompress(id) is then invoked explicitly for every record.
        TieringPolicy policy = new TieringPolicy(Duration.ofDays(3650), 0, Duration.ofDays(1));
        RecompressionManager manager = new RecompressionManager(
                dir, key, policy, Clock.systemUTC());
        List<byte[]> originals = new ArrayList<>(count);
        try {
            long writeStart = System.nanoTime();
            for (int i = 0; i < count; i++) {
                byte[] payload = generatePayload(i);
                originals.add(payload);
                manager.put("rec-" + i, payload);
            }
            long hotWriteMillis = elapsedMillis(writeStart);
            long hotBytes = manager.storedBytes();

            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                manager.recompress("rec-" + i);
            }
            long recompressMillis = elapsedMillis(start);
            long coldBytes = manager.storedBytes();

            for (int i = 0; i < count; i++) {
                byte[] decoded = manager.get("rec-" + i);
                if (!java.util.Arrays.equals(decoded, originals.get(i))) {
                    throw new CorruptStorageException("benchmark consistency failure at rec-" + i);
                }
            }

            return new Row(
                    count,
                    RECORD_BYTES,
                    hotBytes,
                    coldBytes,
                    100.0 * (1.0 - (double) coldBytes / hotBytes),
                    hotWriteMillis,
                    recompressMillis,
                    (double) recompressMillis * 1000.0 / count);
        } finally {
            manager.close();
        }
    }

    /** ~75% repetitive log text, ~25% random bytes. */
    static byte[] generatePayload(int seed) {
        Random random = new Random(0x9e3779b97f4a7c15L ^ seed);
        StringBuilder sb = new StringBuilder(RECORD_BYTES);
        String[] levels = {"INFO", "WARN", "DEBUG", "ERROR"};
        String base = "2026-10-04 12:00:00 [worker-7] INFO  order placed id=88421 total=129.90 status=OK  ";
        while (sb.length() < RECORD_BYTES) {
            int n = 40 + random.nextInt(60);
            for (int i = 0; i < n && sb.length() < RECORD_BYTES; i++) {
                char c = (i % 23 == 0) ? (char) ('a' + random.nextInt(26))
                        : base.charAt(i % base.length());
                sb.append(c);
            }
            sb.append(levels[random.nextInt(levels.length)]).append('\n');
        }
        sb.setLength(RECORD_BYTES);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public static String toMarkdown(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| records | plaintext bytes | hot (L1) on disk | cold (L9) on disk | space saved | hot write ms | recompress ms | recompress us/record |\n");
        sb.append("|---------|-----------------|------------------|-------------------|-------------|--------------|---------------|----------------------|\n");
        for (Row row : rows) {
            sb.append(String.format(
                    "| %,d | %,d | %,d | %,d | %.1f%% | %,d | %,d | %.1f |%n",
                    row.recordCount(),
                    (long) row.recordCount() * row.recordBytes(),
                    row.hotBytesOnDisk(),
                    row.coldBytesOnDisk(),
                    row.spaceSavedPercent(),
                    row.hotWriteMillis(),
                    row.recompressMillis(),
                    row.recompressMicrosPerRecord()));
        }
        return sb.toString();
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
