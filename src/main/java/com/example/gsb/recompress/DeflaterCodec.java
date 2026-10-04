package com.example.gsb.recompress;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Compression codec backed by {@link Deflater}/{@link Inflater} at a configurable level. */
public final class DeflaterCodec implements CompressionCodec {

    private final int id;
    private final String name;
    private final int level;

    public DeflaterCodec(int id, String name, int level) {
        this.id = id;
        this.name = name;
        this.level = level;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public byte[] compress(byte[] plain) {
        Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(plain);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, plain.length / 2));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int produced = deflater.deflate(buffer);
                if (produced == 0 && deflater.needsInput()) {
                    break;
                }
                out.write(buffer, 0, produced);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    @Override
    public byte[] decompress(byte[] compressed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, compressed.length * 2));
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int produced;
                try {
                    produced = inflater.inflate(buffer);
                } catch (DataFormatException e) {
                    throw new StoreException("corrupt compressed payload", e);
                }
                if (produced == 0 && !inflater.finished()
                        && (inflater.needsDictionary() || inflater.needsInput())) {
                    throw new StoreException("truncated compressed payload");
                }
                out.write(buffer, 0, produced);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }
}
