package com.example.idempotency.repository;

import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.model.IdempotencyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String key);

    boolean existsByIdempotencyKeyAndStatus(String key, IdempotencyStatus status);

    @Modifying
    @Query("""
        UPDATE IdempotencyRecord r
           SET r.status       = :status,
               r.responseBody = :body,
               r.responseStatus = :httpStatus,
               r.completedAt  = :now
         WHERE r.idempotencyKey = :key
        """)
    int markCompleted(
        @Param("key")        String key,
        @Param("status")     IdempotencyStatus status,
        @Param("body")       String body,
        @Param("httpStatus") int httpStatus,
        @Param("now")        Instant now
    );
}
