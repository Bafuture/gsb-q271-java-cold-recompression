package com.example.gsb.recompress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EncryptorTest {

    private final Encryptor encryptor = new Encryptor(Encryptor.generateKey());

    @Test
    void roundTrip() {
        byte[] data = CodecTest.compressibleText(2048);
        byte[] encrypted = encryptor.encrypt(data);
        assertThat(encrypted).isNotEqualTo(data);
        assertThat(encryptor.decrypt(encrypted)).isEqualTo(data);
    }

    @Test
    void tamperedCiphertextFailsAuthentication() {
        byte[] encrypted = encryptor.encrypt(CodecTest.randomBytes(256));
        encrypted[encrypted.length - 1] ^= 0x01;
        assertThatThrownBy(() -> encryptor.decrypt(encrypted)).isInstanceOf(StoreException.class);
    }

    @Test
    void wrongKeyFails() {
        byte[] encrypted = encryptor.encrypt(CodecTest.randomBytes(128));
        Encryptor other = new Encryptor(Encryptor.generateKey());
        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(StoreException.class);
    }

    @Test
    void rejectsInvalidKeyLength() {
        assertThatThrownBy(() -> new Encryptor(new byte[7]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
