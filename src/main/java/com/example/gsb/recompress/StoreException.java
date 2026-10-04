package com.example.gsb.recompress;

/** Unchecked exception raised for store-level failures (IO, corruption, crypto). */
public class StoreException extends RuntimeException {

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
