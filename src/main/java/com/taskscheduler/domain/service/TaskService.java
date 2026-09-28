package com.taskscheduler.domain.service;

import com.taskscheduler.domain.event.TaskEventType;
import com.taskscheduler.domain.exception.TaskNotFoundException;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;
import com.taskscheduler.domain.port.TaskEventPort;
import com.taskscheduler.domain.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Application service coordinating task lifecycle.
 *
 * @Transactional at class level = every public method runs in a transaction.
 * readOnly = true on queries = Hibernate skips dirty checking,
 * and at scale PostgreSQL can route these to read replicas.
 *
 * This service is intentionally thin — business logic lives in Task,
 * not here. "Anemic domain model" is the anti-pattern where services
 * do everything and entities are just data bags. We avoid it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TaskService {

    private static final int DEFAULT_BATCH_SIZE = 10;

    private final TaskRepository taskRepository;
    private final TaskEventPort taskEventPort;

    /**
     * Only publishes TASK_CREATED immediately if the task is already due.
     * A task scheduled for the future is saved as PENDING with
     * dispatchedAt left null; DueTaskDispatchScheduler publishes it once
     * scheduledAt actually arrives.
     */
    public Task createTask(
            String name, String type, String payload,
            int priority, Instant scheduledAt
    ) {
        Task task = Task.create(name, type, payload, priority, scheduledAt);
        if (task.isDue()) {
            task.markDispatched();
        }
        Task saved = taskRepository.save(task);
        if (saved.getDispatchedAt() != null) {
            taskEventPort.publish(saved, TaskEventType.TASK_CREATED);
        }
        log.info("Task created: id={} name='{}' type={} priority={} scheduledAt={} dispatched={}",
                saved.getId(), saved.getName(), saved.getType(),
                saved.getPriority(), saved.getScheduledAt(), saved.getDispatchedAt() != null);
        return saved;
    }

    @Transactional(readOnly = true)
    public Task getTask(UUID id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Task> getDueTasks(int limit) {
        return taskRepository.findDueTasks(
                TaskStatus.PENDING,
                Instant.now(),
                PageRequest.of(0, limit)
        );
    }

    @Transactional(readOnly = true)
    public List<Task> getDueTasks() {
        return getDueTasks(DEFAULT_BATCH_SIZE);
    }

    public Task startTask(UUID id) {
        Task task  = getTask(id);
        task.start();
        Task saved = taskRepository.save(task);
        taskEventPort.publish(saved, TaskEventType.TASK_STARTED);
        log.info("Task started: id={}", id);
        return saved;
    }

    public Task completeTask(UUID id) {
        Task task  = getTask(id);
        task.complete();
        Task saved = taskRepository.save(task);
        taskEventPort.publish(saved, TaskEventType.TASK_COMPLETED);
        log.info("Task completed: id={}", id);
        return saved;
    }

    public Task failTask(UUID id, String errorMessage) {
        Task task  = getTask(id);
        task.fail(errorMessage);
        Task saved = taskRepository.save(task);

        if (saved.getStatus() == TaskStatus.DEAD_LETTER) {
            log.warn("Task dead-lettered: id={} retryCount={} error='{}'",
                    id, saved.getRetryCount(), errorMessage);
            taskEventPort.publishDeadLetter(saved);
        } else {
            log.warn("Task failed: id={} retryCount={} status={} error='{}'",
                    id, saved.getRetryCount(), saved.getStatus(), errorMessage);
            taskEventPort.publish(saved, TaskEventType.TASK_FAILED);
        }

        return saved;
    }

    public Task cancelTask(UUID id) {
        Task task  = getTask(id);
        task.cancel();
        Task saved = taskRepository.save(task);
        taskEventPort.publish(saved, TaskEventType.TASK_CANCELLED);
        log.info("Task cancelled: id={}", id);
        return saved;
    }

    /**
     * Transitions a FAILED task back to PENDING and re-publishes
     * a TASK_CREATED event so the worker pool picks it up again.
     * Called by RetryScheduler after the backoff period elapses.
     */
    public Task scheduleRetry(UUID id) {
        Task task  = getTask(id);
        task.scheduleRetry();   // FAILED → PENDING
        Task saved = taskRepository.save(task);
        taskEventPort.publish(saved, TaskEventType.TASK_CREATED);
        log.info("Task scheduled for retry: id={} retryCount={}",
                id, saved.getRetryCount());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Task> getFailedTasks() {
        return taskRepository.findByStatusOrderByUpdatedAtAsc(TaskStatus.FAILED);
    }

    @Transactional(readOnly = true)
    public List<Task> getStaleRunningTasks(Instant cutoff, int limit) {
        return taskRepository.findStaleRunningTasks(
                TaskStatus.RUNNING, cutoff, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<Task> getOrphanedPendingTasks(Instant cutoff, int limit) {
        return taskRepository.findOrphanedPendingTasks(
                TaskStatus.PENDING, cutoff, PageRequest.of(0, limit));
    }

    /**
     * Re-publishes TASK_CREATED for a task that was dispatched before but
     * whose event is presumed lost. Bumps dispatchedAt so this orphan sweep
     * doesn't immediately re-flag the same task next poll cycle. Used by
     * StaleTaskRecoveryScheduler — a safety net, not the primary dispatch path.
     */
    public void republishOrphanedTask(UUID id) {
        dispatch(id, "Orphaned task re-dispatched");
    }

    @Transactional(readOnly = true)
    public List<Task> getUndispatchedDueTasks(Instant now, int limit) {
        return taskRepository.findUndispatchedDueTasks(
                TaskStatus.PENDING, now, PageRequest.of(0, limit));
    }

    /**
     * Publishes TASK_CREATED for a task that just became due and was never
     * dispatched before. This is the primary dispatch mechanism for tasks
     * created with a future scheduledAt — see DueTaskDispatchScheduler.
     */
    public void dispatchTask(UUID id) {
        dispatch(id, "Task dispatched");
    }

    private void dispatch(UUID id, String logMessage) {
        Task task = getTask(id);
        task.markDispatched();
        Task saved = taskRepository.save(task);
        taskEventPort.publish(saved, TaskEventType.TASK_CREATED);
        log.info("{}: id={} retryCount={}", logMessage, id, saved.getRetryCount());
    }
}