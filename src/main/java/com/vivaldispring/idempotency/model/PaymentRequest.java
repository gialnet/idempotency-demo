package com.vivaldispring.idempotency.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Demo business payload — represents a payment order.
 * Any real domain object would fit here equally well.
 */
public record PaymentRequest(
    @NotBlank String accountId,
    @Positive BigDecimal amount,
    @NotBlank String currency
) {}
