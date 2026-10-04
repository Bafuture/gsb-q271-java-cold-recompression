package com.example.gsb.recompress;

/**
 * Callbacks fired at each durability boundary of a recompression.
 * Production code uses {@link #NONE}; tests use hooks to inject crashes.
 */
public interface RecompressionHook {

    RecompressionHook NONE = new RecompressionHook() {
    };

    /** Staged replacement file is fully written and durable; journal not yet written. */
    default void afterStagingWritten(String recordId) {
    }

    /** Journal entry is durable; atomic swap not yet performed. */
    default void afterJournalWritten(String recordId) {
    }

    /** Atomic swap completed; journal not yet removed. */
    default void afterAtomicSwap(String recordId) {
    }
}
