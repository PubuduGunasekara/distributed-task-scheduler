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

    public Task createTask(
            String name, String type, String payload,
            int priority, Instant scheduledAt
    ) {
        Task task  = Task.create(name, type, payload, priority, scheduledAt);
        Task saved = taskRepository.save(task);
        taskEventPort.publish(saved, TaskEventType.TASK_CREATED);   // ← ADD
        log.info("Task created: id={} name='{}' type={} priority={} scheduledAt={}",
                saved.getId(), saved.getName(), saved.getType(),
                saved.getPriority(), saved.getScheduledAt());
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
        taskEventPort.publish(saved, TaskEventType.TASK_STARTED);   // ← ADD
        log.info("Task started: id={}", id);
        return saved;
    }

    public Task completeTask(UUID id) {
        Task task  = getTask(id);
        task.complete();
        Task saved = taskRepository.save(task);
        taskEventPort.publish(saved, TaskEventType.TASK_COMPLETED); // ← ADD
        log.info("Task completed: id={}", id);
        return saved;
    }

    public Task failTask(UUID id, String errorMessage) {
        Task task  = getTask(id);
        task.fail(errorMessage);
        Task saved = taskRepository.save(task);
        taskEventPort.publish(saved, TaskEventType.TASK_FAILED);    // ← ADD
        log.warn("Task failed: id={} retryCount={} status={} error='{}'",
                id, saved.getRetryCount(), saved.getStatus(), errorMessage);
        return saved;
    }

    public Task cancelTask(UUID id) {
        Task task  = getTask(id);
        task.cancel();
        Task saved = taskRepository.save(task);
        taskEventPort.publish(saved, TaskEventType.TASK_CANCELLED); // ← ADD
        log.info("Task cancelled: id={}", id);
        return saved;
    }
}