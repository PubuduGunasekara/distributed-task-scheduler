package com.taskscheduler.worker.retry;

import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RetryPolicy")
class RetryPolicyTest {

    @Nested
    @DisplayName("backoffFor()")
    class BackoffFor {

        @Test
        @DisplayName("retryCount=1 should return 10 seconds")
        void shouldReturn10sForFirstRetry() {
            assertThat(RetryPolicy.backoffFor(1)).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("retryCount=2 should return 30 seconds")
        void shouldReturn30sForSecondRetry() {
            assertThat(RetryPolicy.backoffFor(2)).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("retryCount=3 should return 90 seconds")
        void shouldReturn90sForThirdRetry() {
            assertThat(RetryPolicy.backoffFor(3)).isEqualTo(Duration.ofSeconds(90));
        }

        @Test
        @DisplayName("backoff should grow with each retry")
        void shouldGrowWithEachRetry() {
            Duration retry1 = RetryPolicy.backoffFor(1);
            Duration retry2 = RetryPolicy.backoffFor(2);
            Duration retry3 = RetryPolicy.backoffFor(3);

            assertThat(retry1).isLessThan(retry2);
            assertThat(retry2).isLessThan(retry3);
        }
    }

    @Nested
    @DisplayName("isEligibleForRetry()")
    class IsEligibleForRetry {

        @Test
        @DisplayName("should return false when task is not FAILED")
        void shouldReturnFalseWhenNotFailed() {
            Task task = taskWithStatus(TaskStatus.RUNNING, 1,
                    Instant.now().minusSeconds(60));

            assertThat(RetryPolicy.isEligibleForRetry(task)).isFalse();
        }

        @Test
        @DisplayName("should return false when backoff has not elapsed")
        void shouldReturnFalseWhenBackoffNotElapsed() {
            // retryCount=1 → 10s backoff; updatedAt=5s ago → not yet eligible
            Task task = taskWithStatus(TaskStatus.FAILED, 1,
                    Instant.now().minusSeconds(5));

            assertThat(RetryPolicy.isEligibleForRetry(task)).isFalse();
        }

        @Test
        @DisplayName("should return true when backoff has elapsed")
        void shouldReturnTrueWhenBackoffElapsed() {
            // retryCount=1 → 10s backoff; updatedAt=15s ago → eligible
            Task task = taskWithStatus(TaskStatus.FAILED, 1,
                    Instant.now().minusSeconds(15));

            assertThat(RetryPolicy.isEligibleForRetry(task)).isTrue();
        }

        @Test
        @DisplayName("should respect longer backoff for higher retry counts")
        void shouldRespectLongerBackoffForHigherRetryCounts() {
            // retryCount=2 → 30s backoff; updatedAt=15s ago → NOT eligible yet
            Task task = taskWithStatus(TaskStatus.FAILED, 2,
                    Instant.now().minusSeconds(15));

            assertThat(RetryPolicy.isEligibleForRetry(task)).isFalse();
        }

        @Test
        @DisplayName("should return false for DEAD_LETTER tasks")
        void shouldReturnFalseForDeadLetter() {
            Task task = taskWithStatus(TaskStatus.DEAD_LETTER, 3,
                    Instant.now().minusSeconds(120));

            assertThat(RetryPolicy.isEligibleForRetry(task)).isFalse();
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private Task taskWithStatus(TaskStatus status, int retryCount, Instant updatedAt) {
        Task task = mock(Task.class);
        when(task.getStatus()).thenReturn(status);
        when(task.getRetryCount()).thenReturn(retryCount);
        when(task.getUpdatedAt()).thenReturn(updatedAt);
        return task;
    }
}