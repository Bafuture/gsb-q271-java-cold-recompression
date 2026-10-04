package com.example.gsb.recompress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.Test;

class CodecTest {

    @Test
    void fastCodecRoundTrips() {
        byte[] data = randomBytes(4096);
        assertThat(Codecs.FAST.decompress(Codecs.FAST.compress(data))).isEqualTo(data);
    }

    @Test
    void highRatioCodecRoundTrips() {
        byte[] data = randomBytes(4096);
        assertThat(Codecs.HIGH_RATIO.decompress(Codecs.HIGH_RATIO.compress(data))).isEqualTo(data);
    }

    @Test
    void emptyPayloadRoundTrips() {
        for (CompressionCodec codec : new CompressionCodec[] {Codecs.FAST, Codecs.HIGH_RATIO}) {
            assertThat(codec.decompress(codec.compress(new byte[0]))).isEmpty();
        }
    }

    @Test
    void highRatioBeatsFastOnCompressibleData() {
        byte[] data = compressibleText(64 * 1024);
        byte[] fast = Codecs.FAST.compress(data);
        byte[] high = Codecs.HIGH_RATIO.compress(data);
        assertThat(high.length).isLessThan(fast.length);
        assertThat(high.length).isLessThan(data.length / 4);
    }

    @Test
    void decompressRejectsCorruptData() {
        byte[] compressed = Codecs.FAST.compress(compressibleText(1024));
        compressed[compressed.length / 2] ^= 0x7F;
        assertThatThrownBy(() -> Codecs.FAST.decompress(compressed))
                .isInstanceOf(StoreException.class);
    }

    @Test
    void registryResolvesKnownIdsAndRejectsUnknown() {
        assertThat(Codecs.byId(Codecs.FAST.id())).isSameAs(Codecs.FAST);
        assertThat(Codecs.byId(Codecs.HIGH_RATIO.id())).isSameAs(Codecs.HIGH_RATIO);
        assertThatThrownBy(() -> Codecs.byId(99)).isInstanceOf(StoreException.class);
    }

    static byte[] randomBytes(int length) {
        byte[] data = new byte[length];
        new Random(42).nextBytes(data);
        return data;
    }

    static byte[] compressibleText(int approxLength) {
        StringBuilder builder = new StringBuilder(approxLength);
        int line = 0;
        while (builder.length() < approxLength) {
            builder.append("2026-09-30T12:00:00Z INFO [worker-").append(line % 8)
                    .append("] request processed successfully user=user-").append(line % 1000)
                    .append(" action=GET /api/v1/orders status=200 latencyMs=").append(line % 500)
                    .append('\n');
            line++;
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }
}
