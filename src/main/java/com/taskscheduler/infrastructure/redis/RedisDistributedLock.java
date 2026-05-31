package com.taskscheduler.infrastructure.redis;

import com.taskscheduler.domain.port.DistributedLockPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Redis-backed distributed lock using SETNX + TTL.
 *
 * Key design decisions:
 *
 * 1. SETNX (SET if Not eXists) with EX (expiry) in one atomic command.
 *    setIfAbsent(key, value, ttl) maps to: SET key value NX EX ttl
 *    This is atomic — no separate SET and EXPIRE calls that could be
 *    interrupted by a crash between them.
 *
 * 2. TTL = 30 seconds.
 *    If a worker crashes while holding the lock, the lock auto-expires.
 *    Choose TTL > max expected task execution time to avoid premature expiry.
 *    In production, TTL is tuned per task type.
 *
 * 3. Lua script for release (compare-and-delete).
 *    Naive approach: GET → check owner → DEL.
 *    Race condition: another worker acquires between GET and DEL,
 *    and we delete THEIR lock. The Lua script runs atomically on
 *    the Redis server — no interleaving possible.
 *
 * 4. Lock key includes taskId for namespacing.
 *    Pattern: "task-lock:{uuid}"
 *    Visible in Redis CLI: KEYS task-lock:* shows all held locks.
 *    Useful for debugging stuck tasks in production.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDistributedLock implements DistributedLockPort {

    static final String   LOCK_PREFIX = "task-lock:";
    static final Duration LOCK_TTL    = Duration.ofSeconds(30);

    /**
     * Atomically: if GET(key) == ownerId then DEL(key) return 1
     *             else return 0
     *
     * KEYS[1] = lock key, ARGV[1] = ownerId
     */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    else
                        return 0
                    end
                    """,
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean acquireLock(UUID taskId, String ownerId) {
        String  key      = LOCK_PREFIX + taskId;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, ownerId, LOCK_TTL);

        boolean result = Boolean.TRUE.equals(acquired);
        if (result) {
            log.debug("Lock acquired: taskId={} owner={}", taskId, ownerId);
        } else {
            log.debug("Lock unavailable: taskId={}", taskId);
        }
        return result;
    }

    @Override
    public void releaseLock(UUID taskId, String ownerId) {
        String key    = LOCK_PREFIX + taskId;
        Long   result = redisTemplate.execute(
                RELEASE_SCRIPT,
                List.of(key),
                ownerId
        );

        if (Long.valueOf(1L).equals(result)) {
            log.debug("Lock released: taskId={} owner={}", taskId, ownerId);
        } else {
            log.warn("Lock not released — not owner or already expired: taskId={} owner={}",
                    taskId, ownerId);
        }
    }
}