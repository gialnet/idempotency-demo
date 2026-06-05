package com.example.idempotency.model;

public enum IdempotencyStatus {
    /** Request is being processed — persisted to DB before execution starts. */
    PROCESSING,
    /** Request finished successfully — safe to replay the cached response. */
    COMPLETED,
    /** Request failed — may be retried. */
    FAILED
}
