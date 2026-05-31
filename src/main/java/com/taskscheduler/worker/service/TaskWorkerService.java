package com.taskscheduler.worker.service;

import com.taskscheduler.domain.event.TaskEvent;
import com.taskscheduler.domain.event.TaskEventType;
import com.taskscheduler.domain.exception.TaskNotFoundException;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.service.TaskService;
import com.taskscheduler.worker.executor.TaskExecutorRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orchestrates task execution from a received event.
 *
 * NOT @Transactional at this level — each TaskService method has its
 * own transaction. Wrapping the whole process() call in one transaction
 * would be wrong: if execution fails after startTask() commits,
 * we'd roll back the RUNNING state — but TASK_STARTED was already
 * published to Kafka (can't rollback Kafka). Separate transactions
 * keep DB and Kafka in consistent states independently.
 *
 * Idempotency guard:
 *   startTask() throws IllegalStateException if task is not PENDING.
 *   This means if the same TASK_CREATED event is delivered twice
 *   (at-least-once), the second delivery fails silently here —
 *   the task is already RUNNING or COMPLETED.
 *   Redis distributed locking (M5) adds a stronger guard.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskWorkerService {

    private final TaskService          taskService;
    private final TaskExecutorRegistry executorRegistry;

    public void process(TaskEvent event) {
        // Only workers act on TASK_CREATED events.
        // TASK_STARTED, TASK_COMPLETED, TASK_FAILED, TASK_CANCELLED
        // are audit events — consumers skip them here.
        if (event.eventType() != TaskEventType.TASK_CREATED) {
            log.debug("Skipping non-actionable event: type={} taskId={}",
                    event.eventType(), event.taskId());
            return;
        }

        UUID taskId = event.taskId();
        log.info("Processing task: taskId={}", taskId);

        try {
            // Transition PENDING → RUNNING.
            // Throws IllegalStateException if task is not PENDING
            // (duplicate delivery guard).
            Task runningTask = taskService.startTask(taskId);

            executeAndFinalize(runningTask);

        } catch (TaskNotFoundException ex) {
            log.error("Task not found in database: taskId={}", taskId, ex);
            // Event references a task that doesn't exist — ack and move on.
            // Should never happen in normal operation.

        } catch (IllegalStateException ex) {
            log.warn("Task is not in PENDING state, skipping: taskId={} reason={}",
                    taskId, ex.getMessage());
            // Duplicate delivery — task already processed. Ack and move on.
        }
    }

    private void executeAndFinalize(Task task) {
        UUID taskId = task.getId();

        try {
            executorRegistry.execute(task);
            taskService.completeTask(taskId);
            log.info("Task completed: taskId={}", taskId);

        } catch (Exception ex) {
            log.error("Task execution failed: taskId={} error='{}'",
                    taskId, ex.getMessage(), ex);
            taskService.failTask(taskId, ex.getMessage());
            // Task is now FAILED. M6 adds retry scheduling:
            // a scheduler polls for FAILED tasks and calls scheduleRetry().
        }
    }
}