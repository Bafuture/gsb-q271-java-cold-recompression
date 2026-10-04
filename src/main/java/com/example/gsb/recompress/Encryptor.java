package com.example.gsb.recompress;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES/GCM encryption applied on top of compression.
 * Layout of an encrypted blob: {@code [12-byte IV][ciphertext + 16-byte GCM tag]}.
 */
public final class Encryptor {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public Encryptor(byte[] keyBytes) {
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalArgumentException("AES key must be 16, 24 or 32 bytes");
        }
        this.key = new SecretKeySpec(keyBytes.clone(), "AES");
    }

    public static byte[] generateKey() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        return key;
    }

    public byte[] encrypt(byte[] plain) {
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain);
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new StoreException("encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] encrypted) {
        if (encrypted.length <= IV_LENGTH) {
            throw new StoreException("encrypted blob too short");
        }
        byte[] iv = Arrays.copyOfRange(encrypted, 0, IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(encrypted, IV_LENGTH, encrypted.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new StoreException("decryption failed", e);
        }
    }
}
