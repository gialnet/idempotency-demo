package com.vivaldispring.idempotency.service;

import com.vivaldispring.idempotency.config.IdempotencyConstants;
import com.vivaldispring.idempotency.exception.DuplicateInFlightException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Hot-path: atomic Redis SET NX EX to prevent concurrent duplicates.
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #tryAcquireLock} → returns {@code true} if the lock was acquired
 *       (first request), throws {@link DuplicateInFlightException} if another
 *       thread/node already holds it.</li>
 *   <li>{@link #releaseLock} → removes the lock key after business logic
 *       completes (success OR failure). The cold-path record in PostgreSQL
 *       then owns deduplication going forward.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyHotPathService {

    private final StringRedisTemplate redis;

    /**
     * Attempts an atomic SET key IN_FLIGHT NX EX 30.
     *
     * @return {@code true} if the lock was newly acquired
     * @throws DuplicateInFlightException if the key already exists (in-flight duplicate)
     */
    public boolean tryAcquireLock(String idempotencyKey) {
        Boolean acquired = redis.opsForValue().setIfAbsent(
            idempotencyKey,
            IdempotencyConstants.IN_FLIGHT_VALUE,
            Duration.ofSeconds(IdempotencyConstants.LOCK_TTL_SECONDS)
        );

        if (Boolean.TRUE.equals(acquired)) {
            log.debug("Hot-path lock acquired for key={}", idempotencyKey);
            return true;
        }

        String currentValue = redis.opsForValue().get(idempotencyKey);
        log.warn("Hot-path duplicate detected key={} currentValue={}", idempotencyKey, currentValue);
        throw new DuplicateInFlightException(idempotencyKey);
    }

    /**
     * Releases the Redis lock.  Safe to call even if the key no longer exists.
     */
    public void releaseLock(String idempotencyKey) {
        redis.delete(idempotencyKey);
        log.debug("Hot-path lock released for key={}", idempotencyKey);
    }

    /**
     * Returns {@code true} if the key currently exists in Redis (still in-flight).
     * Used in tests to assert lock state.
     */
    public boolean isLocked(String idempotencyKey) {
        return Boolean.TRUE.equals(redis.hasKey(idempotencyKey));
    }

    /**
     * Returns the raw value stored for the key (IN_FLIGHT or null).
     */
    public String getLockValue(String idempotencyKey) {
        return redis.opsForValue().get(idempotencyKey);
    }
}
