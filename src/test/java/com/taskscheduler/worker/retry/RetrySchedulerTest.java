package com.taskscheduler.worker.retry;

import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;
import com.taskscheduler.domain.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RetryScheduler")
class RetrySchedulerTest {

    @Mock    private TaskService    taskService;
    @InjectMocks private RetryScheduler scheduler;

    @Test
    @DisplayName("should schedule retry for eligible FAILED tasks")
    void shouldScheduleRetryForEligibleTasks() {
        UUID id   = UUID.randomUUID();
        Task task = eligibleFailedTask(id);
        when(taskService.getFailedTasks()).thenReturn(List.of(task));

        scheduler.processRetries();

        verify(taskService).scheduleRetry(id);
    }

    @Test
    @DisplayName("should not schedule retry when backoff has not elapsed")
    void shouldNotScheduleRetryForIneligibleTasks() {
        Task task = ineligibleFailedTask();
        when(taskService.getFailedTasks()).thenReturn(List.of(task));

        scheduler.processRetries();

        verify(taskService, never()).scheduleRetry(any());
    }

    @Test
    @DisplayName("should do nothing when there are no FAILED tasks")
    void shouldDoNothingWhenNoFailedTasks() {
        when(taskService.getFailedTasks()).thenReturn(List.of());

        scheduler.processRetries();

        verify(taskService, never()).scheduleRetry(any());
    }

    @Test
    @DisplayName("should continue processing other tasks when one throws")
    void shouldContinueOnException() {
        UUID id1  = UUID.randomUUID();
        UUID id2  = UUID.randomUUID();
        Task task1 = eligibleFailedTask(id1);
        Task task2 = eligibleFailedTask(id2);

        when(taskService.getFailedTasks()).thenReturn(List.of(task1, task2));
        doThrow(new RuntimeException("DB error")).when(taskService).scheduleRetry(id1);

        scheduler.processRetries();

        // task2 must still be retried even though task1 threw
        verify(taskService).scheduleRetry(id2);
    }

    @Test
    @DisplayName("should schedule only eligible tasks from a mixed list")
    void shouldOnlyScheduleEligibleFromMixedList() {
        UUID id1     = UUID.randomUUID();
        UUID id2     = UUID.randomUUID();
        Task eligible   = eligibleFailedTask(id1);
        Task ineligible = ineligibleFailedTask();

        when(taskService.getFailedTasks()).thenReturn(List.of(eligible, ineligible));

        scheduler.processRetries();

        verify(taskService).scheduleRetry(id1);
        verify(taskService, never()).scheduleRetry(id2);
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private Task eligibleFailedTask(UUID id) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(id);
        when(task.getStatus()).thenReturn(TaskStatus.FAILED);
        when(task.getRetryCount()).thenReturn(1);
        // 15s ago > 10s backoff for retryCount=1 → eligible
        when(task.getUpdatedAt()).thenReturn(Instant.now().minusSeconds(15));
        return task;
    }

    private Task ineligibleFailedTask() {
        Task task = mock(Task.class);
        when(task.getStatus()).thenReturn(TaskStatus.FAILED);
        when(task.getRetryCount()).thenReturn(1);
        when(task.getUpdatedAt()).thenReturn(Instant.now().minusSeconds(5));
        return task;
    }
}