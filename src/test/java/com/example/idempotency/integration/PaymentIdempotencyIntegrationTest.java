package com.example.idempotency.integration;

import com.example.idempotency.config.IdempotencyConstants;
import com.example.idempotency.model.IdempotencyStatus;
import com.example.idempotency.model.PaymentResponse;
import com.example.idempotency.repository.IdempotencyRecordRepository;
import com.example.idempotency.service.IdempotencyHotPathService;
import com.example.idempotency.util.ContainersBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.concurrent.TimeUnit;

/**
 * Full integration test suite for the idempotency check-and-set mechanism.
 *
 * <p>Uses real PostgreSQL + Redis via Testcontainers.  Each test gets a fresh
 * idempotency key, so tests are independent and parallelisable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("Idempotency integration tests")
class PaymentIdempotencyIntegrationTest extends ContainersBase {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired IdempotencyRecordRepository repository;
    @Autowired IdempotencyHotPathService hotPathService;

    private static final String PAYMENT_BODY = """
        {
          "accountId": "ACC-001",
          "amount": 100.00,
          "currency": "EUR"
        }
        """;

    // ────────────────────────────────────────────────────────────────────
    // HAPPY PATH
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("POST /payments returns 201 with a valid response body")
        void firstRequest_returns201() throws Exception {
            var key = UUID.randomUUID().toString();

            mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        }

        @Test
        @DisplayName("Cold-path record is persisted as COMPLETED after successful request")
        void firstRequest_persistsColdPathRecord() throws Exception {
            var key = UUID.randomUUID().toString();

            mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isCreated());

            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
                var record = repository.findByIdempotencyKey(key);
                assertThat(record).isPresent();
                assertThat(record.get().getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
                assertThat(record.get().getResponseStatus()).isEqualTo(201);
                assertThat(record.get().getResponseBody()).contains("APPROVED");
            });
        }

        @Test
        @DisplayName("Redis hot-path lock is released after request completes")
        void lockIsReleasedAfterCompletion() throws Exception {
            var key = UUID.randomUUID().toString();

            mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isCreated());

            await().atMost(2, TimeUnit.SECONDS)
                .until(() -> !hotPathService.isLocked(key));
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // COLD-PATH REPLAY
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Cold-path replay")
    class ColdPathReplay {

        @Test
        @DisplayName("Second identical request returns same transactionId (cached cold-path)")
        void retryReturns_sameTransactionId() throws Exception {
            var key = UUID.randomUUID().toString();

            // First request
            MvcResult first = mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isCreated())
                .andReturn();

            PaymentResponse firstResp = objectMapper.readValue(
                first.getResponse().getContentAsString(), PaymentResponse.class);

            // Second request with the same idempotency key
            MvcResult second = mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andReturn();

            PaymentResponse secondResp = objectMapper.readValue(
                second.getResponse().getContentAsString(), PaymentResponse.class);

            assertThat(secondResp.transactionId()).isEqualTo(firstResp.transactionId());
        }

        @Test
        @DisplayName("Replay response includes Idempotency-Replayed: true header")
        void replay_setsReplayedHeader() throws Exception {
            var key = UUID.randomUUID().toString();

            // Seed a completed record
            mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isCreated());

            mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(header().string("Idempotency-Replayed", "true"));
        }

        @Test
        @DisplayName("Cold-path does not increment DB row count on replay")
        void replay_doesNotCreateNewRecord() throws Exception {
            var key = UUID.randomUUID().toString();

            mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isCreated());

            long countBefore = repository.count();

            mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isCreated());

            assertThat(repository.count()).isEqualTo(countBefore);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // HOT-PATH CONCURRENCY
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Hot-path concurrency")
    class HotPathConcurrency {

        @Test
        @DisplayName("Exactly one of two simultaneous requests succeeds; the other gets 409")
        void concurrentDuplicates_onlyOneSucceeds() throws Exception {
            var key = UUID.randomUUID().toString();
            int threads = 2;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch  = new CountDownLatch(threads);
            AtomicInteger successCount  = new AtomicInteger();
            AtomicInteger conflictCount = new AtomicInteger();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        var result = mockMvc.perform(post("/payments")
                                .header(IdempotencyConstants.HEADER, key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PAYMENT_BODY))
                            .andReturn();
                        int status = result.getResponse().getStatus();
                        if (status == 201) successCount.incrementAndGet();
                        if (status == 409) conflictCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("409 response body contains Problem Detail with idempotency key")
        void conflictResponse_hasProblemDetail() throws Exception {
            var key = UUID.randomUUID().toString();
            // Manually hold the lock to force a 409
            hotPathService.tryAcquireLock(key);

            try {
                mockMvc.perform(post("/payments")
                        .header(IdempotencyConstants.HEADER, key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PAYMENT_BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.idempotencyKey").value(key));
            } finally {
                hotPathService.releaseLock(key);
            }
        }

        @Test
        @DisplayName("Five concurrent requests with the same key: exactly one succeeds")
        void fiveConcurrentRequests_onlyOneSucceeds() throws Exception {
            var key = UUID.randomUUID().toString();
            int threads = 5;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch  = new CountDownLatch(threads);
            AtomicInteger successCount = new AtomicInteger();

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startLatch.await();
                        var result = mockMvc.perform(post("/payments")
                                .header(IdempotencyConstants.HEADER, key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(PAYMENT_BODY))
                            .andReturn();
                        if (result.getResponse().getStatus() == 201) successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(15, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            assertThat(successCount.get()).isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // EDGE CASES
    // ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Missing Idempotency-Key header returns 400")
        void missingHeader_returns400() throws Exception {
            mockMvc.perform(post("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Invalid request body returns 400")
        void invalidBody_returns400() throws Exception {
            mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        { "accountId": "", "amount": -50, "currency": "EUR" }
                        """))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Different keys produce independent responses")
        void differentKeys_areIndependent() throws Exception {
            var key1 = UUID.randomUUID().toString();
            var key2 = UUID.randomUUID().toString();

            MvcResult r1 = mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key1)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isCreated())
                .andReturn();

            MvcResult r2 = mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key2)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isCreated())
                .andReturn();

            var resp1 = objectMapper.readValue(r1.getResponse().getContentAsString(), PaymentResponse.class);
            var resp2 = objectMapper.readValue(r2.getResponse().getContentAsString(), PaymentResponse.class);

            assertThat(resp1.transactionId()).isNotEqualTo(resp2.transactionId());
        }

        @Test
        @DisplayName("Hot-path lock does not remain after successful request")
        void lockClearedAfterSuccess() throws Exception {
            var key = UUID.randomUUID().toString();

            mockMvc.perform(post("/payments")
                    .header(IdempotencyConstants.HEADER, key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(PAYMENT_BODY))
                .andExpect(status().isCreated());

            assertThat(hotPathService.isLocked(key)).isFalse();
        }
    }
}
