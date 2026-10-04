package com.example.gsb.recompress;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColdDataStoreTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path dir;

    private TierPolicy policy() {
        return new TierPolicy(Duration.ofDays(30), 3);
    }

    @Test
    void putGetRoundTripsThroughCompressionAndEncryption() {
        MutableClock clock = new MutableClock(T0);
        try (ColdDataStore store = new ColdDataStore(dir, policy(),
                new Encryptor(Encryptor.generateKey()), clock)) {
            byte[] payload = CodecTest.compressibleText(8192);
            store.put("rec-1", payload);
            assertThat(store.get("rec-1")).isEqualTo(payload);
        }
    }

    @Test
    void committedFileNeverContainsPlaintext() throws IOException {
        MutableClock clock = new MutableClock(T0);
        String secret = "super-secret-marker-AAAAAAAAAAAA";
        try (ColdDataStore store = new ColdDataStore(dir, policy(),
                new Encryptor(Encryptor.generateKey()), clock)) {
            store.put("rec-1", secret.repeat(32).getBytes());
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                // ISO-8859-1 maps bytes 1:1 to chars, so this is a byte-substring search.
                String content = new String(Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                assertThat(content)
                        .as("%s must not leak plaintext", file)
                        .doesNotContain(secret);
            }
        }
    }

    @Test
    void tierAtWriteTimeSelectsCodec() {
        MutableClock clock = new MutableClock(T0);
        try (ColdDataStore store = new ColdDataStore(dir, policy(),
                new Encryptor(Encryptor.generateKey()), clock)) {
            store.put("hot", CodecTest.compressibleText(4096));
            assertThat(store.tierOf("hot")).isEqualTo(Tier.HOT);
            assertThat(store.codecIdOf("hot")).isEqualTo(Codecs.FAST.id());
        }
        // A store whose policy treats everything as cold writes with the high-ratio codec.
        try (ColdDataStore store = new ColdDataStore(dir.resolve("cold-store"),
                new TierPolicy(Duration.ZERO, 0),
                new Encryptor(Encryptor.generateKey()), clock)) {
            store.put("cold", CodecTest.compressibleText(4096));
            assertThat(store.tierOf("cold")).isEqualTo(Tier.COLD);
            assertThat(store.codecIdOf("cold")).isEqualTo(Codecs.HIGH_RATIO.id());
        }
    }

    @Test
    void frequentlyAccessedOldDataIsNotRecompressed() {
        MutableClock clock = new MutableClock(T0);
        try (ColdDataStore store = new ColdDataStore(dir, policy(),
                new Encryptor(Encryptor.generateKey()), clock)) {
            store.put("busy", CodecTest.compressibleText(4096));
            clock.advance(Duration.ofDays(60));
            assertThat(store.tierOf("busy")).isEqualTo(Tier.COLD);
            for (int i = 0; i < 5; i++) {
                assertThat(store.get("busy")).isNotEmpty();
            }
            assertThat(store.tierOf("busy")).isEqualTo(Tier.HOT);

            store.recompressCold();

            assertThat(store.codecIdOf("busy")).isEqualTo(Codecs.FAST.id());
            assertThat(store.stats().recordsRecompressed()).isZero();
        }
    }

    @Test
    void atomicSwapKeepsOldCopyReadableUntilTheSingleSwitch() {
        MutableClock clock = new MutableClock(T0);
        byte[] payload = CodecTest.compressibleText(8192);
        AtomicReference<ColdDataStore> storeRef = new AtomicReference<>();
        RecompressionHook hook = new RecompressionHook() {
            @Override
            public void afterStagingWritten(String recordId) {
                // Staging written, journal not durable yet: old copy must still serve reads.
                assertThat(storeRef.get().codecIdOf(recordId)).isEqualTo(Codecs.FAST.id());
            }

            @Override
            public void afterJournalWritten(String recordId) {
                // About to swap: the committed file is still the complete old copy.
                assertThat(storeRef.get().codecIdOf(recordId)).isEqualTo(Codecs.FAST.id());
                assertThat(storeRef.get().get(recordId)).isEqualTo(payload);
            }
        };
        try (ColdDataStore store = new ColdDataStore(dir, policy(),
                new Encryptor(Encryptor.generateKey()), clock, hook, true)) {
            storeRef.set(store);
            store.put("rec-1", payload);
            clock.advance(Duration.ofDays(31));

            store.recompressCold();

            assertThat(store.codecIdOf("rec-1")).isEqualTo(Codecs.HIGH_RATIO.id());
            assertThat(store.get("rec-1")).isEqualTo(payload);
        }
    }

    @Test
    void contentIsIdenticalForEveryRecordAfterRecompression() {
        MutableClock clock = new MutableClock(T0);
        Random random = new Random(7);
        int count = 300;
        List<byte[]> expected = new ArrayList<>();
        try (ColdDataStore store = new ColdDataStore(dir, policy(),
                new Encryptor(Encryptor.generateKey()), clock)) {
            for (int i = 0; i < count; i++) {
                byte[] payload = CodecTest.compressibleText(512 + random.nextInt(2048));
                expected.add(payload);
                store.put("rec-" + i, payload);
            }
            clock.advance(Duration.ofDays(31));

            store.recompressCold();

            // Decrypt + decompress every record and compare with the original, item by item.
            for (int i = 0; i < count; i++) {
                assertThat(store.codecIdOf("rec-" + i)).isEqualTo(Codecs.HIGH_RATIO.id());
                assertThat(store.get("rec-" + i))
                        .as("record %d must be byte-identical after recompression", i)
                        .isEqualTo(expected.get(i));
            }
            assertThat(store.stats().recordsRecompressed()).isEqualTo(count);
        }
    }

    @Test
    void alreadyColdRecordsAreSkipped() {
        MutableClock clock = new MutableClock(T0);
        try (ColdDataStore store = new ColdDataStore(dir, policy(),
                new Encryptor(Encryptor.generateKey()), clock)) {
            store.put("rec-1", CodecTest.compressibleText(1024));
            clock.advance(Duration.ofDays(31));

            store.recompressCold();
            assertThat(store.stats().recordsRecompressed()).isEqualTo(1);
            assertThat(store.codecIdOf("rec-1")).isEqualTo(Codecs.HIGH_RATIO.id());

            // Second pass: already encoded with the cold codec, nothing to do.
            store.recompressCold();
            assertThat(store.stats().recordsRecompressed()).isEqualTo(1);
        }
    }

    @Test
    void statsReportCountsRatiosSpaceSavedAndElapsed() {
        MutableClock clock = new MutableClock(T0);
        try (ColdDataStore store = new ColdDataStore(dir, policy(),
                new Encryptor(Encryptor.generateKey()), clock)) {
            for (int i = 0; i < 50; i++) {
                store.put("rec-" + i, CodecTest.compressibleText(4096));
            }
            clock.advance(Duration.ofDays(31));

            RecompressionStats stats = store.recompressCold();

            assertThat(stats.recordsRecompressed()).isEqualTo(50);
            assertThat(stats.recoveryCount()).isZero();
            assertThat(stats.bytesAfter()).isLessThan(stats.bytesBefore());
            assertThat(stats.spaceSavedBytes())
                    .isEqualTo(stats.bytesBefore() - stats.bytesAfter());
            assertThat(stats.plaintextBytes()).isGreaterThan(stats.bytesBefore());
            assertThat(stats.compressionRatioAfter())
                    .isLessThan(stats.compressionRatioBefore());
            assertThat(stats.recompressNanos()).isGreaterThan(0);
            assertThat(stats.recompressMillis()).isGreaterThan(0);
        }
    }

    @Test
    void invalidIdsAreRejected() {
        try (ColdDataStore store = new ColdDataStore(dir, policy(),
                new Encryptor(Encryptor.generateKey()), Clock.systemUTC())) {
            try {
                store.put("../escape", new byte[1]);
                throw new AssertionError("expected rejection");
            } catch (IllegalArgumentException expected) {
                // expected
            }
            try {
                store.get("space in id");
                throw new AssertionError("expected rejection");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }
}
