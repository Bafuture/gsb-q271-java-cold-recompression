package com.example.gsb.recompress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class RecordFileTest {

    @Test
    void encodeDecodeRoundTrip() {
        byte[] payload = CodecTest.randomBytes(1024);
        byte[] file = RecordFile.encode(Codecs.FAST.id(), payload);
        RecordFile.Decoded decoded = RecordFile.decode(file);
        assertThat(decoded.codecId()).isEqualTo(Codecs.FAST.id());
        assertThat(decoded.payload()).isEqualTo(payload);
    }

    @Test
    void decodeRejectsTruncatedFile() {
        byte[] file = RecordFile.encode(Codecs.FAST.id(), CodecTest.randomBytes(512));
        byte[] truncated = Arrays.copyOf(file, file.length - 10);
        assertThatThrownBy(() -> RecordFile.decode(truncated)).isInstanceOf(StoreException.class);
        assertThat(RecordFile.isValid(truncated)).isFalse();
    }

    @Test
    void decodeRejectsCorruptPayload() {
        byte[] file = RecordFile.encode(Codecs.FAST.id(), CodecTest.randomBytes(512));
        file[file.length - 1] ^= 0x01;
        assertThatThrownBy(() -> RecordFile.decode(file)).isInstanceOf(StoreException.class);
        assertThat(RecordFile.isValid(file)).isFalse();
    }

    @Test
    void decodeRejectsBadMagic() {
        byte[] file = RecordFile.encode(Codecs.FAST.id(), CodecTest.randomBytes(64));
        file[0] ^= 0xFF;
        assertThatThrownBy(() -> RecordFile.decode(file)).isInstanceOf(StoreException.class);
    }
}
