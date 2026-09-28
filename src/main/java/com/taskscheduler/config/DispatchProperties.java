package com.taskscheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized configuration for {@code DueTaskDispatchScheduler}.
 *
 * Bound from application.yml:
 *   scheduler:
 *     dispatch:
 *       poll-interval: 5s
 *       batch-size:    100
 *
 * Poll interval is short (default 5s, vs. recovery's 60s) because this is
 * the primary dispatch path for future-scheduled tasks — operators expect
 * a task to run close to its scheduledAt, not up to a minute late.
 */
@ConfigurationProperties(prefix = "scheduler.dispatch")
public record DispatchProperties(
        Duration pollInterval,
        int batchSize
) {
    public DispatchProperties {
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "scheduler.dispatch.poll-interval must be positive, got: " + pollInterval);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "scheduler.dispatch.batch-size must be > 0, got: " + batchSize);
        }
    }
}
