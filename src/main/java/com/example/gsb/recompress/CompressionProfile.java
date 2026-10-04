package com.example.gsb.recompress;

import java.util.zip.Deflater;

/**
 * Per-tier compression strategy. Hot data favours speed (level 1),
 * cold data favours ratio (level 9). Both use raw DEFLATE.
 */
public enum CompressionProfile {
    FAST(Deflater.BEST_SPEED),
    HIGH(Deflater.BEST_COMPRESSION);

    private final int deflateLevel;

    CompressionProfile(int deflateLevel) {
        this.deflateLevel = deflateLevel;
    }

    public int deflateLevel() {
        return deflateLevel;
    }

    public static CompressionProfile forTier(Tier tier) {
        return switch (tier) {
            case HOT -> FAST;
            case COLD -> HIGH;
        };
    }
}
