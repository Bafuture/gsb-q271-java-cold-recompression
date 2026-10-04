package com.example.gsb.recompress;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlobCodecTest {

    private final BlobCodec codec = new BlobCodec(new CryptoService(TestSupport.TEST_KEY));

    @Test
    void roundTripBothTiers() {
        byte[] payload = TestSupport.compressiblePayload("codec", 4096);
        for (Tier tier : Tier.values()) {
            byte[] blob = codec.encode(payload, tier);
            assertThat(codec.tierOf(blob)).isEqualTo(tier);
            assertThat(codec.decode(blob)).isEqualTo(payload);
        }
    }

    @Test
    void emptyPayloadRoundTrips() {
        byte[] blob = codec.encode(new byte[0], Tier.COLD);
        assertThat(codec.decode(blob)).isEmpty();
    }

    @Test
    void coldProfileCompressesAtLeastAsWellAsHot() {
        byte[] payload = TestSupport.compressiblePayload("ratio", 16 * 1024);
        byte[] hot = codec.encode(payload, Tier.HOT);
        byte[] cold = codec.encode(payload, Tier.COLD);
        assertThat(cold.length).isLessThanOrEqualTo(hot.length);
        assertThat(hot.length).isLessThan(payload.length);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] blob = codec.encode(TestSupport.compressiblePayload("tamper", 1024), Tier.HOT);
        blob[blob.length - 1] ^= 0x01;
        assertThatThrownBy(() -> codec.decode(blob)).isInstanceOf(CorruptStorageException.class);
    }

    @Test
    void tamperedTierMarkerIsRejected() {
        byte[] blob = codec.encode(TestSupport.compressiblePayload("tier", 1024), Tier.HOT);
        blob[BlobCodec.MAGIC.length] = Tier.COLD.code(); // AAD covers the tier byte
        assertThatThrownBy(() -> codec.decode(blob)).isInstanceOf(CorruptStorageException.class);
    }

    @Test
    void truncatedBlobIsRejected() {
        byte[] blob = codec.encode(TestSupport.compressiblePayload("trunc", 1024), Tier.COLD);
        byte[] truncated = new byte[blob.length / 2];
        System.arraycopy(blob, 0, truncated, 0, truncated.length);
        assertThatThrownBy(() -> codec.decode(truncated)).isInstanceOf(CorruptStorageException.class);
    }

    @Test
    void wrongKeyIsRejected() {
        byte[] blob = codec.encode(TestSupport.compressiblePayload("key", 512), Tier.HOT);
        byte[] otherKey = new byte[32];
        BlobCodec other = new BlobCodec(new CryptoService(otherKey));
        assertThatThrownBy(() -> other.decode(blob)).isInstanceOf(CorruptStorageException.class);
    }
}
