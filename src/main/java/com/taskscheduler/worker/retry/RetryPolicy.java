package com.taskscheduler.worker.retry;

import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * Stateless utility encapsulating retry backoff logic.
 *
 * Exponential backoff formula: delay = initialDelay × multiplier^(retryCount - 1)
 *
 * retryCount=1 → 10  × 3^0 =  10 seconds
 * retryCount=2 → 10  × 3^1 =  30 seconds
 * retryCount=3 → 10  × 3^2 =  90 seconds
 *
 * Why exponential and not fixed?
 *   Fixed-delay retry hammers failing downstream services with constant load.
 *   Exponential backoff gives transient failures time to resolve (network blip,
 *   DB restart) and prevents retry storms. Industry standard from AWS to GCP.
 *
 * Why a separate class?
 *   Business rules about retry timing belong in one testable place, not scattered
 *   across the scheduler, consumer, and service. Changing the formula means
 *   changing one class and one test file.
 */
public final class RetryPolicy {

    static final long   INITIAL_DELAY_SECONDS = 10L;
    static final double MULTIPLIER            = 3.0;

    private RetryPolicy() {}

    /**
     * Calculate the backoff duration for the given retry attempt number.
     * retryCount is the count AFTER the most recent failure.
     */
    public static Duration backoffFor(int retryCount) {
        long seconds = (long) (INITIAL_DELAY_SECONDS
                * Math.pow(MULTIPLIER, Math.max(retryCount - 1, 0)));
        return Duration.ofSeconds(seconds);
    }

    /**
     * Whether a FAILED task has waited long enough for its next retry.
     *
     * Checks:
     *   1. Task is in FAILED status (not DEAD_LETTER, COMPLETED, etc.)
     *   2. Enough time has elapsed since the last failure (updatedAt + backoff < now)
     */
    public static boolean isEligibleForRetry(Task task) {
        if (task.getStatus() != TaskStatus.FAILED) {
            return false;
        }
        Duration backoff     = backoffFor(task.getRetryCount());
        Instant  nextRetryAt = task.getUpdatedAt().plus(backoff);
        return Instant.now().isAfter(nextRetryAt);
    }
}