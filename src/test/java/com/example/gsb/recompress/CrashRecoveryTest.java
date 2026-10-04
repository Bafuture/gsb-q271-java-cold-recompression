package com.example.gsb.recompress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Simulates a crash at every stage of the recompression pipeline and verifies
 * that after a restart the record is either the complete old copy or the
 * complete new copy, never a half-product, and that recovery statistics are
 * counted.
 */
class CrashRecoveryTest {

    @TempDir
    Path dir;

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final TieringPolicy policy =
            new TieringPolicy(Duration.ofDays(1), 0, Duration.ofDays(1));
    private final byte[] payload = TestSupport.compressiblePayload("crash", 4096);

    private RecompressionManager newManager() throws Exception {
        return new RecompressionManager(dir, TestSupport.TEST_KEY, policy, clock);
    }

    private void putColdCandidate(RecompressionManager manager) {
        manager.put("victim", payload);
        clock.advance(Duration.ofDays(2));
    }

    private static FaultHook crashAt(FaultHook.Phase crashPhase) {
        return (phase, id) -> {
            if (phase == crashPhase) {
                throw new RuntimeException("simulated crash at " + crashPhase);
            }
        };
    }

    @Test
    void crashRightAfterEncodeLeavesOldCopyIntact() throws Exception {
        try (RecompressionManager manager = newManager()) {
            putColdCandidate(manager);
            manager.setFaultHook(crashAt(FaultHook.Phase.AFTER_ENCODE));
            assertThatThrownBy(() -> manager.recompress("victim"))
                    .hasMessageContaining("simulated crash");
        }
        try (RecompressionManager restarted = newManager()) {
            assertThat(restarted.get("victim")).isEqualTo(payload);
            assertThat(restarted.storedTier("victim")).isEqualTo(Tier.HOT);
            // journal STARTED was never written -> nothing to recover
            assertThat(restarted.stats().interruptedRunsRecovered()).isZero();
        }
    }

    @Test
    void crashBeforeCommitIsDiscardedOnRestart() throws Exception {
        try (RecompressionManager manager = newManager()) {
            putColdCandidate(manager);
            manager.setFaultHook(crashAt(FaultHook.Phase.BEFORE_COMMIT));
            assertThatThrownBy(() -> manager.recompress("victim"))
                    .hasMessageContaining("simulated crash");
        }
        try (RecompressionManager restarted = newManager()) {
            assertThat(restarted.get("victim")).isEqualTo(payload);
            assertThat(restarted.storedTier("victim")).isEqualTo(Tier.HOT);
            assertThat(restarted.metaOf("victim").orElseThrow().tier()).isEqualTo(Tier.HOT);
            assertThat(restarted.stats().interruptedRunsRecovered()).isEqualTo(1);
            assertThat(restarted.stats().recoveredByDiscarding()).isEqualTo(1);
            assertThat(restarted.stats().recoveredByCompleting()).isZero();
            assertNoStagingLeftovers();
            // the record can simply be recompressed again
            restarted.recompress("victim");
            assertThat(restarted.get("victim")).isEqualTo(payload);
            assertThat(restarted.storedTier("victim")).isEqualTo(Tier.COLD);
        }
    }

    @Test
    void crashAfterCommitIsCompletedOnRestart() throws Exception {
        try (RecompressionManager manager = newManager()) {
            putColdCandidate(manager);
            manager.setFaultHook(crashAt(FaultHook.Phase.AFTER_COMMIT));
            assertThatThrownBy(() -> manager.recompress("victim"))
                    .hasMessageContaining("simulated crash");
        }
        try (RecompressionManager restarted = newManager()) {
            assertThat(restarted.get("victim")).isEqualTo(payload);
            assertThat(restarted.storedTier("victim")).isEqualTo(Tier.COLD);
            // metadata flip was lost with the crash; recovery restores it
            assertThat(restarted.metaOf("victim").orElseThrow().tier()).isEqualTo(Tier.COLD);
            assertThat(restarted.stats().interruptedRunsRecovered()).isEqualTo(1);
            assertThat(restarted.stats().recoveredByCompleting()).isEqualTo(1);
            assertThat(restarted.stats().recoveredByDiscarding()).isZero();
            assertNoStagingLeftovers();
        }
    }

    @Test
    void crashBetweenCommitAndStagedJournalEntryIsCompletedOnRestart() throws Exception {
        // Hand-crafted intermediate state: journal has only STARTED, but the
        // store already committed the new blob (commit marker consumed).
        try (RecompressionManager manager = newManager()) {
            putColdCandidate(manager);
        }
        RecompressionJournal journal = new RecompressionJournal(dir.resolve("recompression.journal"));
        FileBlobStore store = new FileBlobStore(dir);
        BlobCodec codec = new BlobCodec(new CryptoService(TestSupport.TEST_KEY));
        byte[] oldBlob = store.read("victim").orElseThrow();
        journal.appendStarted("victim", FileBlobStore.crc32(oldBlob), oldBlob.length);
        byte[] newBlob = codec.encode(payload, Tier.COLD);
        store.stageReplacement("victim", newBlob);
        store.commitReplacement("victim", FileBlobStore.crc32(oldBlob));
        // crash "happens" here: no STAGED, no DONE in the journal

        try (RecompressionManager restarted = newManager()) {
            assertThat(restarted.get("victim")).isEqualTo(payload);
            assertThat(restarted.storedTier("victim")).isEqualTo(Tier.COLD);
            assertThat(restarted.metaOf("victim").orElseThrow().tier()).isEqualTo(Tier.COLD);
            assertThat(restarted.stats().interruptedRunsRecovered()).isEqualTo(1);
            assertThat(restarted.stats().recoveredByCompleting()).isEqualTo(1);
        }
    }

    @Test
    void repeatedCrashesNeverLoseTheRecord() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (RecompressionManager manager = newManager()) {
                if (attempt == 0) {
                    putColdCandidate(manager);
                }
                manager.setFaultHook(crashAt(FaultHook.Phase.BEFORE_COMMIT));
                assertThatThrownBy(() -> manager.recompress("victim"))
                        .hasMessageContaining("simulated crash");
            }
        }
        try (RecompressionManager restarted = newManager()) {
            assertThat(restarted.get("victim")).isEqualTo(payload);
            // the journal is compacted after each successful recovery, so each
            // restart only sees the latest crash (which is one interrupted run)
            assertThat(restarted.stats().interruptedRunsRecovered()).isEqualTo(1);
            restarted.recompress("victim");
            assertThat(restarted.get("victim")).isEqualTo(payload);
            assertThat(restarted.storedTier("victim")).isEqualTo(Tier.COLD);
        }
    }

    private void assertNoStagingLeftovers() throws Exception {
        try (Stream<Path> files = Files.list(dir.resolve("staging"))) {
            assertThat(files).isEmpty();
        }
    }
}
