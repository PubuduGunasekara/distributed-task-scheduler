package com.taskscheduler.worker.service;

import com.taskscheduler.domain.event.TaskEvent;
import com.taskscheduler.domain.event.TaskEventType;
import com.taskscheduler.domain.exception.TaskNotFoundException;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;
import com.taskscheduler.domain.service.TaskService;
import com.taskscheduler.worker.executor.TaskExecutorRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

    @InjectMocks
    private TaskWorkerService workerService;

    @Nested
    @DisplayName("process() — TASK_CREATED")
    class ProcessCreated {

        @Test
        @DisplayName("should start, execute, and complete task on success")
        void shouldStartExecuteAndComplete() throws Exception {
            UUID id      = UUID.randomUUID();
            Task running = mock(Task.class);
            when(running.getId()).thenReturn(id); // only field accessed in executeAndFinalize()

            when(taskService.startTask(id)).thenReturn(running);
            // executorRegistry.execute() is void — Mockito does nothing by default, no stub needed
            // taskService.completeTask() return value is unused in worker — no stub needed

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
            Task running = mock(Task.class);
            when(running.getId()).thenReturn(id); // only getId() is accessed in this path

            when(taskService.startTask(id)).thenReturn(running);
            doThrow(new RuntimeException("connection timeout"))
                    .when(executorRegistry).execute(running);

            workerService.process(createdEvent(id));

            verify(taskService).failTask(id, "connection timeout");
            verify(taskService, never()).completeTask(any());
        }

        @Test
        @DisplayName("should skip when task is not PENDING — duplicate delivery guard")
        void shouldSkipWhenTaskNotPending() throws Exception {
            UUID id = UUID.randomUUID();
            when(taskService.startTask(id))
                    .thenThrow(new IllegalStateException("Task already RUNNING"));

            workerService.process(createdEvent(id));

            verify(executorRegistry, never()).execute(any());
            verify(taskService, never()).completeTask(any());
            verify(taskService, never()).failTask(any(), any());
        }

        @Test
        @DisplayName("should skip when task not found in database")
        void shouldSkipWhenTaskNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(taskService.startTask(id))
                    .thenThrow(new TaskNotFoundException(id));

            workerService.process(createdEvent(id));

            verify(executorRegistry, never()).execute(any());
        }
    }

    @Nested
    @DisplayName("process() — non-TASK_CREATED events")
    class ProcessOtherEvents {

        @Test
        @DisplayName("should skip TASK_STARTED event")
        void shouldSkipStartedEvent() {
            workerService.process(eventOf(UUID.randomUUID(), TaskEventType.TASK_STARTED));
            verifyNoInteractions(taskService, executorRegistry);
        }

        @Test
        @DisplayName("should skip TASK_COMPLETED event")
        void shouldSkipCompletedEvent() {
            workerService.process(eventOf(UUID.randomUUID(), TaskEventType.TASK_COMPLETED));
            verifyNoInteractions(taskService, executorRegistry);
        }

        @Test
        @DisplayName("should skip TASK_FAILED event")
        void shouldSkipFailedEvent() {
            workerService.process(eventOf(UUID.randomUUID(), TaskEventType.TASK_FAILED));
            verifyNoInteractions(taskService, executorRegistry);
        }

        @Test
        @DisplayName("should skip TASK_CANCELLED event")
        void shouldSkipCancelledEvent() {
            workerService.process(eventOf(UUID.randomUUID(), TaskEventType.TASK_CANCELLED));
            verifyNoInteractions(taskService, executorRegistry);
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

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