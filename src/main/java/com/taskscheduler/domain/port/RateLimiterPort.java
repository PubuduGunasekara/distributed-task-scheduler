package com.taskscheduler.domain.port;

/**
 * Port for distributed rate limiting.
 *
 * Implementations must be atomic — concurrent requests from the same
 * client must not both see "allowed" when only one token remains.
 * The Redis implementation guarantees this via Lua scripting.
 */
public interface RateLimiterPort {

    /**
     * Attempt to consume one token from this client's bucket.
     *
     * @param clientId unique identifier per client (IP address, API key, etc.)
     * @return result indicating whether the request is allowed and tokens remaining
     */
    RateLimitResult tryConsume(String clientId);
}