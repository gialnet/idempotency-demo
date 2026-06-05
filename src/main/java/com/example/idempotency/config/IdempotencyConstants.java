package com.example.idempotency.config;

public final class IdempotencyConstants {
    private IdempotencyConstants() {}
    public static final String HEADER = "Idempotency-Key";
    public static final long LOCK_TTL_SECONDS = 30L;
    public static final String IN_FLIGHT_VALUE = "IN_FLIGHT";
}
