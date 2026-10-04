package com.example.gsb.recompress;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;

/** Access metadata of one record, persisted alongside the encoded payload. */
public final class RecordMeta {

    private static final int MAGIC = 0x4D455441; // "META"

    private final String id;
    private final Instant createdAt;
    private volatile Instant lastAccessAt;
    private long accessCount;
    private volatile boolean dirty;

    public RecordMeta(String id, Instant createdAt) {
        this(id, createdAt, createdAt, 0L);
    }

    public RecordMeta(String id, Instant createdAt, Instant lastAccessAt, long accessCount) {
        this.id = id;
        this.createdAt = createdAt;
        this.lastAccessAt = lastAccessAt;
        this.accessCount = accessCount;
    }

    public String id() {
        return id;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastAccessAt() {
        return lastAccessAt;
    }

    public synchronized long accessCount() {
        return accessCount;
    }

    /** Marks a read at {@code at}; hot data that keeps being read stays hot. */
    public synchronized void recordAccess(Instant at) {
        this.accessCount++;
        this.lastAccessAt = at;
        this.dirty = true;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized void markClean() {
        this.dirty = false;
    }

    public byte[] encode() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
                DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeLong(createdAt.toEpochMilli());
            out.writeLong(lastAccessAt.toEpochMilli());
            out.writeLong(accessCount);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new StoreException("failed to encode meta for " + id, e);
        }
    }

    public static RecordMeta decode(String id, byte[] raw) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            if (in.readInt() != MAGIC) {
                throw new StoreException("bad meta magic for " + id);
            }
            Instant createdAt = Instant.ofEpochMilli(in.readLong());
            Instant lastAccessAt = Instant.ofEpochMilli(in.readLong());
            long accessCount = in.readLong();
            return new RecordMeta(id, createdAt, lastAccessAt, accessCount);
        } catch (IOException e) {
            throw new StoreException("failed to decode meta for " + id, e);
        }
    }
}
