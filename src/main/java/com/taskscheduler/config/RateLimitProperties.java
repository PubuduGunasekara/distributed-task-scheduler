package com.taskscheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized rate limit configuration.
 *
 * Bound from application.yml:
 *   rate-limit:
 *     enabled: true
 *     requests-per-window: 10
 *     window-seconds: 60
 *
 * Why a record?
 *   Rate limit config is immutable after startup. Records enforce this.
 *   Spring Boot 3.x binds @ConfigurationProperties on records natively.
 *
 * Changing limits requires only application.yml — zero code changes.
 * In production, these are environment variables, not YAML values.
 */
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        boolean enabled,
        int     requestsPerWindow,
        int     windowSeconds
) {
    public RateLimitProperties {
        if (requestsPerWindow <= 0) throw new IllegalArgumentException(
                "rate-limit.requests-per-window must be > 0, got: " + requestsPerWindow);
        if (windowSeconds <= 0) throw new IllegalArgumentException(
                "rate-limit.window-seconds must be > 0, got: " + windowSeconds);
    }
}