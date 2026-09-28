package com.taskscheduler.infrastructure.redis;

import com.taskscheduler.config.RateLimitProperties;
import com.taskscheduler.domain.port.RateLimitResult;
import com.taskscheduler.domain.port.RateLimiterPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fixed-window rate limiter backed by Redis.
 *
 * This is a fixed-window counter, not a token bucket: there's no
 * continuous refill. A counter is set to the limit at the start of a
 * window and decrements on each request; when the window's TTL expires,
 * the key disappears and the next request starts a brand-new window at
 * full capacity. See Known Limitations in the README for the resulting
 * boundary-burst tradeoff (a client can burst up to ~2x the limit across
 * a window boundary).
 *
 * The Lua script is the critical piece. It runs atomically on the Redis
 * server — the entire GET-check-DECR-or-SET sequence is uninterruptible.
 *
 * Script logic:
 *   GET the current counter for this client.
 *   If key doesn't exist (first request in this window, or the previous
 *   window's key just expired):
 *     → SET key = (limit - 1) with TTL = window
 *     → return (limit - 1)   [allowed, consumed 1 of limit for this window]
 *   If current count > 0:
 *     → DECR key
 *     → return remaining      [allowed]
 *   If current count == 0:
 *     → return -1             [denied, window exhausted]
 *
 * Return value contract:
 *   >= 0 → allowed, value is remaining count for this window
 *   -1   → denied
 *
 * Key format: "rate-limit:{clientId}"
 *   Visible in redis-cli: KEYS rate-limit:*  shows all active clients
 *   TTL visible via:       TTL rate-limit:127.0.0.1
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiterPort {

    static final String KEY_PREFIX = "rate-limit:";

    private static final DefaultRedisScript<Long> FIXED_WINDOW_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local key     = KEYS[1]
                    local limit   = tonumber(ARGV[1])
                    local window  = tonumber(ARGV[2])
                    local current = redis.call('GET', key)
                    if current == false then
                        redis.call('SET', key, limit - 1, 'EX', window)
                        return limit - 1
                    elseif tonumber(current) > 0 then
                        return redis.call('DECR', key)
                    else
                        return -1
                    end
                    """,
                    Long.class
            );

    private final StringRedisTemplate  redisTemplate;
    private final RateLimitProperties  properties;

    @Override
    public RateLimitResult tryConsume(String clientId) {
        String key    = KEY_PREFIX + clientId;
        Long   result = redisTemplate.execute(
                FIXED_WINDOW_SCRIPT,
                List.of(key),
                String.valueOf(properties.requestsPerWindow()),
                String.valueOf(properties.windowSeconds())
        );

        if (result == null || result < 0) {
            log.debug("Rate limit exceeded: clientId={}", clientId);
            return RateLimitResult.denied(
                    properties.requestsPerWindow(),
                    properties.windowSeconds()
            );
        }

        log.debug("Rate limit allowed: clientId={} remaining={}", clientId, result);
        return RateLimitResult.allowed(
                result.intValue(),
                properties.requestsPerWindow(),
                properties.windowSeconds()
        );
    }
}