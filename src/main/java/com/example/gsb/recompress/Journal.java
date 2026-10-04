package com.example.gsb.recompress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Write-ahead journal entry for an in-flight recompression.
 * Written after the staged file is durable, before the atomic swap.
 * Contains the CRC of the staged file so recovery can decide between
 * completing the swap (continue) or deleting the staging file (discard).
 */
final class Journal {

    private static final int MAGIC = 0x4A524E31; // "JRN1"

    private Journal() {
    }

    static byte[] encode(String recordId, long stagedCrc) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
                DataOutputStream out = new DataOutputStream(bytes)) {
            byte[] id = recordId.getBytes(StandardCharsets.UTF_8);
            out.writeInt(MAGIC);
            out.writeShort(id.length);
            out.write(id);
            out.writeLong(stagedCrc);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new StoreException("failed to encode journal for " + recordId, e);
        }
    }

    static Entry decode(byte[] raw) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            if (in.readInt() != MAGIC) {
                throw new StoreException("bad journal magic");
            }
            int idLength = in.readShort();
            byte[] id = in.readNBytes(idLength);
            if (id.length != idLength) {
                throw new StoreException("truncated journal entry");
            }
            long stagedCrc = in.readLong();
            return new Entry(new String(id, StandardCharsets.UTF_8), stagedCrc);
        } catch (IOException e) {
            throw new StoreException("failed to decode journal", e);
        }
    }

    record Entry(String recordId, long stagedCrc) {
    }
}
