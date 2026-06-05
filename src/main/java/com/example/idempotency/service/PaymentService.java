package com.example.idempotency.service;

import com.example.idempotency.model.PaymentRequest;
import com.example.idempotency.model.PaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Stub business logic — represents an expensive, side-effecting operation
 * (e.g. debiting an account, calling a payment gateway).
 *
 * <p>In production this would call downstream APIs, write to the DB, etc.
 * Tests can use {@code @MockBean} to control its behaviour.
 */
@Slf4j
@Service
public class PaymentService {

    public PaymentResponse process(PaymentRequest request) {
        log.info("Processing payment accountId={} amount={}", request.accountId(), request.amount());
        // Simulate work — in real life: call payment gateway, write ledger entry, etc.
        return new PaymentResponse(
            UUID.randomUUID().toString(),
            request.accountId(),
            request.amount(),
            request.currency(),
            "APPROVED",
            Instant.now()
        );
    }
}
