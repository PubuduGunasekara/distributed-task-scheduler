package com.taskscheduler.worker.recovery;

import com.taskscheduler.config.RecoveryProperties;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.service.TaskService;
import com.taskscheduler.infrastructure.metrics.TaskMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StaleTaskRecoveryScheduler")
class StaleTaskRecoverySchedulerTest {

    private static final String TIMEOUT_MESSAGE = "Execution timed out: worker presumed dead";

    @Mock private TaskService taskService;

    // Real instances — see TaskWorkerServiceTest for why TaskMetrics can't be mocked.
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TaskMetrics   taskMetrics   = new TaskMetrics(meterRegistry);
    private final RecoveryProperties properties = new RecoveryProperties(
            Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(2), 100);

    private StaleTaskRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new StaleTaskRecoveryScheduler(taskService, taskMetrics, properties);
        lenient().when(taskService.getStaleRunningTasks(any(), anyInt())).thenReturn(List.of());
        lenient().when(taskService.getOrphanedPendingTasks(any(), any(), anyInt())).thenReturn(List.of());
    }

    // =========================================================
    // STUCK RUNNING TASKS
    // =========================================================

    @Nested
    @DisplayName("stuck RUNNING tasks")
    class StuckRunning {

        @Test
        @DisplayName("should fail a task stuck past the execution timeout")
        void shouldFailStuckTask() {
            UUID id = UUID.randomUUID();
            Task task = taskWithId(id);
            when(taskService.getStaleRunningTasks(any(), eq(100))).thenReturn(List.of(task));

            scheduler.recoverStaleTasks();

            verify(taskService).failTask(id, TIMEOUT_MESSAGE);
        }

        @Test
        @DisplayName("should do nothing when no RUNNING task is stale")
        void shouldDoNothingWhenNoneStale() {
            scheduler.recoverStaleTasks();

            verify(taskService, never()).failTask(any(), any());
        }

        @Test
        @DisplayName("should continue recovering others when one already transitioned (optimistic lock)")
        void shouldContinueOnOptimisticLockFailure() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            Task task1 = taskWithId(id1);
            Task task2 = taskWithId(id2);
            when(taskService.getStaleRunningTasks(any(), eq(100)))
                    .thenReturn(List.of(task1, task2));
            doThrow(new ObjectOptimisticLockingFailureException(Task.class, id1))
                    .when(taskService).failTask(eq(id1), anyString());

            scheduler.recoverStaleTasks();

            verify(taskService).failTask(id1, TIMEOUT_MESSAGE);
            verify(taskService).failTask(id2, TIMEOUT_MESSAGE);
        }

        @Test
        @DisplayName("should continue recovering others when one is no longer RUNNING (illegal state)")
        void shouldContinueOnIllegalStateException() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            Task task1 = taskWithId(id1);
            Task task2 = taskWithId(id2);
            when(taskService.getStaleRunningTasks(any(), eq(100)))
                    .thenReturn(List.of(task1, task2));
            doThrow(new IllegalStateException("not RUNNING"))
                    .when(taskService).failTask(eq(id1), anyString());

            scheduler.recoverStaleTasks();

            verify(taskService).failTask(id2, TIMEOUT_MESSAGE);
        }

        @Test
        @DisplayName("should query with the configured batch size as the limit")
        void shouldRespectBatchSize() {
            RecoveryProperties smallBatch = new RecoveryProperties(
                    Duration.ofSeconds(60), Duration.ofMinutes(5), Duration.ofMinutes(2), 5);
            scheduler = new StaleTaskRecoveryScheduler(taskService, taskMetrics, smallBatch);
            when(taskService.getStaleRunningTasks(any(), anyInt())).thenReturn(List.of());
            when(taskService.getOrphanedPendingTasks(any(), any(), anyInt())).thenReturn(List.of());

            scheduler.recoverStaleTasks();

            verify(taskService).getStaleRunningTasks(any(), eq(5));
            verify(taskService).getOrphanedPendingTasks(any(), any(), eq(5));
        }
    }

    // =========================================================
    // ORPHANED PENDING TASKS
    // =========================================================

    @Nested
    @DisplayName("orphaned PENDING tasks")
    class OrphanedPending {

        @Test
        @DisplayName("should republish a due orphaned task")
        void shouldRepublishOrphan() {
            UUID id = UUID.randomUUID();
            Task task = taskWithId(id);
            when(taskService.getOrphanedPendingTasks(any(), any(), eq(100)))
                    .thenReturn(List.of(task));

            scheduler.recoverStaleTasks();

            verify(taskService).republishOrphanedTask(id);
        }

        @Test
        @DisplayName("should do nothing when no PENDING task is orphaned")
        void shouldDoNothingWhenNoneOrphaned() {
            scheduler.recoverStaleTasks();

            verify(taskService, never()).republishOrphanedTask(any());
        }

        @Test
        @DisplayName("should continue republishing others when one already transitioned (optimistic lock)")
        void shouldContinueOnOptimisticLockFailure() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            Task task1 = taskWithId(id1);
            Task task2 = taskWithId(id2);
            when(taskService.getOrphanedPendingTasks(any(), any(), eq(100)))
                    .thenReturn(List.of(task1, task2));
            doThrow(new ObjectOptimisticLockingFailureException(Task.class, id1))
                    .when(taskService).republishOrphanedTask(id1);

            scheduler.recoverStaleTasks();

            verify(taskService).republishOrphanedTask(id1);
            verify(taskService).republishOrphanedTask(id2);
        }

        @Test
        @DisplayName("should continue republishing others when one is no longer PENDING (illegal state)")
        void shouldContinueOnIllegalStateException() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            Task task1 = taskWithId(id1);
            Task task2 = taskWithId(id2);
            when(taskService.getOrphanedPendingTasks(any(), any(), eq(100)))
                    .thenReturn(List.of(task1, task2));
            doThrow(new IllegalStateException("not PENDING"))
                    .when(taskService).republishOrphanedTask(id1);

            scheduler.recoverStaleTasks();

            verify(taskService).republishOrphanedTask(id2);
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private Task taskWithId(UUID id) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(id);
        return task;
    }
}
