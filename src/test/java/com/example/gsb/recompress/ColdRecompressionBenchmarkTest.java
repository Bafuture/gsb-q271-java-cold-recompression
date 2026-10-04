package com.example.gsb.recompress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 7: space saved and time spent by recompression at different
 * data volumes. Writes a Markdown table to target/benchmark-results.md.
 */
class ColdRecompressionBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void benchmarkAcrossDataVolumes() throws Exception {
        List<ColdRecompressionBenchmark.Row> rows = ColdRecompressionBenchmark.run(dir);
        assertThat(rows).hasSize(3);
        for (ColdRecompressionBenchmark.Row row : rows) {
            assertThat(row.coldBytesOnDisk()).isLessThanOrEqualTo(row.hotBytesOnDisk());
            assertThat(row.spaceSavedPercent()).isGreaterThanOrEqualTo(0.0);
            assertThat(row.recompressMillis()).isGreaterThanOrEqualTo(0);
        }
        String table = ColdRecompressionBenchmark.toMarkdown(rows);
        System.out.println(System.lineSeparator() + table);
        Path out = Path.of("target", "benchmark-results.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table, StandardCharsets.UTF_8);
    }
}
