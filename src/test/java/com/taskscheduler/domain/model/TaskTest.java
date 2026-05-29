package com.taskscheduler.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Task")
class TaskTest {

    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("should initialize with PENDING status")
        void shouldInitializeWithPendingStatus() {
            assertThat(buildTask().getStatus()).isEqualTo(TaskStatus.PENDING);
        }

        @Test
        @DisplayName("should initialize retryCount to zero")
        void shouldInitializeRetryCountToZero() {
            assertThat(buildTask().getRetryCount()).isZero();
        }

        @Test
        @DisplayName("should set createdAt to approximately now")
        void shouldSetCreatedAt() {
            Instant before = Instant.now();
            Task task      = buildTask();
            Instant after  = Instant.now();
            assertThat(task.getCreatedAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("should throw when priority is negative")
        void shouldThrowForNegativePriority() {
            assertThatThrownBy(() ->
                    Task.create("name", "TYPE", "{}", -1, Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Priority must be 0–10");
        }

        @Test
        @DisplayName("should throw when priority exceeds 10")
        void shouldThrowForPriorityAboveMax() {
            assertThatThrownBy(() ->
                    Task.create("name", "TYPE", "{}", 11, Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should accept boundary values 0 and 10")
        void shouldAcceptBoundaryPriorities() {
            assertThatCode(() -> Task.create("n", "T", "{}", 0,  Instant.now()))
                    .doesNotThrowAnyException();
            assertThatCode(() -> Task.create("n", "T", "{}", 10, Instant.now()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("start()")
    class Start {

        @Test
        @DisplayName("should transition PENDING → RUNNING")
        void shouldTransitionToRunning() {
            Task task = buildTask();
            task.start();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
        }

        @Test
        @DisplayName("should set startedAt")
        void shouldSetStartedAt() {
            Task task = buildTask();
            task.start();
            assertThat(task.getStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("should throw when already RUNNING")
        void shouldThrowWhenAlreadyRunning() {
            Task task = buildTask();
            task.start();
            assertThatThrownBy(task::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RUNNING");
        }

        @Test
        @DisplayName("should throw when COMPLETED")
        void shouldThrowWhenCompleted() {
            Task task = buildRunningTask();
            task.complete();
            assertThatThrownBy(task::start)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("complete()")
    class Complete {

        @Test
        @DisplayName("should transition RUNNING → COMPLETED")
        void shouldTransitionToCompleted() {
            Task task = buildRunningTask();
            task.complete();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        }

        @Test
        @DisplayName("should set completedAt")
        void shouldSetCompletedAt() {
            Task task = buildRunningTask();
            task.complete();
            assertThat(task.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("should throw when PENDING")
        void shouldThrowWhenPending() {
            assertThatThrownBy(() -> buildTask().complete())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("fail()")
    class Fail {

        @Test
        @DisplayName("should transition to FAILED when retries remain")
        void shouldTransitionToFailed() {
            Task task = buildRunningTask();
            task.fail("timeout");
            assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(task.getRetryCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should transition to DEAD_LETTER when retries exhausted")
        void shouldTransitionToDeadLetterWhenExhausted() {
            Task task = buildTask();
            for (int i = 0; i < 3; i++) {
                task.start();
                task.fail("error " + i);
                if (task.getStatus() == TaskStatus.FAILED) {
                    task.scheduleRetry();
                }
            }
            assertThat(task.getStatus()).isEqualTo(TaskStatus.DEAD_LETTER);
            assertThat(task.getFailedAt()).isNotNull();
        }

        @Test
        @DisplayName("should record error message")
        void shouldRecordErrorMessage() {
            Task task = buildRunningTask();
            task.fail("connection refused");
            assertThat(task.getErrorMessage()).isEqualTo("connection refused");
        }

        @Test
        @DisplayName("should throw when not RUNNING")
        void shouldThrowWhenNotRunning() {
            assertThatThrownBy(() -> buildTask().fail("error"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("cancel()")
    class Cancel {

        @Test
        @DisplayName("should transition PENDING → CANCELLED")
        void shouldTransitionToCancelled() {
            Task task = buildTask();
            task.cancel();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        }

        @Test
        @DisplayName("should throw when RUNNING")
        void shouldThrowWhenRunning() {
            Task task = buildRunningTask();
            assertThatThrownBy(task::cancel)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("isDue()")
    class IsDue {

        @Test
        @DisplayName("should return true for past scheduledAt")
        void shouldReturnTrueForPast() {
            Task task = Task.create("n", "T", "{}", 1, Instant.now().minusSeconds(60));
            assertThat(task.isDue()).isTrue();
        }

        @Test
        @DisplayName("should return false for future scheduledAt")
        void shouldReturnFalseForFuture() {
            Task task = Task.create("n", "T", "{}", 1, Instant.now().plusSeconds(3600));
            assertThat(task.isDue()).isFalse();
        }
    }

    @Nested
    @DisplayName("TaskStatus.isTerminal()")
    class IsTerminal {

        @Test
        @DisplayName("COMPLETED should be terminal")
        void completedIsTerminal() {
            assertThat(TaskStatus.COMPLETED.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("DEAD_LETTER should be terminal")
        void deadLetterIsTerminal() {
            assertThat(TaskStatus.DEAD_LETTER.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("CANCELLED should be terminal")
        void cancelledIsTerminal() {
            assertThat(TaskStatus.CANCELLED.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("PENDING should not be terminal")
        void pendingIsNotTerminal() {
            assertThat(TaskStatus.PENDING.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("RUNNING should not be terminal")
        void runningIsNotTerminal() {
            assertThat(TaskStatus.RUNNING.isTerminal()).isFalse();
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private Task buildTask() {
        return Task.create("test-task", "EMAIL_SEND", "{}", 5, Instant.now());
    }

    private Task buildRunningTask() {
        Task task = buildTask();
        task.start();
        return task;
    }
}