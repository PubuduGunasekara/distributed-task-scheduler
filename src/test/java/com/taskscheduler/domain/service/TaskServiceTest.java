package com.taskscheduler.domain.service;

import com.taskscheduler.domain.exception.TaskNotFoundException;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;
import com.taskscheduler.domain.repository.TaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskService")
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private TaskService taskService;

    @Nested
    @DisplayName("startTask()")
    class StartTask {

        @Test
        @DisplayName("should transition task to RUNNING")
        void shouldTransitionToRunning() {
            UUID id   = UUID.randomUUID();
            Task task = buildPendingTask();
            when(taskRepository.findById(id)).thenReturn(Optional.of(task));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Task result = taskService.startTask(id);

            assertThat(result.getStatus()).isEqualTo(TaskStatus.RUNNING);
        }

        @Test
        @DisplayName("should throw TaskNotFoundException when task does not exist")
        void shouldThrowWhenNotFound() {
            UUID id = UUID.randomUUID();
            when(taskRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.startTask(id))
                    .isInstanceOf(TaskNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("completeTask()")
    class CompleteTask {

        @Test
        @DisplayName("should transition task to COMPLETED")
        void shouldTransitionToCompleted() {
            UUID id   = UUID.randomUUID();
            Task task = buildRunningTask();
            when(taskRepository.findById(id)).thenReturn(Optional.of(task));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Task result = taskService.completeTask(id);

            assertThat(result.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        }
    }

    @Nested
    @DisplayName("cancelTask()")
    class CancelTask {

        @Test
        @DisplayName("should transition task to CANCELLED")
        void shouldTransitionToCancelled() {
            UUID id   = UUID.randomUUID();
            Task task = buildPendingTask();
            when(taskRepository.findById(id)).thenReturn(Optional.of(task));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Task result = taskService.cancelTask(id);

            assertThat(result.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("getDueTasks() — no-arg overload")
    class GetDueTasksNoArg {

        @Test
        @DisplayName("should delegate to getDueTasks with default batch size")
        void shouldDelegateWithDefaultBatchSize() {
            when(taskRepository.findDueTasks(
                    eq(TaskStatus.PENDING), any(Instant.class), any(Pageable.class))
            ).thenReturn(List.of(buildPendingTask()));

            List<Task> result = taskService.getDueTasks();

            assertThat(result).hasSize(1);
            verify(taskRepository).findDueTasks(
                    eq(TaskStatus.PENDING), any(Instant.class), any(Pageable.class)
            );
        }
    }

    @Nested
    @DisplayName("createTask()")
    class CreateTask {

        @Test
        @DisplayName("should save and return the task")
        void shouldSaveAndReturn() {
            Task expected = buildPendingTask();
            when(taskRepository.save(any(Task.class))).thenReturn(expected);

            Task result = taskService.createTask(
                    "test", "EMAIL", "{}", 5, Instant.now()
            );

            assertThat(result.getStatus()).isEqualTo(TaskStatus.PENDING);
            verify(taskRepository, times(1)).save(any(Task.class));
        }

        @Test
        @DisplayName("should pass correct fields to repository via ArgumentCaptor")
        void shouldPassCorrectFields() {
            ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
            when(taskRepository.save(captor.capture()))
                    .thenAnswer(inv -> inv.getArgument(0));

            Instant scheduledAt = Instant.now().plusSeconds(300);
            taskService.createTask("my-task", "REPORT", "{\"id\":1}", 8, scheduledAt);

            Task captured = captor.getValue();
            assertThat(captured.getName()).isEqualTo("my-task");
            assertThat(captured.getType()).isEqualTo("REPORT");
            assertThat(captured.getPriority()).isEqualTo(8);
            assertThat(captured.getScheduledAt()).isEqualTo(scheduledAt);
        }
    }

    @Nested
    @DisplayName("getTask()")
    class GetTask {

        @Test
        @DisplayName("should return task when found")
        void shouldReturnWhenFound() {
            UUID id   = UUID.randomUUID();
            Task task = buildPendingTask();
            when(taskRepository.findById(id)).thenReturn(Optional.of(task));

            assertThat(taskService.getTask(id)).isSameAs(task);
        }

        @Test
        @DisplayName("should throw TaskNotFoundException when not found")
        void shouldThrowWhenNotFound() {
            UUID id = UUID.randomUUID();
            when(taskRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.getTask(id))
                    .isInstanceOf(TaskNotFoundException.class)
                    .hasMessageContaining(id.toString());
        }

        @Test
        @DisplayName("should include the task ID in the exception")
        void shouldIncludeTaskIdInException() {
            UUID id = UUID.randomUUID();
            when(taskRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> taskService.getTask(id))
                    .isInstanceOf(TaskNotFoundException.class)
                    .satisfies(ex -> {
                        TaskNotFoundException notFound = (TaskNotFoundException) ex;
                        assertThat(notFound.getTaskId()).isEqualTo(id);
                    });
        }
    }

    @Nested
    @DisplayName("getDueTasks()")
    class GetDueTasks {

        @Test
        @DisplayName("should query with PENDING status")
        void shouldQueryPendingTasks() {
            when(taskRepository.findDueTasks(
                    eq(TaskStatus.PENDING), any(Instant.class), any(Pageable.class))
            ).thenReturn(List.of(buildPendingTask()));

            List<Task> result = taskService.getDueTasks(5);

            assertThat(result).hasSize(1);
        }
    }

    @Nested
    @DisplayName("failTask()")
    class FailTask {

        @Test
        @DisplayName("should transition task to FAILED when retries remain")
        void shouldTransitionToFailed() {
            UUID id   = UUID.randomUUID();
            Task task = buildRunningTask();
            when(taskRepository.findById(id)).thenReturn(Optional.of(task));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Task result = taskService.failTask(id, "timeout");

            assertThat(result.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(result.getErrorMessage()).isEqualTo("timeout");
        }
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private Task buildPendingTask() {
        return Task.create("test", "EMAIL", "{}", 1, Instant.now());
    }

    private Task buildRunningTask() {
        Task task = buildPendingTask();
        task.start();
        return task;
    }
}