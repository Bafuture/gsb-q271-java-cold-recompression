package com.example.gsb.recompress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecompressionManagerTest {

    @TempDir
    Path dir;

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final TieringPolicy policy =
            new TieringPolicy(Duration.ofDays(30), 2, Duration.ofDays(7));

    private RecompressionManager newManager(Path d) throws Exception {
        return new RecompressionManager(d, TestSupport.TEST_KEY, policy, clock);
    }

    @Test
    void unknownIdReadsNullAndInvalidIdRejected() throws Exception {
        try (RecompressionManager manager = newManager(dir)) {
            assertThat(manager.get("missing")).isNull();
            assertThatThrownBy(() -> manager.get("../escape"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> manager.recompress("missing"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void newRecordIsHotEncoded() throws Exception {
        try (RecompressionManager manager = newManager(dir)) {
            manager.put("a", TestSupport.compressiblePayload("a", 2048));
            assertThat(manager.metaOf("a").orElseThrow().tier()).isEqualTo(Tier.HOT);
            assertThat(manager.storedTier("a")).isEqualTo(Tier.HOT);
            assertThat(manager.get("a")).isEqualTo(TestSupport.compressiblePayload("a", 2048));
        }
    }

    @Test
    void coldCandidatesAppearWithAgeAndDisappearAfterRecompression() throws Exception {
        try (RecompressionManager manager = newManager(dir)) {
            manager.put("a", TestSupport.compressiblePayload("a", 2048));
            manager.put("b", TestSupport.compressiblePayload("b", 2048));
            assertThat(manager.scanColdCandidates()).isEmpty();

            clock.advance(Duration.ofDays(40));
            assertThat(manager.scanColdCandidates()).containsExactly("a", "b");

            manager.recompress("a");
            assertThat(manager.scanColdCandidates()).containsExactly("b");
            assertThat(manager.storedTier("a")).isEqualTo(Tier.COLD);
            assertThat(manager.metaOf("a").orElseThrow().tier()).isEqualTo(Tier.COLD);
        }
    }

    @Test
    void frequentlyReadOldRecordStaysHot() throws Exception {
        try (RecompressionManager manager = newManager(dir)) {
            manager.put("hot-one", TestSupport.compressiblePayload("h", 1024));
            clock.advance(Duration.ofDays(40));
            manager.get("hot-one");
            manager.get("hot-one");
            manager.get("hot-one");
            assertThat(manager.scanColdCandidates()).doesNotContain("hot-one");
        }
    }

    @Test
    void batchRecompressionStopsAtLimitAndContinuesLater() throws Exception {
        try (RecompressionManager manager = newManager(dir)) {
            for (int i = 0; i < 5; i++) {
                manager.put("rec" + i, TestSupport.compressiblePayload("r" + i, 1024));
            }
            clock.advance(Duration.ofDays(40));
            assertThat(manager.recompressBatch(2)).isEqualTo(2);
            assertThat(manager.scanColdCandidates()).containsExactly("rec2", "rec3", "rec4");
            assertThat(manager.recompressBatch(10)).isEqualTo(3);
            assertThat(manager.scanColdCandidates()).isEmpty();
        }
    }

    @Test
    void tieringDecisionSurvivesRestart() throws Exception {
        try (RecompressionManager manager = newManager(dir)) {
            manager.put("a", TestSupport.compressiblePayload("a", 1024));
            clock.advance(Duration.ofDays(40));
            manager.get("a"); // one recorded access, still under the threshold
        }
        try (RecompressionManager reopened = newManager(dir)) {
            assertThat(reopened.scanColdCandidates()).containsExactly("a");
        }
    }

    @Test
    void fixedClockPolicyAlsoAccepted() throws Exception {
        TieringPolicy neverCold = new TieringPolicy(Duration.ofDays(1), 0, Duration.ofDays(1));
        try (RecompressionManager manager = new RecompressionManager(
                dir.resolve("other"), TestSupport.TEST_KEY, neverCold, Clock.systemUTC())) {
            manager.put("a", new byte[]{1, 2, 3});
            assertThat(manager.scanColdCandidates()).isEmpty();
        }
    }
}
