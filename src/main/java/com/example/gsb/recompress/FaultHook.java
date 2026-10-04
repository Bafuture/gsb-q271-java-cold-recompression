package com.example.gsb.recompress;

/**
 * Test hook invoked at well-defined points of the recompression pipeline so
 * tests can simulate a crash (by throwing) or block (to observe concurrent
 * reads). Production code never installs a hook.
 */
@FunctionalInterface
public interface FaultHook {

    enum Phase {
        /** After the new blob was encoded, before it is staged. */
        AFTER_ENCODE,
        /** After the staged file is durable, before the commit rename. */
        BEFORE_COMMIT,
        /** After the commit rename, before the journal DONE record. */
        AFTER_COMMIT
    }

    void onPhase(Phase phase, String recordId) throws Exception;
}
