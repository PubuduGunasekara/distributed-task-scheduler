package com.taskscheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized configuration for {@code StaleTaskRecoveryScheduler}.
 *
 * Bound from application.yml:
 *   scheduler:
 *     recovery:
 *       poll-interval:     60s
 *       execution-timeout: 5m
 *       orphan-threshold:  2m
 *       batch-size:        100
 *
 * executionTimeout must exceed the Redis distributed lock TTL (see
 * RedisDistributedLock.LOCK_TTL, 30s). Otherwise the recovery scheduler
 * could fail a task that is still legitimately RUNNING and holding its
 * lock, racing the worker that is actively executing it.
 */
@ConfigurationProperties(prefix = "scheduler.recovery")
public record RecoveryProperties(
        Duration pollInterval,
        Duration executionTimeout,
        Duration orphanThreshold,
        int batchSize
) {
    /** Must stay in sync with RedisDistributedLock.LOCK_TTL. */
    static final Duration LOCK_TTL = Duration.ofSeconds(30);

    public RecoveryProperties {
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "scheduler.recovery.poll-interval must be positive, got: " + pollInterval);
        }
        if (executionTimeout == null || executionTimeout.compareTo(LOCK_TTL) <= 0) {
            throw new IllegalArgumentException(
                    "scheduler.recovery.execution-timeout must be greater than the Redis lock TTL (%s), got: %s"
                            .formatted(LOCK_TTL, executionTimeout));
        }
        if (orphanThreshold == null || orphanThreshold.isZero() || orphanThreshold.isNegative()) {
            throw new IllegalArgumentException(
                    "scheduler.recovery.orphan-threshold must be positive, got: " + orphanThreshold);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException(
                    "scheduler.recovery.batch-size must be > 0, got: " + batchSize);
        }
    }
}
