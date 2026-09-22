package com.taskscheduler.infrastructure.redis;

import com.taskscheduler.config.RateLimitProperties;
import com.taskscheduler.domain.port.RateLimitResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests running RedisRateLimiter's token-bucket Lua script
 * against a real Redis via Testcontainers. The script's atomicity (the
 * whole GET-check-DECR-or-SET sequence as one uninterruptible operation)
 * can't be verified against a mocked StringRedisTemplate.
 *
 * Wires a StringRedisTemplate directly (no Spring context), same as
 * RedisDistributedLockIntegrationTest, to keep this fast.
 */
@Testcontainers
@DisplayName("RedisRateLimiter (real Redis)")
class RedisRateLimiterIntegrationTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void startRedisTemplate() {
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
    }

    @BeforeEach
    void setUp() {
        flushRedis();
    }

    @Test
    @DisplayName("should allow requests up to the configured burst")
    void shouldAllowUpToConfiguredBurst() {
        RedisRateLimiter rateLimiter = rateLimiter(3, 60);
        String clientId = clientId();

        assertThat(rateLimiter.tryConsume(clientId).allowed()).isTrue();
        assertThat(rateLimiter.tryConsume(clientId).allowed()).isTrue();
        assertThat(rateLimiter.tryConsume(clientId).allowed()).isTrue();
    }

    @Test
    @DisplayName("should reject once the burst is exhausted")
    void shouldRejectAfterBurstExhausted() {
        RedisRateLimiter rateLimiter = rateLimiter(2, 60);
        String clientId = clientId();

        rateLimiter.tryConsume(clientId);
        rateLimiter.tryConsume(clientId);

        assertThat(rateLimiter.tryConsume(clientId).allowed()).isFalse();
    }

    @Test
    @DisplayName("should track separate clients independently")
    void shouldTrackClientsIndependently() {
        RedisRateLimiter rateLimiter = rateLimiter(1, 60);
        String clientA = clientId();
        String clientB = clientId();

        assertThat(rateLimiter.tryConsume(clientA).allowed()).isTrue();
        assertThat(rateLimiter.tryConsume(clientB).allowed()).isTrue();
        assertThat(rateLimiter.tryConsume(clientA).allowed()).isFalse();
    }

    @Test
    @DisplayName("should report the remaining token count decreasing as tokens are consumed")
    void shouldReportDecreasingRemainingCount() {
        RedisRateLimiter rateLimiter = rateLimiter(3, 60);
        String clientId = clientId();

        assertThat(rateLimiter.tryConsume(clientId).remaining()).isEqualTo(2);
        assertThat(rateLimiter.tryConsume(clientId).remaining()).isEqualTo(1);
        assertThat(rateLimiter.tryConsume(clientId).remaining()).isEqualTo(0);
    }

    @Test
    @DisplayName("should report limit and windowSeconds on a denied result")
    void shouldReportLimitAndWindowOnDenial() {
        RedisRateLimiter rateLimiter = rateLimiter(1, 45);
        String clientId = clientId();
        rateLimiter.tryConsume(clientId);

        RateLimitResult result = rateLimiter.tryConsume(clientId);

        assertThat(result.allowed()).isFalse();
        assertThat(result.limit()).isEqualTo(1);
        assertThat(result.windowSeconds()).isEqualTo(45);
        assertThat(result.remaining()).isZero();
    }

    private RedisRateLimiter rateLimiter(int requestsPerWindow, int windowSeconds) {
        return new RedisRateLimiter(
                redisTemplate,
                new RateLimitProperties(true, requestsPerWindow, windowSeconds));
    }

    private String clientId() {
        return "client-" + UUID.randomUUID();
    }

    private void flushRedis() {
        RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
        try {
            connection.serverCommands().flushDb();
        } finally {
            connection.close();
        }
    }
}
