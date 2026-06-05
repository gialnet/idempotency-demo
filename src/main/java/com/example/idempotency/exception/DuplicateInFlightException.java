package com.example.idempotency.exception;

/**
 * Thrown when a concurrent request with the same idempotency key is already
 * in-flight (hot-path Redis lock already held).
 */
public class DuplicateInFlightException extends RuntimeException {

    private final String idempotencyKey;

    public DuplicateInFlightException(String key) {
        super("Duplicate in-flight request for idempotency key: " + key);
        this.idempotencyKey = key;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
