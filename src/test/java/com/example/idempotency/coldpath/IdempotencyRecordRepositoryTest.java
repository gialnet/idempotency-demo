package com.example.idempotency.coldpath;

import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.model.IdempotencyStatus;
import com.example.idempotency.repository.IdempotencyRecordRepository;
import com.example.idempotency.util.ContainersBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @DataJpaTest slice: only JPA layer, real PostgreSQL via Testcontainers.
 * No Redis, no web layer.
 */
@DataJpaTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("IdempotencyRecordRepository — JPA slice")
class IdempotencyRecordRepositoryTest extends ContainersBase {

    @Autowired IdempotencyRecordRepository repository;

    @Test
    @DisplayName("save and findByIdempotencyKey roundtrip")
    void saveAndFind_roundtrip() {
        var key = UUID.randomUUID().toString();
        var record = IdempotencyRecord.builder()
            .idempotencyKey(key)
            .status(IdempotencyStatus.PROCESSING)
            .responseStatus(0)
            .build();

        repository.save(record);
        var found = repository.findByIdempotencyKey(key);

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(IdempotencyStatus.PROCESSING);
    }

    @Test
    @DisplayName("markCompleted updates status and persists response body")
    void markCompleted_updatesRecord() {
        var key = UUID.randomUUID().toString();
        repository.save(IdempotencyRecord.builder()
            .idempotencyKey(key)
            .status(IdempotencyStatus.PROCESSING)
            .responseStatus(0)
            .build());

        int updated = repository.markCompleted(
            key, IdempotencyStatus.COMPLETED,
            """
            {"transactionId":"tx-99","status":"APPROVED"}
            """,
            201, Instant.now()
        );

        assertThat(updated).isEqualTo(1);
        var record = repository.findByIdempotencyKey(key).orElseThrow();
        assertThat(record.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(record.getResponseBody()).contains("tx-99");
        assertThat(record.getResponseStatus()).isEqualTo(201);
        assertThat(record.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("existsByIdempotencyKeyAndStatus returns false when status does not match")
    void existsByKeyAndStatus_mismatch() {
        var key = UUID.randomUUID().toString();
        repository.save(IdempotencyRecord.builder()
            .idempotencyKey(key)
            .status(IdempotencyStatus.PROCESSING)
            .responseStatus(0)
            .build());

        assertThat(repository.existsByIdempotencyKeyAndStatus(key, IdempotencyStatus.COMPLETED))
            .isFalse();
    }

    @Test
    @DisplayName("unique constraint prevents two records with the same idempotency key")
    void uniqueConstraint_preventsduplicates() {
        var key = UUID.randomUUID().toString();
        repository.save(IdempotencyRecord.builder()
            .idempotencyKey(key)
            .status(IdempotencyStatus.PROCESSING)
            .responseStatus(0)
            .build());

        var duplicate = IdempotencyRecord.builder()
            .idempotencyKey(key)
            .status(IdempotencyStatus.PROCESSING)
            .responseStatus(0)
            .build();

        org.junit.jupiter.api.Assertions.assertThrows(
            Exception.class, () -> {
                repository.saveAndFlush(duplicate);
            }
        );
    }
}
