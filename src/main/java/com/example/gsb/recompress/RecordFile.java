package com.example.gsb.recompress;

import java.nio.ByteBuffer;
import java.util.zip.CRC32;

/**
 * On-disk record format:
 * <pre>
 *   magic      4 bytes  "GRC1"
 *   version    1 byte
 *   codecId    1 byte
 *   reserved   2 bytes
 *   crc32      4 bytes  CRC of the payload
 *   length     4 bytes  payload length
 *   payload    N bytes  encrypted(compressed(plaintext))
 * </pre>
 */
public final class RecordFile {

    static final int MAGIC = 0x47524331; // "GRC1"
    private static final int VERSION = 1;
    static final int HEADER_LENGTH = 16;

    private RecordFile() {
    }

    public static byte[] encode(int codecId, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_LENGTH + payload.length);
        buffer.putInt(MAGIC);
        buffer.put((byte) VERSION);
        buffer.put((byte) codecId);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32(payload));
        buffer.putInt(payload.length);
        buffer.put(payload);
        return buffer.array();
    }

    public static Decoded decode(byte[] fileBytes) {
        if (fileBytes.length < HEADER_LENGTH) {
            throw new StoreException("record file too short: " + fileBytes.length + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(fileBytes);
        if (buffer.getInt() != MAGIC) {
            throw new StoreException("bad record magic");
        }
        int version = buffer.get();
        if (version != VERSION) {
            throw new StoreException("unsupported record version: " + version);
        }
        int codecId = buffer.get();
        buffer.getShort(); // reserved
        long expectedCrc = Integer.toUnsignedLong(buffer.getInt());
        int length = buffer.getInt();
        if (length < 0 || fileBytes.length != HEADER_LENGTH + length) {
            throw new StoreException("record length mismatch: header=" + length
                    + " actual=" + (fileBytes.length - HEADER_LENGTH));
        }
        byte[] payload = new byte[length];
        buffer.get(payload);
        if (crc32(payload) != expectedCrc) {
            throw new StoreException("record payload crc mismatch");
        }
        return new Decoded(codecId, payload);
    }

    public static boolean isValid(byte[] fileBytes) {
        try {
            decode(fileBytes);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    /** A decoded record file: codec id plus the still-encrypted payload. */
    public record Decoded(int codecId, byte[] payload) {
    }
}
