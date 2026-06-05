package com.example.idempotency.controller;

import com.example.idempotency.config.IdempotencyConstants;
import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.model.PaymentRequest;
import com.example.idempotency.model.PaymentResponse;
import com.example.idempotency.service.IdempotencyColdPathService;
import com.example.idempotency.service.IdempotencyHotPathService;
import com.example.idempotency.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Entry point for idempotent payment submissions.
 *
 * <p>Full check-and-set lifecycle per request:
 * <ol>
 *   <li><b>Hot-path</b>: acquire Redis lock (SET NX EX 30). Reject concurrent
 *       duplicates with 409.</li>
 *   <li><b>Cold-path check</b>: if a COMPLETED record exists in PostgreSQL,
 *       replay cached response without touching business logic.</li>
 *   <li><b>Execute</b>: run {@link PaymentService#process}, serialise result.</li>
 *   <li><b>Persist</b>: mark the cold-path record COMPLETED.</li>
 *   <li><b>Release</b>: delete the Redis hot-path lock.</li>
 * </ol>
 */
@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final IdempotencyHotPathService hotPath;
    private final IdempotencyColdPathService coldPath;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @PostMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<PaymentResponse> createPayment(
        @RequestHeader(IdempotencyConstants.HEADER) String idempotencyKey,
        @Valid @RequestBody PaymentRequest request
    ) throws Exception {

        // ── 1. HOT-PATH: atomic Redis lock ────────────────────────────
        hotPath.tryAcquireLock(idempotencyKey); // throws DuplicateInFlightException on collision

        try {
            // ── 2. COLD-PATH: replay if already completed ─────────────
            Optional<IdempotencyRecord> existing = coldPath.checkCompleted(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Cold-path cache hit for key={}", idempotencyKey);
                PaymentResponse cached = objectMapper.readValue(
                    existing.get().getResponseBody(), PaymentResponse.class
                );
                return ResponseEntity
                    .status(existing.get().getResponseStatus())
                    .header("Idempotency-Replayed", "true")
                    .body(cached);
            }

            // ── 3. EXECUTE business logic ─────────────────────────────
            coldPath.createProcessingRecord(idempotencyKey);
            PaymentResponse result = paymentService.process(request);

            // ── 4. PERSIST completed record ───────────────────────────
            String body = objectMapper.writeValueAsString(result);
            coldPath.markCompleted(idempotencyKey, HttpStatus.CREATED.value(), body);

            return ResponseEntity.status(HttpStatus.CREATED).body(result);

        } catch (Exception ex) {
            // On any failure, mark the record as FAILED so client may retry
            coldPath.markFailed(idempotencyKey);
            throw ex;
        } finally {
            // ── 5. RELEASE Redis lock ─────────────────────────────────
            hotPath.releaseLock(idempotencyKey);
        }
    }
}
