package com.example.gsb.recompress;

/** Thrown by test hooks to simulate a process crash mid-recompression. */
public class SimulatedCrashException extends RuntimeException {

    public SimulatedCrashException(String message) {
        super(message);
    }
}
