package com.taskscheduler.infrastructure.redis;

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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests running RedisDistributedLock against a real Redis via
 * Testcontainers, instead of the mocked StringRedisTemplate unit tests use
 * elsewhere. The Lua compare-and-delete release script and SETNX+TTL
 * acquire semantics only mean something against a real server — a mock
 * can't catch a broken Lua script or a wrong TTL argument.
 *
 * Wires a StringRedisTemplate directly (no Spring context) to keep this
 * fast: this class only needs a connection factory, not the full
 * application (Postgres, Kafka, web layer, etc.).
 */
@Testcontainers
@DisplayName("RedisDistributedLock (real Redis)")
class RedisDistributedLockIntegrationTest {

    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static StringRedisTemplate redisTemplate;

    private RedisDistributedLock lock;

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
        lock = new RedisDistributedLock(redisTemplate);
        flushRedis();
    }

    @Test
    @DisplayName("should acquire the lock when no one holds it")
    void shouldAcquireWhenFree() {
        assertThat(lock.acquireLock(taskId(), "worker-1")).isTrue();
    }

    @Test
    @DisplayName("should reject a second acquire for the same task while held")
    void shouldRejectSecondAcquireWhileHeld() {
        UUID taskId = taskId();

        assertThat(lock.acquireLock(taskId, "worker-1")).isTrue();
        assertThat(lock.acquireLock(taskId, "worker-2")).isFalse();
    }

    @Test
    @DisplayName("should set a TTL of about 30s on acquire")
    void shouldSetTtlOnAcquire() {
        UUID taskId = taskId();
        lock.acquireLock(taskId, "worker-1");

        Long ttl = redisTemplate.getExpire(RedisDistributedLock.LOCK_PREFIX + taskId, TimeUnit.SECONDS);

        assertThat(ttl).isNotNull();
        assertThat(ttl).isBetween(25L, 30L);
    }

    @Test
    @DisplayName("should NOT delete the key when a non-owner releases it")
    void shouldNotReleaseWhenNotOwner() {
        UUID taskId = taskId();
        lock.acquireLock(taskId, "worker-1");

        lock.releaseLock(taskId, "worker-2");

        assertThat(redisTemplate.hasKey(RedisDistributedLock.LOCK_PREFIX + taskId)).isTrue();
    }

    @Test
    @DisplayName("should delete the key when the owner releases it")
    void shouldReleaseWhenOwner() {
        UUID taskId = taskId();
        lock.acquireLock(taskId, "worker-1");

        lock.releaseLock(taskId, "worker-1");

        assertThat(redisTemplate.hasKey(RedisDistributedLock.LOCK_PREFIX + taskId)).isFalse();
    }

    @Test
    @DisplayName("should let another owner acquire once the key is gone (TTL-expiry equivalent)")
    void shouldAllowAcquireOnceKeyIsGone() {
        UUID taskId = taskId();
        lock.acquireLock(taskId, "worker-1");

        // Deletes the key directly instead of waiting out the real 30s TTL.
        // Redis's own expiry mechanism isn't ours to test; what matters here
        // is that acquireLock() succeeds again once the key is gone, which
        // is exactly what happens on natural TTL expiry too.
        redisTemplate.delete(RedisDistributedLock.LOCK_PREFIX + taskId);

        assertThat(lock.acquireLock(taskId, "worker-2")).isTrue();
    }

    private UUID taskId() {
        return UUID.randomUUID();
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
