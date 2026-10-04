package com.example.gsb.recompress;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that reads stay complete and correct while recompression runs in the background. */
class BackgroundRecompressionTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path dir;

    @Test
    void readsDuringBackgroundRecompressionAlwaysReturnCompleteData() throws Exception {
        MutableClock clock = new MutableClock(T0);
        int recordCount = 200;
        List<byte[]> expected = new ArrayList<>();
        try (ColdDataStore store = new ColdDataStore(dir,
                // High access ceiling: concurrent reads must not promote records back to hot.
                new TierPolicy(Duration.ofDays(30), 1_000_000),
                new Encryptor(Encryptor.generateKey()), clock)) {
            for (int i = 0; i < recordCount; i++) {
                byte[] payload = CodecTest.compressibleText(2048);
                expected.add(payload);
                store.put("rec-" + i, payload);
            }
            clock.advance(Duration.ofDays(31));

            AtomicBoolean keepReading = new AtomicBoolean(true);
            AtomicReference<Throwable> readerFailure = new AtomicReference<>();
            CountDownLatch readersStarted = new CountDownLatch(4);
            List<Thread> readers = new ArrayList<>();
            for (int r = 0; r < 4; r++) {
                final int readerId = r;
                Thread reader = new Thread(() -> {
                    readersStarted.countDown();
                    int i = readerId;
                    while (keepReading.get()) {
                        byte[] actual = store.get("rec-" + (i % recordCount));
                        if (!java.util.Arrays.equals(actual, expected.get(i % recordCount))) {
                            readerFailure.compareAndSet(null,
                                    new AssertionError("torn read of rec-" + (i % recordCount)));
                            return;
                        }
                        i += 4;
                    }
                }, "reader-" + r);
                reader.setDaemon(true);
                readers.add(reader);
                reader.start();
            }
            assertThat(readersStarted.await(10, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<RecompressionStats> future = store.recompressColdAsync();
            RecompressionStats stats = future.get(60, TimeUnit.SECONDS);
            keepReading.set(false);
            for (Thread reader : readers) {
                reader.join(10_000);
            }

            assertThat(readerFailure.get()).isNull();
            assertThat(stats.recordsRecompressed()).isEqualTo(recordCount);
            for (int i = 0; i < recordCount; i++) {
                assertThat(store.codecIdOf("rec-" + i)).isEqualTo(Codecs.HIGH_RATIO.id());
                assertThat(store.get("rec-" + i)).isEqualTo(expected.get(i));
            }
        }
    }

    @Test
    void asyncRecompressionIsObservableThroughStats() throws Exception {
        MutableClock clock = new MutableClock(T0);
        try (ColdDataStore store = new ColdDataStore(dir,
                new TierPolicy(Duration.ofDays(30), 3),
                new Encryptor(Encryptor.generateKey()), clock)) {
            for (int i = 0; i < 20; i++) {
                store.put("rec-" + i, CodecTest.compressibleText(8192));
            }
            clock.advance(Duration.ofDays(31));

            RecompressionStats stats = store.recompressColdAsync().get(30, TimeUnit.SECONDS);

            assertThat(stats.recordsRecompressed()).isEqualTo(20);
            assertThat(stats.bytesAfter()).isLessThan(stats.bytesBefore());
        }
    }
}
