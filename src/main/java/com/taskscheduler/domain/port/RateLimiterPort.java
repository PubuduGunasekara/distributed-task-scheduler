package com.taskscheduler.domain.port;

/**
 * Port for distributed rate limiting.
 *
 * Implementations must be atomic — concurrent requests from the same
 * client must not both see "allowed" when only one request remains in
 * the current window. The Redis implementation guarantees this via Lua
 * scripting.
 */
public interface RateLimiterPort {

    /**
     * Attempt to consume one unit of this client's rate limit allowance.
     *
     * @param clientId unique identifier per client (IP address, API key, etc.)
     * @return result indicating whether the request is allowed and how much remains
     */
    RateLimitResult tryConsume(String clientId);
}