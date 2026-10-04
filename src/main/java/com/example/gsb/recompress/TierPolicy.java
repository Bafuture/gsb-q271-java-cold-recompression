package com.example.gsb.recompress;

import java.time.Duration;
import java.time.Instant;

/**
 * Two-tier classification based on data age and access frequency:
 * data at least {@code coldAge} old AND accessed at most {@code coldMaxAccesses}
 * times is considered COLD; everything else is HOT.
 */
public final class TierPolicy {

    private final Duration coldAge;
    private final long coldMaxAccesses;

    public TierPolicy(Duration coldAge, long coldMaxAccesses) {
        if (coldAge == null || coldAge.isNegative()) {
            throw new IllegalArgumentException("coldAge must be a non-null, non-negative duration");
        }
        if (coldMaxAccesses < 0) {
            throw new IllegalArgumentException("coldMaxAccesses must be non-negative");
        }
        this.coldAge = coldAge;
        this.coldMaxAccesses = coldMaxAccesses;
    }

    public Duration coldAge() {
        return coldAge;
    }

    public long coldMaxAccesses() {
        return coldMaxAccesses;
    }

    public Tier classify(RecordMeta meta, Instant now) {
        boolean oldEnough = !meta.createdAt().plus(coldAge).isAfter(now);
        boolean rarelyAccessed = meta.accessCount() <= coldMaxAccesses;
        return oldEnough && rarelyAccessed ? Tier.COLD : Tier.HOT;
    }
}
