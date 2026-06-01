package com.taskscheduler.domain.port;

/**
 * Result of a single rate limit token consumption attempt.
 *
 * Carries everything the interceptor needs to:
 *   - decide whether to allow or reject the request
 *   - set the correct X-RateLimit-* response headers
 *   - tell the client when to retry via Retry-After
 */
public record RateLimitResult(
        boolean allowed,
        int     remaining,
        int     limit,
        int     windowSeconds
) {
    public static RateLimitResult allowed(int remaining, int limit, int windowSeconds) {
        return new RateLimitResult(true, remaining, limit, windowSeconds);
    }

    public static RateLimitResult denied(int limit, int windowSeconds) {
        return new RateLimitResult(false, 0, limit, windowSeconds);
    }
}