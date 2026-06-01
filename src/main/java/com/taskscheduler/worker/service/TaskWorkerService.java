package com.taskscheduler.worker.service;

import com.taskscheduler.domain.event.TaskEvent;
import com.taskscheduler.domain.event.TaskEventType;
import com.taskscheduler.domain.exception.TaskNotFoundException;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.port.DistributedLockPort;
import com.taskscheduler.domain.service.TaskService;
import com.taskscheduler.infrastructure.metrics.TaskMetrics;
import com.taskscheduler.worker.executor.TaskExecutorRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orchestrates task execution with distributed locking.
 *
 * Lock acquisition is the first thing that happens for every
 * TASK_CREATED event. If the lock is unavailable, we return
 * immediately — zero database queries, zero wasted work.
 *
 * The finally block guarantees lock release regardless of whether
 * the task succeeded, failed, or threw an unexpected exception.
 * This is critical: a lock not released means that task can never
 * be processed again until the 30-second TTL expires.
 *
 * workerId uniquely identifies this JVM instance.
 * Stored as the lock value in Redis — visible when debugging:
 *   redis-cli GET task-lock:{uuid}  → shows which worker holds it
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskWorkerService {

    private final TaskService          taskService;
    private final TaskExecutorRegistry executorRegistry;
    private final DistributedLockPort  lockPort;
    private final TaskMetrics taskMetrics;
    private final MeterRegistry meterRegistry;

    /**
     * Unique identifier for this worker instance.
     * Generated once at JVM startup — stable for the lifetime of this process.
     */
    private final String workerId = UUID.randomUUID().toString();

    public void process(TaskEvent event) {
        if (event.eventType() != TaskEventType.TASK_CREATED) {
            log.debug("Skipping non-actionable event: type={} taskId={}",
                    event.eventType(), event.taskId());
            return;
        }

        UUID taskId = event.taskId();

        // First guard: Redis lock — fast, no DB query
        if (!lockPort.acquireLock(taskId, workerId)) {
            taskMetrics.recordLockRejected();
            log.info("Lock unavailable for taskId={}", taskId);
            return;
        }
        taskMetrics.recordLockAcquired();

        log.info("Lock acquired, processing task: taskId={} worker={}",
                taskId, workerId);

        try {
            // Second guard: state machine in startTask()
            // Throws IllegalStateException if task is not PENDING
            Task runningTask = taskService.startTask(taskId);
            executeAndFinalize(runningTask);

        } catch (TaskNotFoundException ex) {
            log.error("Task not found: taskId={}", taskId, ex);
        } catch (IllegalStateException ex) {
            log.warn("Task not in PENDING state, skipping: taskId={} reason={}",
                    taskId, ex.getMessage());
        } finally {
            // ALWAYS release the lock — success or failure
            lockPort.releaseLock(taskId, workerId);
        }
    }

    private void executeAndFinalize(Task task) {
        UUID         taskId = task.getId();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            executorRegistry.execute(task);
            taskService.completeTask(taskId);
            sample.stop(taskMetrics.executionTimer(task.getType()));
            taskMetrics.recordTaskCompleted(task.getType());          // ← must be here
            log.info("Task completed: taskId={}", taskId);

        } catch (Exception ex) {
            sample.stop(taskMetrics.executionTimer(task.getType()));
            taskMetrics.recordTaskFailed(task.getType());             // ← must be here
            log.error("Task execution failed: taskId={}", taskId, ex);
            taskService.failTask(taskId, ex.getMessage());
        }
    }
}