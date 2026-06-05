package com.example.idempotency.util;

import com.redis.testcontainers.RedisContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers base class.
 *
 * Starts one PostgreSQL and one Redis container per JVM process
 * (static = singleton lifecycle), reused across all test classes.
 */
public abstract class ContainersBase {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("idempotency_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    protected static final RedisContainer REDIS =
        new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        REDIS.start();

        registry.add("spring.datasource.url",           POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",      POSTGRES::getUsername);
        registry.add("spring.datasource.password",      POSTGRES::getPassword);
        registry.add("spring.data.redis.host",          REDIS::getHost);
        registry.add("spring.data.redis.port",          () -> REDIS.getMappedPort(6379));
        registry.add("spring.flyway.enabled",           () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto",   () -> "validate");
    }
}
