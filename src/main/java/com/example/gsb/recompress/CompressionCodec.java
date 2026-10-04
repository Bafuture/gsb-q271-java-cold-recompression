package com.example.gsb.recompress;

/** A lossless compression scheme identified by a small integer stored in record headers. */
public interface CompressionCodec {

    /** Stable id persisted in record files; never reuse an id for a different codec. */
    int id();

    String name();

    byte[] compress(byte[] plain);

    byte[] decompress(byte[] compressed);
}
