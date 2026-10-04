package com.example.gsb.recompress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Simulates process crashes at each durability boundary of a recompression
 * and verifies that a reopened store recovers without ever exposing a
 * half-written record.
 */
class CrashRecoveryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final byte[] PAYLOAD = CodecTest.compressibleText(8192);

    @TempDir
    Path dir;

    private TierPolicy policy() {
        return new TierPolicy(Duration.ofDays(30), 3);
    }

    private ColdDataStore openWithCrashAt(RecompressionHook hook) {
        return new ColdDataStore(dir, policy(), new Encryptor(TEST_KEY), new MutableClock(T0),
                hook, true);
    }

    private static final byte[] TEST_KEY = Encryptor.generateKey();

    private void seedOneColdRecord() {
        MutableClock clock = new MutableClock(T0);
        try (ColdDataStore store = new ColdDataStore(dir, policy(), new Encryptor(TEST_KEY),
                clock)) {
            store.put("rec-1", PAYLOAD);
            clock.advance(Duration.ofDays(31));
        }
    }

    @Test
    void crashAfterJournalIsCompletedOnRecovery() throws IOException {
        seedOneColdRecord();
        MutableClock clock = new MutableClock(T0.plus(Duration.ofDays(31)));
        RecompressionHook crashAfterJournal = new RecompressionHook() {
            @Override
            public void afterJournalWritten(String recordId) {
                throw new SimulatedCrashException("crash before atomic swap");
            }
        };
        try (ColdDataStore store = new ColdDataStore(dir, policy(), new Encryptor(TEST_KEY),
                clock, crashAfterJournal, true)) {
            assertThatThrownBy(store::recompressCold)
                    .isInstanceOf(SimulatedCrashException.class);
        }

        // Reopen: the staged file is intact and CRC-matched, so recovery
        // completes the swap. Content must equal the original.
        try (ColdDataStore recovered = new ColdDataStore(dir, policy(), new Encryptor(TEST_KEY),
                new MutableClock(T0.plus(Duration.ofDays(31))))) {
            assertThat(recovered.get("rec-1")).isEqualTo(PAYLOAD);
            assertThat(recovered.codecIdOf("rec-1")).isEqualTo(Codecs.HIGH_RATIO.id());
            assertThat(recovered.stats().recoveriesCompleted()).isEqualTo(1);
            assertThat(recovered.stats().recoveriesDiscarded()).isZero();
            assertThat(recovered.stats().recoveryCount()).isEqualTo(1);
            assertNoLeftoverStagingOrJournals();
        }
    }

    @Test
    void crashBeforeJournalLeavesOrphanStagingThatIsDiscarded() throws IOException {
        seedOneColdRecord();
        MutableClock clock = new MutableClock(T0.plus(Duration.ofDays(31)));
        RecompressionHook crashAfterStaging = new RecompressionHook() {
            @Override
            public void afterStagingWritten(String recordId) {
                throw new SimulatedCrashException("crash before journal write");
            }
        };
        try (ColdDataStore store = new ColdDataStore(dir, policy(), new Encryptor(TEST_KEY),
                clock, crashAfterStaging, true)) {
            assertThatThrownBy(store::recompressCold)
                    .isInstanceOf(SimulatedCrashException.class);
        }

        // Reopen: no journal promised the staging file, so it is discarded and
        // the old copy keeps serving reads.
        try (ColdDataStore recovered = new ColdDataStore(dir, policy(), new Encryptor(TEST_KEY),
                new MutableClock(T0.plus(Duration.ofDays(31))))) {
            assertThat(recovered.get("rec-1")).isEqualTo(PAYLOAD);
            assertThat(recovered.codecIdOf("rec-1")).isEqualTo(Codecs.FAST.id());
            assertThat(recovered.stats().recoveriesDiscarded()).isEqualTo(1);
            assertThat(recovered.stats().recoveryCount()).isEqualTo(1);
            assertNoLeftoverStagingOrJournals();
        }
    }

    @Test
    void crashWithCorruptStagingIsDiscardedAndOldCopySurvives() throws IOException {
        seedOneColdRecord();
        MutableClock clock = new MutableClock(T0.plus(Duration.ofDays(31)));
        RecompressionHook crashAfterJournal = new RecompressionHook() {
            @Override
            public void afterJournalWritten(String recordId) {
                throw new SimulatedCrashException("crash before atomic swap");
            }
        };
        try (ColdDataStore store = new ColdDataStore(dir, policy(), new Encryptor(TEST_KEY),
                clock, crashAfterJournal, true)) {
            assertThatThrownBy(store::recompressCold)
                    .isInstanceOf(SimulatedCrashException.class);
        }

        // Simulate a torn write: truncate the staged file after the crash.
        Path staging = dir.resolve("staging").resolve("rec-1.new");
        byte[] staged = Files.readAllBytes(staging);
        Files.write(staging, new byte[staged.length / 2]);

        // Reopen: the staged CRC no longer matches the journal, so recovery
        // discards it; the old copy is still complete and readable.
        try (ColdDataStore recovered = new ColdDataStore(dir, policy(), new Encryptor(TEST_KEY),
                new MutableClock(T0.plus(Duration.ofDays(31))))) {
            assertThat(recovered.get("rec-1")).isEqualTo(PAYLOAD);
            assertThat(recovered.codecIdOf("rec-1")).isEqualTo(Codecs.FAST.id());
            assertThat(recovered.stats().recoveriesDiscarded()).isEqualTo(1);
            assertNoLeftoverStagingOrJournals();
        }
    }

    @Test
    void recoveryCanResumeRecompressionAfterDiscard() throws IOException {
        seedOneColdRecord();
        MutableClock clock = new MutableClock(T0.plus(Duration.ofDays(31)));
        RecompressionHook crashAfterStaging = new RecompressionHook() {
            @Override
            public void afterStagingWritten(String recordId) {
                throw new SimulatedCrashException("crash before journal write");
            }
        };
        try (ColdDataStore store = new ColdDataStore(dir, policy(), new Encryptor(TEST_KEY),
                clock, crashAfterStaging, true)) {
            assertThatThrownBy(store::recompressCold)
                    .isInstanceOf(SimulatedCrashException.class);
        }

        // After discarding the half-finished attempt, a fresh recompression
        // succeeds and produces byte-identical content.
        try (ColdDataStore recovered = new ColdDataStore(dir, policy(), new Encryptor(TEST_KEY),
                new MutableClock(T0.plus(Duration.ofDays(31))))) {
            assertThat(recovered.stats().recoveryCount()).isEqualTo(1);
            recovered.recompressCold();
            assertThat(recovered.codecIdOf("rec-1")).isEqualTo(Codecs.HIGH_RATIO.id());
            assertThat(recovered.get("rec-1")).isEqualTo(PAYLOAD);
            assertThat(recovered.stats().recordsRecompressed()).isEqualTo(1);
        }
    }

    private void assertNoLeftoverStagingOrJournals() throws IOException {
        try (Stream<Path> staging = Files.list(dir.resolve("staging"));
                Stream<Path> journals = Files.list(dir.resolve("journal"))) {
            assertThat(staging.toList()).as("no leftover staging files").isEmpty();
            assertThat(journals.toList()).as("no leftover journals").isEmpty();
        }
    }
}
