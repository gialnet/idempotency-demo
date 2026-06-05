package com.example.idempotency.coldpath;

import com.example.idempotency.model.IdempotencyRecord;
import com.example.idempotency.model.IdempotencyStatus;
import com.example.idempotency.repository.IdempotencyRecordRepository;
import com.example.idempotency.service.IdempotencyColdPathService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the PostgreSQL cold-path service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Cold-path: PostgreSQL idempotency store")
class IdempotencyColdPathServiceTest {

    @Mock IdempotencyRecordRepository repository;
    @InjectMocks IdempotencyColdPathService service;

    private static IdempotencyRecord completedRecord(String key) {
        return IdempotencyRecord.builder()
            .idempotencyKey(key)
            .status(IdempotencyStatus.COMPLETED)
            .responseStatus(201)
            .responseBody("""
                {"transactionId":"tx-123","status":"APPROVED"}
                """)
            .createdAt(Instant.now())
            .completedAt(Instant.now())
            .build();
    }

    private static IdempotencyRecord processingRecord(String key) {
        return IdempotencyRecord.builder()
            .idempotencyKey(key)
            .status(IdempotencyStatus.PROCESSING)
            .responseStatus(0)
            .createdAt(Instant.now())
            .build();
    }

    @Nested
    @DisplayName("checkCompleted")
    class CheckCompleted {

        @Test
        @DisplayName("returns completed record when it exists in the database")
        void returnsRecord_whenCompletedExists() {
            var key = UUID.randomUUID().toString();
            when(repository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(completedRecord(key)));

            var result = service.checkCompleted(key);

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        }

        @Test
        @DisplayName("returns empty when no record exists (first request)")
        void returnsEmpty_whenNoRecord() {
            var key = UUID.randomUUID().toString();
            when(repository.findByIdempotencyKey(key)).thenReturn(Optional.empty());

            assertThat(service.checkCompleted(key)).isEmpty();
        }

        @Test
        @DisplayName("returns empty when record exists but is still PROCESSING (not yet completed)")
        void returnsEmpty_whenRecordIsProcessing() {
            var key = UUID.randomUUID().toString();
            when(repository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(processingRecord(key)));

            // PROCESSING != COMPLETED, so cold-path does not short-circuit
            assertThat(service.checkCompleted(key)).isEmpty();
        }

        @Test
        @DisplayName("returns empty when record is in FAILED state")
        void returnsEmpty_whenRecordIsFailed() {
            var key = UUID.randomUUID().toString();
            var failed = IdempotencyRecord.builder()
                .idempotencyKey(key)
                .status(IdempotencyStatus.FAILED)
                .responseStatus(500)
                .createdAt(Instant.now())
                .build();
            when(repository.findByIdempotencyKey(key)).thenReturn(Optional.of(failed));

            assertThat(service.checkCompleted(key)).isEmpty();
        }
    }

    @Nested
    @DisplayName("createProcessingRecord")
    class CreateProcessingRecord {

        @Test
        @DisplayName("saves a new PROCESSING record when none exists")
        void savesNewRecord() {
            var key = UUID.randomUUID().toString();
            when(repository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            var saved = processingRecord(key);
            when(repository.save(any())).thenReturn(saved);

            var result = service.createProcessingRecord(key);

            assertThat(result.getStatus()).isEqualTo(IdempotencyStatus.PROCESSING);
            var captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getIdempotencyKey()).isEqualTo(key);
            assertThat(captor.getValue().getStatus()).isEqualTo(IdempotencyStatus.PROCESSING);
        }

        @Test
        @DisplayName("returns existing record without saving when one already exists")
        void returnsExisting_withoutSave() {
            var key = UUID.randomUUID().toString();
            var existing = processingRecord(key);
            when(repository.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

            var result = service.createProcessingRecord(key);

            assertThat(result).isSameAs(existing);
            verify(repository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("markCompleted")
    class MarkCompleted {

        @Test
        @DisplayName("invokes repository update with COMPLETED status and payload")
        void callsRepositoryUpdate() {
            var key = UUID.randomUUID().toString();
            when(repository.markCompleted(any(), any(), any(), anyInt(), any())).thenReturn(1);

            service.markCompleted(key, 201, """
                {"transactionId":"tx-abc"}
                """);

            verify(repository).markCompleted(
                eq(key),
                eq(IdempotencyStatus.COMPLETED),
                contains("tx-abc"),
                eq(201),
                any(Instant.class)
            );
        }
    }

    @Nested
    @DisplayName("markFailed")
    class MarkFailed {

        @Test
        @DisplayName("updates existing record to FAILED status")
        void updatesRecordToFailed() {
            var key = UUID.randomUUID().toString();
            var record = processingRecord(key);
            when(repository.findByIdempotencyKey(key)).thenReturn(Optional.of(record));

            service.markFailed(key);

            var captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
            verify(repository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(IdempotencyStatus.FAILED);
        }

        @Test
        @DisplayName("is a no-op when no record exists for the key")
        void isNoOp_whenNoRecord() {
            var key = UUID.randomUUID().toString();
            when(repository.findByIdempotencyKey(key)).thenReturn(Optional.empty());

            service.markFailed(key);

            verify(repository, never()).save(any());
        }
    }
}
