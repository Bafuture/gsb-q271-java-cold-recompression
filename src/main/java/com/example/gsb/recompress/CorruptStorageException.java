package com.example.gsb.recompress;

/** Raised when stored bytes fail integrity checks (checksum, GCM tag, magic). */
public class CorruptStorageException extends RuntimeException {
    public CorruptStorageException(String message) {
        super(message);
    }

    public CorruptStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
