package com.example.gsb.recompress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record must stay readable while recompression is in flight, and every
 * read must return a complete copy (old before the rename, new afterwards).
 */
class ReadableDuringRecompressionTest {

    @TempDir
    Path dir;

    private final byte[] payload = TestSupport.compressiblePayload("live", 8192);

    @Test
    void readsBeforeAndAfterSwitchReturnCompleteContent() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TieringPolicy policy = new TieringPolicy(Duration.ofDays(1), 0, Duration.ofDays(1));
        try (RecompressionManager manager =
                     new RecompressionManager(dir, TestSupport.TEST_KEY, policy, clock)) {
            manager.put("live", payload);
            clock.advance(Duration.ofDays(2));

            CountDownLatch atCommitPoint = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            manager.setFaultHook((phase, id) -> {
                if (phase == FaultHook.Phase.BEFORE_COMMIT && "live".equals(id)) {
                    atCommitPoint.countDown();
                    release.await();
                }
            });

            Thread worker = new Thread(() -> manager.recompress("live"));
            worker.start();
            try {
                assertThat(atCommitPoint.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

                // Old copy is still live at this point.
                assertThat(manager.storedTier("live")).isEqualTo(Tier.HOT);
                assertThat(manager.get("live")).isEqualTo(payload);

                release.countDown();
                worker.join(5000);
                assertThat(worker.isAlive()).isFalse();

                // New copy is live and equally complete.
                assertThat(manager.storedTier("live")).isEqualTo(Tier.COLD);
                assertThat(manager.get("live")).isEqualTo(payload);
            } finally {
                release.countDown();
                worker.join(5000);
            }
        }
    }
}
