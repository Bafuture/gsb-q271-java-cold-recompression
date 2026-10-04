package com.example.gsb.recompress;

import java.util.Map;
import java.util.zip.Deflater;

/** Registry of the built-in codecs. Hot tier uses speed, cold tier uses ratio. */
public final class Codecs {

    /** Fast compression for the hot tier. */
    public static final CompressionCodec FAST =
            new DeflaterCodec(1, "deflate-fast", Deflater.BEST_SPEED);

    /** High-ratio compression for the cold tier. */
    public static final CompressionCodec HIGH_RATIO =
            new DeflaterCodec(2, "deflate-high", Deflater.BEST_COMPRESSION);

    private static final Map<Integer, CompressionCodec> BY_ID = Map.of(
            FAST.id(), FAST,
            HIGH_RATIO.id(), HIGH_RATIO);

    private Codecs() {
    }

    public static CompressionCodec byId(int id) {
        CompressionCodec codec = BY_ID.get(id);
        if (codec == null) {
            throw new StoreException("unknown codec id: " + id);
        }
        return codec;
    }
}
