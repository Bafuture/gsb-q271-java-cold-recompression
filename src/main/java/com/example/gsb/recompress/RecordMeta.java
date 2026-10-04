package com.example.gsb.recompress;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Mutable per-record metadata: tier, timestamps and a bounded window of
 * access timestamps used by the tiering policy. Persisted next to the blob.
 */
public final class RecordMeta {

    private static final int MAX_TRACKED_ACCESSES = 1024;

    private final String id;
    private final Instant createdAt;
    private Tier tier;
    private Instant lastAccessAt;
    private final Deque<Instant> accesses = new ArrayDeque<>();

    public RecordMeta(String id, Tier tier, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tier = Objects.requireNonNull(tier, "tier");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lastAccessAt = createdAt;
    }

    public String id() {
        return id;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized Tier tier() {
        return tier;
    }

    public synchronized void setTier(Tier newTier) {
        this.tier = Objects.requireNonNull(newTier, "newTier");
    }

    public synchronized Instant lastAccessAt() {
        return lastAccessAt;
    }

    synchronized void restoreLastAccess(Instant at) {
        this.lastAccessAt = at;
    }

    public synchronized void recordAccess(Instant at) {
        lastAccessAt = at;
        accesses.addLast(at);
        while (accesses.size() > MAX_TRACKED_ACCESSES) {
            accesses.removeFirst();
        }
    }

    public synchronized int accessesWithin(Instant fromInclusive, Instant toInclusive) {
        int count = 0;
        for (Instant access : accesses) {
            if (!access.isBefore(fromInclusive) && !access.isAfter(toInclusive)) {
                count++;
            }
        }
        return count;
    }

    public synchronized List<Instant> accessSnapshot() {
        return new ArrayList<>(accesses);
    }

    public synchronized void restoreAccesses(List<Instant> instants) {
        accesses.clear();
        accesses.addAll(instants);
    }
}
