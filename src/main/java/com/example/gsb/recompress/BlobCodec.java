package com.example.gsb.recompress;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Encodes/decodes the on-disk blob format:
 * <pre>
 *   magic   "RCB1"        4 bytes
 *   tier    HOT=1/COLD=2  1 byte
 *   iv      random        12 bytes
 *   payload AES-256-GCM(deflate(plaintext)), tag appended by GCM
 * </pre>
 * The first 17 bytes (magic + tier + iv) are used as GCM AAD, so the tier
 * marker is authenticated together with the payload.
 */
public final class BlobCodec {

    public static final byte[] MAGIC = {'R', 'C', 'B', '1'};
    public static final int HEADER_LENGTH = MAGIC.length + 1 + CryptoService.IV_LENGTH;

    private final CryptoService crypto;

    public BlobCodec(CryptoService crypto) {
        this.crypto = crypto;
    }

    public byte[] encode(byte[] plaintext, Tier tier) {
        byte[] compressed = compress(plaintext, CompressionProfile.forTier(tier));
        byte[] iv = crypto.newIv();
        byte[] header = header(tier, iv);
        byte[] encrypted = crypto.encrypt(compressed, iv, header);
        byte[] blob = new byte[header.length + encrypted.length];
        System.arraycopy(header, 0, blob, 0, header.length);
        System.arraycopy(encrypted, 0, blob, header.length, encrypted.length);
        return blob;
    }

    public byte[] decode(byte[] blob) {
        if (blob == null || blob.length < HEADER_LENGTH + 16) {
            throw new CorruptStorageException("blob too short: " + (blob == null ? -1 : blob.length));
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (blob[i] != MAGIC[i]) {
                throw new CorruptStorageException("bad magic in blob header");
            }
        }
        Tier tier = Tier.fromCode(blob[MAGIC.length]);
        byte[] header = Arrays.copyOf(blob, HEADER_LENGTH);
        byte[] iv = Arrays.copyOfRange(blob, MAGIC.length + 1, HEADER_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(blob, HEADER_LENGTH, blob.length);
        byte[] compressed = crypto.decrypt(ciphertext, iv, header);
        return decompress(compressed, tier);
    }

    public Tier tierOf(byte[] blob) {
        if (blob == null || blob.length < HEADER_LENGTH) {
            throw new CorruptStorageException("blob too short to read tier");
        }
        return Tier.fromCode(blob[MAGIC.length]);
    }

    private static byte[] header(Tier tier, byte[] iv) {
        byte[] header = new byte[HEADER_LENGTH];
        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        header[MAGIC.length] = tier.code();
        System.arraycopy(iv, 0, header, MAGIC.length + 1, iv.length);
        return header;
    }

    static byte[] compress(byte[] data, CompressionProfile profile) {
        Deflater deflater = new Deflater(profile.deflateLevel(), true);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buffer);
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    static byte[] decompress(byte[] data, Tier tier) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 2));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0) {
                    if (inflater.finished()) {
                        break;
                    }
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw new CorruptStorageException("truncated deflate stream for tier " + tier);
                    }
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new CorruptStorageException("invalid deflate stream for tier " + tier, e);
        } finally {
            inflater.end();
        }
    }
}
