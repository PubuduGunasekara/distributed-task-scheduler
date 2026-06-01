package com.taskscheduler.worker.service;

import com.taskscheduler.domain.event.TaskEvent;
import com.taskscheduler.domain.event.TaskEventType;
import com.taskscheduler.domain.exception.TaskNotFoundException;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;
import com.taskscheduler.domain.port.DistributedLockPort;
import com.taskscheduler.domain.service.TaskService;
import com.taskscheduler.infrastructure.metrics.TaskMetrics;
import com.taskscheduler.worker.executor.TaskExecutorRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskWorkerService")
class TaskWorkerServiceTest {

    @Mock private TaskService          taskService;
    @Mock private TaskExecutorRegistry executorRegistry;
    @Mock private DistributedLockPort  lockPort;

    // Real instances — MeterRegistry and TaskMetrics cannot be mocked
    // because Timer.start(registry) calls registry.config() internally,
    // which returns null on a Mockito mock → NullPointerException.
    // SimpleMeterRegistry is an in-memory no-op registry — zero I/O.
    private final MeterRegistry    meterRegistry = new SimpleMeterRegistry();
    private final TaskMetrics      taskMetrics   = new TaskMetrics(meterRegistry);
    private       TaskWorkerService workerService;

    @BeforeEach
    void setUp() {
        workerService = new TaskWorkerService(
                taskService, executorRegistry, lockPort, taskMetrics, meterRegistry
        );
    }

    // =========================================================
    // LOCKING BEHAVIOUR
    // =========================================================

    @Nested
    @DisplayName("distributed locking")
    class Locking {

        @Test
        @DisplayName("should skip task when lock is not available")
        void shouldSkipWhenLockNotAvailable() {
            UUID taskId = UUID.randomUUID();
            when(lockPort.acquireLock(eq(taskId), anyString())).thenReturn(false);

            workerService.process(createdEvent(taskId));

            verifyNoInteractions(taskService, executorRegistry);
        }

        @Test
        @DisplayName("should release lock after successful execution")
        void shouldReleaseLockAfterSuccess() throws Exception {
            UUID id      = UUID.randomUUID();
            Task running = minimalMock(id);
            when(lockPort.acquireLock(eq(id), anyString())).thenReturn(true);
            when(taskService.startTask(id)).thenReturn(running);

            workerService.process(createdEvent(id));

            verify(lockPort).releaseLock(eq(id), anyString());
        }

        @Test
        @DisplayName("should release lock even when startTask throws")
        void shouldReleaseLockWhenStartTaskThrows() {
            UUID id = UUID.randomUUID();
            when(lockPort.acquireLock(eq(id), anyString())).thenReturn(true);
            when(taskService.startTask(id))
                    .thenThrow(new IllegalStateException("already RUNNING"));

            workerService.process(createdEvent(id));

            verify(lockPort).releaseLock(eq(id), anyString());
        }

        @Test
        @DisplayName("should release lock even when execution fails")
        void shouldReleaseLockWhenExecutionFails() throws Exception {
            UUID id      = UUID.randomUUID();
            Task running = minimalMock(id);
            when(lockPort.acquireLock(eq(id), anyString())).thenReturn(true);
            when(taskService.startTask(id)).thenReturn(running);
            doThrow(new RuntimeException("timeout"))
                    .when(executorRegistry).execute(running);

            workerService.process(createdEvent(id));

            verify(lockPort).releaseLock(eq(id), anyString());
        }
    }

    // =========================================================
    // PROCESSING BEHAVIOUR
    // =========================================================

    @Nested
    @DisplayName("process() — TASK_CREATED")
    class ProcessCreated {

        @Test
        @DisplayName("should start, execute, and complete task on success")
        void shouldStartExecuteAndComplete() throws Exception {
            UUID id      = UUID.randomUUID();
            Task running = minimalMock(id);
            when(lockPort.acquireLock(eq(id), anyString())).thenReturn(true);
            when(taskService.startTask(id)).thenReturn(running);

            workerService.process(createdEvent(id));

            verify(taskService).startTask(id);
            verify(executorRegistry).execute(running);
            verify(taskService).completeTask(id);
            verify(taskService, never()).failTask(any(), any());
        }

