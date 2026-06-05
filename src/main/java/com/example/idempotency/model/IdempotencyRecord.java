package com.example.idempotency.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Persistent record of a completed idempotent request (cold-path store).
 *
 * <p>Once a request transitions to {@code COMPLETED}, this row is returned
 * verbatim on any future retry, bypassing business logic entirely.
 */
@Entity
@Table(
    name = "idempotency_records",
    indexes = @Index(name = "idx_idempotency_key", columnList = "idempotency_key", unique = true)
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 255)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IdempotencyStatus status;

    /** HTTP status code of the original response. */
    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    /** Serialised JSON body of the original response. */
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
