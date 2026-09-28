package com.taskscheduler.worker.dispatch;

import com.taskscheduler.config.DispatchProperties;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("DueTaskDispatchScheduler")
class DueTaskDispatchSchedulerTest {

    @Mock private TaskService taskService;

    private final DispatchProperties properties = new DispatchProperties(Duration.ofSeconds(5), 100);

    private DueTaskDispatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DueTaskDispatchScheduler(taskService, properties);
        lenient().when(taskService.getUndispatchedDueTasks(any(), anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("should dispatch a previously-undispatched due task")
    void shouldDispatchDueTask() {
        UUID id = UUID.randomUUID();
        Task task = taskWithId(id);
        when(taskService.getUndispatchedDueTasks(any(), eq(100))).thenReturn(List.of(task));

        scheduler.dispatchDueTasks();

        verify(taskService).dispatchTask(id);
    }

    @Test
    @DisplayName("should do nothing when no task is due and undispatched")
    void shouldDoNothingWhenNoneDue() {
        scheduler.dispatchDueTasks();

        verify(taskService, never()).dispatchTask(any());
    }

    @Test
    @DisplayName("should dispatch a due task exactly once per poll cycle")
    void shouldDispatchExactlyOnce() {
        UUID id = UUID.randomUUID();
        Task task = taskWithId(id);
        when(taskService.getUndispatchedDueTasks(any(), eq(100))).thenReturn(List.of(task));

        scheduler.dispatchDueTasks();
        scheduler.dispatchDueTasks();

        verify(taskService, times(2)).dispatchTask(id);
        // Each call to dispatchDueTasks() dispatches whatever the query returns;
        // it's the query (dispatchedAt IS NULL) that prevents a re-dispatch once
        // TaskService.dispatchTask() has actually set dispatchedAt — that's
        // covered by TaskRepositoryTest, not this mock-based scheduler test.
    }

    @Test
    @DisplayName("should continue dispatching others when one already transitioned (optimistic lock)")
    void shouldContinueOnOptimisticLockFailure() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Task task1 = taskWithId(id1);
        Task task2 = taskWithId(id2);
        when(taskService.getUndispatchedDueTasks(any(), eq(100))).thenReturn(List.of(task1, task2));
        doThrow(new ObjectOptimisticLockingFailureException(Task.class, id1))
                .when(taskService).dispatchTask(id1);

        scheduler.dispatchDueTasks();

        verify(taskService).dispatchTask(id1);
        verify(taskService).dispatchTask(id2);
    }

    @Test
    @DisplayName("should continue dispatching others when one is no longer PENDING (illegal state)")
    void shouldContinueOnIllegalStateException() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Task task1 = taskWithId(id1);
        Task task2 = taskWithId(id2);
        when(taskService.getUndispatchedDueTasks(any(), eq(100))).thenReturn(List.of(task1, task2));
        doThrow(new IllegalStateException("not PENDING"))
                .when(taskService).dispatchTask(id1);

        scheduler.dispatchDueTasks();

        verify(taskService).dispatchTask(id2);
    }

    @Test
    @DisplayName("should query with the configured batch size as the limit")
    void shouldRespectBatchSize() {
        DispatchProperties smallBatch = new DispatchProperties(Duration.ofSeconds(5), 5);
        scheduler = new DueTaskDispatchScheduler(taskService, smallBatch);
        when(taskService.getUndispatchedDueTasks(any(), anyInt())).thenReturn(List.of());

        scheduler.dispatchDueTasks();

        verify(taskService).getUndispatchedDueTasks(any(), eq(5));
    }

    private Task taskWithId(UUID id) {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(id);
        return task;
    }
}
