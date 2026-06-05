package com.example.idempotency.hotpath;

import com.example.idempotency.config.IdempotencyConstants;
import com.example.idempotency.exception.DuplicateInFlightException;
import com.example.idempotency.service.IdempotencyHotPathService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the Redis hot-path lock mechanism.
 *
 * Tests are pure Mockito — no container required, sub-millisecond execution.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Hot-path: Redis SET NX EX lock")
class IdempotencyHotPathServiceTest {

    @Mock  StringRedisTemplate redis;
    @Mock  ValueOperations<String, String> valueOps;
    @InjectMocks IdempotencyHotPathService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Nested
    @DisplayName("tryAcquireLock")
    class TryAcquireLock {

        @Test
        @DisplayName("returns true when SET NX EX succeeds (first request)")
        void acquiresLock_whenKeyDoesNotExist() {
            var key = UUID.randomUUID().toString();
            when(valueOps.setIfAbsent(eq(key), eq(IdempotencyConstants.IN_FLIGHT_VALUE),
                any(Duration.class))).thenReturn(true);

            boolean result = service.tryAcquireLock(key);

            assertThat(result).isTrue();
            verify(valueOps).setIfAbsent(key, IdempotencyConstants.IN_FLIGHT_VALUE,
                Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("throws DuplicateInFlightException when key already exists (concurrent duplicate)")
        void throwsDuplicate_whenKeyAlreadyExists() {
            var key = UUID.randomUUID().toString();
            when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(false);
            when(valueOps.get(key)).thenReturn(IdempotencyConstants.IN_FLIGHT_VALUE);

            assertThatThrownBy(() -> service.tryAcquireLock(key))
                .isInstanceOf(DuplicateInFlightException.class)
                .hasMessageContaining(key)
                .extracting(e -> ((DuplicateInFlightException) e).getIdempotencyKey())
                .isEqualTo(key);
        }

        @Test
        @DisplayName("uses 30-second TTL as defined in constants")
        void usesTtlFromConstants() {
            var key = UUID.randomUUID().toString();
            when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(true);

            service.tryAcquireLock(key);

            verify(valueOps).setIfAbsent(
                eq(key), anyString(),
                eq(Duration.ofSeconds(IdempotencyConstants.LOCK_TTL_SECONDS))
            );
        }

        @Test
        @DisplayName("stores IN_FLIGHT_VALUE as the lock value")
        void storesInFlightValue() {
            var key = UUID.randomUUID().toString();
            when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(true);

            service.tryAcquireLock(key);

            verify(valueOps).setIfAbsent(
                eq(key),
                eq(IdempotencyConstants.IN_FLIGHT_VALUE),
                any()
            );
        }

        @Test
        @DisplayName("treats null return from Redis setIfAbsent as lock failure")
        void treatsNullReturnAsFailure() {
            var key = UUID.randomUUID().toString();
            when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(null);
            when(valueOps.get(key)).thenReturn(null);

            // null is treated as Boolean.FALSE by the service
            assertThatThrownBy(() -> service.tryAcquireLock(key))
                .isInstanceOf(DuplicateInFlightException.class);
        }
    }

    @Nested
    @DisplayName("releaseLock")
    class ReleaseLock {

        @Test
        @DisplayName("deletes the Redis key on release")
        void deletesKey() {
            var key = UUID.randomUUID().toString();
            service.releaseLock(key);
            verify(redis).delete(key);
        }

        @Test
        @DisplayName("release is idempotent — does not throw if key absent")
        void releaseIsIdempotent() {
            var key = UUID.randomUUID().toString();
            when(redis.delete(key)).thenReturn(false); // key not found
            assertThatCode(() -> service.releaseLock(key)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("isLocked / getLockValue")
    class Introspection {

        @Test
        @DisplayName("isLocked returns true when Redis key exists")
        void isLocked_whenKeyPresent() {
            var key = UUID.randomUUID().toString();
            when(redis.hasKey(key)).thenReturn(true);
            assertThat(service.isLocked(key)).isTrue();
        }

        @Test
        @DisplayName("isLocked returns false when Redis key absent")
        void isNotLocked_whenKeyAbsent() {
            var key = UUID.randomUUID().toString();
            when(redis.hasKey(key)).thenReturn(false);
            assertThat(service.isLocked(key)).isFalse();
        }

        @Test
        @DisplayName("getLockValue returns stored value from Redis")
        void getLockValue_returnsStoredValue() {
            var key = UUID.randomUUID().toString();
            when(valueOps.get(key)).thenReturn("IN_FLIGHT");
            assertThat(service.getLockValue(key)).isEqualTo("IN_FLIGHT");
        }
    }
}
