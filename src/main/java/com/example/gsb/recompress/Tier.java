package com.example.gsb.recompress;

/** Storage tier of a record. Hot data uses a fast codec, cold data a high-ratio codec. */
public enum Tier {
    HOT((byte) 1),
    COLD((byte) 2);

    private final byte code;

    Tier(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static Tier fromCode(byte code) {
        for (Tier tier : values()) {
            if (tier.code == code) {
                return tier;
            }
        }
        throw new IllegalArgumentException("unknown tier code: " + code);
    }
}
