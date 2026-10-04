package com.example.gsb.recompress;

import java.nio.charset.StandardCharsets;
import java.util.Random;

final class TestSupport {

    static final byte[] TEST_KEY = new byte[32];

    static {
        for (int i = 0; i < TEST_KEY.length; i++) {
            TEST_KEY[i] = (byte) (i + 1);
        }
    }

    private TestSupport() {
    }

    /** Deterministic, highly compressible payload. */
    static byte[] compressiblePayload(String tag, int bytes) {
        StringBuilder sb = new StringBuilder(bytes);
        while (sb.length() < bytes) {
            sb.append("line-").append(tag).append("-the-quick-brown-fox-jumps-over-the-lazy-dog\n");
        }
        sb.setLength(bytes);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Deterministic, incompressible payload. */
    static byte[] randomPayload(int seed, int bytes) {
        byte[] data = new byte[bytes];
        new Random(seed).nextBytes(data);
        return data;
    }
}
