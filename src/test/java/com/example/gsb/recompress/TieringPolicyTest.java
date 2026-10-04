package com.example.gsb.recompress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TieringPolicyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final TieringPolicy policy =
            new TieringPolicy(Duration.ofDays(30), 2, Duration.ofDays(7));

    @Test
    void freshRecordIsHot() {
        RecordMeta meta = new RecordMeta("a", Tier.HOT, T0);
        clock.advance(Duration.ofDays(10));
        assertThat(policy.classify(meta, clock)).isEqualTo(Tier.HOT);
    }

    @Test
    void oldRecordWithFewAccessesIsCold() {
        RecordMeta meta = new RecordMeta("a", Tier.HOT, T0);
        clock.advance(Duration.ofDays(40));
        meta.recordAccess(clock.instant());
        meta.recordAccess(clock.instant());
        assertThat(policy.classify(meta, clock)).isEqualTo(Tier.COLD);
    }

    @Test
    void oldRecordWithManyRecentAccessesStaysHot() {
        RecordMeta meta = new RecordMeta("a", Tier.HOT, T0);
        clock.advance(Duration.ofDays(40));
        meta.recordAccess(clock.instant());
        meta.recordAccess(clock.instant());
        meta.recordAccess(clock.instant());
        assertThat(policy.classify(meta, clock)).isEqualTo(Tier.HOT);
    }

    @Test
    void accessesOutsideWindowDoNotCount() {
        RecordMeta meta = new RecordMeta("a", Tier.HOT, T0);
        clock.advance(Duration.ofDays(35));
        meta.recordAccess(clock.instant()); // 5 days ago at classification time
        meta.recordAccess(clock.instant());
        meta.recordAccess(clock.instant());
        clock.advance(Duration.ofDays(10)); // accesses now 10 days old, outside 7-day window
        assertThat(policy.classify(meta, clock)).isEqualTo(Tier.COLD);
    }

    @Test
    void boundaryAgeExactlyAtColdAgeIsCold() {
        RecordMeta meta = new RecordMeta("a", Tier.HOT, T0);
        clock.advance(Duration.ofDays(30));
        assertThat(policy.classify(meta, clock)).isEqualTo(Tier.COLD);
    }

    @Test
    void invalidArgumentsRejected() {
        assertThatThrownBy(() -> new TieringPolicy(Duration.ofDays(-1), 0, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TieringPolicy(Duration.ofDays(1), -1, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