        @Test
        @DisplayName("should fail task when executor throws")
        void shouldFailTaskWhenExecutorThrows() throws Exception {
            UUID id      = UUID.randomUUID();
            Task running = minimalMock(id);
            when(lockPort.acquireLock(eq(id), anyString())).thenReturn(true);
            when(taskService.startTask(id)).thenReturn(running);
            doThrow(new RuntimeException("connection timeout"))
                    .when(executorRegistry).execute(running);

            workerService.process(createdEvent(id));

            verify(taskService).failTask(id, "connection timeout");
            verify(taskService, never()).completeTask(any());
        }

        @Test
        @DisplayName("should skip when task is not in PENDING state")
        void shouldSkipWhenTaskNotPending() throws Exception {
            UUID id = UUID.randomUUID();
            when(lockPort.acquireLock(eq(id), anyString())).thenReturn(true);
            when(taskService.startTask(id))
                    .thenThrow(new IllegalStateException("Task already RUNNING"));

            workerService.process(createdEvent(id));

            verify(executorRegistry, never()).execute(any());
            verify(taskService, never()).completeTask(any());
            verify(taskService, never()).failTask(any(), any());
        }

        @Test
        @DisplayName("should skip when task not found")
        void shouldSkipWhenTaskNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(lockPort.acquireLock(eq(id), anyString())).thenReturn(true);
            when(taskService.startTask(id))
                    .thenThrow(new TaskNotFoundException(id));

            workerService.process(createdEvent(id));

            verify(executorRegistry, never()).execute(any());
        }
    }

    // =========================================================
    // NON-ACTIONABLE EVENTS
    // =========================================================

    @Nested
    @DisplayName("process() — non-TASK_CREATED events")
    class ProcessOtherEvents {

        @Test
        @DisplayName("should skip TASK_STARTED event")
        void shouldSkipStarted() {
            workerService.process(eventOf(UUID.randomUUID(), TaskEventType.TASK_STARTED));
            verifyNoInteractions(lockPort, taskService, executorRegistry);
        }

        @Test
        @DisplayName("should skip TASK_COMPLETED event")
        void shouldSkipCompleted() {
            workerService.process(eventOf(UUID.randomUUID(), TaskEventType.TASK_COMPLETED));
            verifyNoInteractions(lockPort, taskService, executorRegistry);
        }

        @Test
        @DisplayName("should skip TASK_FAILED event")
        void shouldSkipFailed() {
            workerService.process(eventOf(UUID.randomUUID(), TaskEventType.TASK_FAILED));
            verifyNoInteractions(lockPort, taskService, executorRegistry);
        }

        @Test
        @DisplayName("should skip TASK_CANCELLED event")
        void shouldSkipCancelled() {
            workerService.process(eventOf(UUID.randomUUID(), TaskEventType.TASK_CANCELLED));
            verifyNoInteractions(lockPort, taskService, executorRegistry);
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    /**
     * Minimal task mock — stubs both getId() and getType().
     * getType() is required because executeAndFinalize() calls
     * taskMetrics.executionTimer(task.getType()), and
     * ConcurrentHashMap.computeIfAbsent(null) throws NPE.
     */
    private Task minimalMock(UUID id) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(id);
        when(task.getType()).thenReturn("EMAIL_SEND");
        return task;
    }

    private TaskEvent createdEvent(UUID taskId) {
        return eventOf(taskId, TaskEventType.TASK_CREATED);
    }

    private TaskEvent eventOf(UUID taskId, TaskEventType type) {
        return new TaskEvent(
                UUID.randomUUID().toString(), type, taskId,
                "test-task", "EMAIL_SEND", TaskStatus.PENDING,
                5, Instant.now(), Instant.now(), 0
        );
    }
}