package com.example.gsb.recompress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class TierPolicyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private final TierPolicy policy = new TierPolicy(Duration.ofDays(30), 3);

    private RecordMeta meta(Instant createdAt, long accesses) {
        return new RecordMeta("id", createdAt, createdAt, accesses);
    }

    @Test
    void youngRarelyAccessedDataIsHot() {
        assertThat(policy.classify(meta(T0, 0), T0.plus(Duration.ofDays(1))))
                .isEqualTo(Tier.HOT);
    }

    @Test
    void oldRarelyAccessedDataIsCold() {
        assertThat(policy.classify(meta(T0, 2), T0.plus(Duration.ofDays(90))))
                .isEqualTo(Tier.COLD);
    }

    @Test
    void oldFrequentlyAccessedDataStaysHot() {
        assertThat(policy.classify(meta(T0, 100), T0.plus(Duration.ofDays(90))))
                .isEqualTo(Tier.HOT);
    }

    @Test
    void youngFrequentlyAccessedDataIsHot() {
        assertThat(policy.classify(meta(T0, 100), T0.plus(Duration.ofDays(1))))
                .isEqualTo(Tier.HOT);
    }

    @Test
    void exactlyAtColdAgeBoundaryIsCold() {
        assertThat(policy.classify(meta(T0, 0), T0.plus(Duration.ofDays(30))))
                .isEqualTo(Tier.COLD);
    }

    @Test
    void oneMillisecondBeforeColdAgeIsHot() {
        assertThat(policy.classify(meta(T0, 0), T0.plus(Duration.ofDays(30)).minusMillis(1)))
                .isEqualTo(Tier.HOT);
    }

    @Test
    void exactlyAtAccessBoundaryIsCold() {
        assertThat(policy.classify(meta(T0, 3), T0.plus(Duration.ofDays(60))))
                .isEqualTo(Tier.COLD);
    }

    @Test
    void oneAccessAboveBoundaryIsHot() {
        assertThat(policy.classify(meta(T0, 4), T0.plus(Duration.ofDays(60))))
                .isEqualTo(Tier.HOT);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> new TierPolicy(null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TierPolicy(Duration.ofDays(-1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TierPolicy(Duration.ofDays(1), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
