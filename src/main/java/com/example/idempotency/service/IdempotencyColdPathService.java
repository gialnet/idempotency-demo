package com.example.idempotency.service;

import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.model.IdempotencyStatus;
import com.example.idempotency.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Cold-path: durable idempotency store in PostgreSQL.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Before executing business logic, call {@link #checkCompleted} — if a
 *       COMPLETED record exists, return the cached payload immediately.</li>
 *   <li>If not completed, call {@link #markCompleted} (or {@link #markFailed})
 *       after the business transaction finishes.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyColdPathService {

    private final IdempotencyRecordRepository repository;

    /**
     * Checks whether a COMPLETED record already exists for this key.
     *
     * @return the cached record if found, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> checkCompleted(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey)
            .filter(r -> r.getStatus() == IdempotencyStatus.COMPLETED);
    }

    /**
     * Persists a PROCESSING record as the request begins execution.
     * Idempotent: if a record already exists, the existing one is returned.
     */
    @Transactional
    public IdempotencyRecord createProcessingRecord(String idempotencyKey) {
        return repository.findByIdempotencyKey(idempotencyKey)
            .orElseGet(() -> {
                var record = IdempotencyRecord.builder()
                    .idempotencyKey(idempotencyKey)
                    .status(IdempotencyStatus.PROCESSING)
                    .responseStatus(0)
                    .build();
                return repository.save(record);
            });
    }

    /**
     * Transitions the record to COMPLETED and stores the serialised response payload.
     */
    @Transactional
    public void markCompleted(String idempotencyKey, int httpStatus, String responseBody) {
        int updated = repository.markCompleted(
            idempotencyKey,
            IdempotencyStatus.COMPLETED,
            responseBody,
            httpStatus,
            Instant.now()
        );
        log.info("Cold-path record marked COMPLETED key={} rows={}", idempotencyKey, updated);
    }

    /**
     * Transitions the record to FAILED (request may be retried by the client).
     */
    @Transactional
    public void markFailed(String idempotencyKey) {
        repository.findByIdempotencyKey(idempotencyKey).ifPresent(r -> {
            r.setStatus(IdempotencyStatus.FAILED);
            repository.save(r);
            log.warn("Cold-path record marked FAILED key={}", idempotencyKey);
        });
    }
}
