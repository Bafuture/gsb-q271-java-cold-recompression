package com.example.gsb.recompress;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Decides whether a record is hot or cold from its age and access frequency.
 * A record is cold when it is at least {@code coldAge} old AND has been
 * accessed at most {@code maxAccessesForCold} times within the last
 * {@code accessWindow}. Everything else is hot.
 */
public final class TieringPolicy {

    private final Duration coldAge;
    private final int maxAccessesForCold;
    private final Duration accessWindow;

    public TieringPolicy(Duration coldAge, int maxAccessesForCold, Duration accessWindow) {
        if (coldAge.isNegative()) {
            throw new IllegalArgumentException("coldAge must not be negative");
        }
        if (maxAccessesForCold < 0) {
            throw new IllegalArgumentException("maxAccessesForCold must not be negative");
        }
        if (accessWindow.isNegative()) {
            throw new IllegalArgumentException("accessWindow must not be negative");
        }
        this.coldAge = coldAge;
        this.maxAccessesForCold = maxAccessesForCold;
        this.accessWindow = accessWindow;
    }

    public Tier classify(RecordMeta meta, Clock clock) {
        Objects.requireNonNull(meta, "meta");
        Instant now = clock.instant();
        Duration age = Duration.between(meta.createdAt(), now);
        if (age.compareTo(coldAge) < 0) {
            return Tier.HOT;
        }
        int recentAccesses = meta.accessesWithin(now.minus(accessWindow), now);
        return recentAccesses <= maxAccessesForCold ? Tier.COLD : Tier.HOT;
    }

    public Duration coldAge() {
        return coldAge;
    }

    public int maxAccessesForCold() {
        return maxAccessesForCold;
    }

    public Duration accessWindow() {
        return accessWindow;
    }
}
