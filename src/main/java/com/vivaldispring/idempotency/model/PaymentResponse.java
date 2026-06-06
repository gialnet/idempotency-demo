package com.vivaldispring.idempotency.model;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
    String transactionId,
    String accountId,
    BigDecimal amount,
    String currency,
    String status,
    Instant processedAt
) {}
