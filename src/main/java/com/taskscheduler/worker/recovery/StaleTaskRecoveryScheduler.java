package com.taskscheduler.worker.recovery;

import com.taskscheduler.config.RecoveryProperties;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.service.TaskService;
import com.taskscheduler.infrastructure.metrics.TaskMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Makes the crash-recovery guarantee in the README actually true: if a
 * worker dies mid-task, or a TASK_CREATED event is dropped, the task does
 * not stay stuck forever.
 *
 * Two independent sweeps, run on the same poll interval:
 *
 * 1. Stuck RUNNING tasks — a worker acquired the Redis lock, called
 *    startTask(), then crashed before completing or failing the task.
 *    The lock expires after 30s, but a redelivered TASK_CREATED event is
 *    rejected by the state machine (task isn't PENDING), so nothing ever
 *    moves it out of RUNNING. We fail it through the normal domain path,
 *    so it flows through the existing retry/backoff/dead-letter pipeline.
 *
 * 2. Orphaned PENDING tasks — a due task whose TASK_CREATED event never
 *    reached a worker (published-but-lost, or lock-rejected while the
 *    real event was acknowledged anyway). We re-publish TASK_CREATED for
 *    it. This is safe to repeat: the Redis lock and the PENDING-only state
 *    transition in startTask() make duplicate events harmless.
 *
 * Runs on every app instance. Multiple instances racing on the same task
 * is expected and safe — the state machine (requireStatus) and JPA
 * @Version optimistic locking are the real guards here, not this
 * scheduler's timing. A per-task try/catch keeps one lost race from
 * stopping the rest of the batch, same pattern as RetryScheduler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleTaskRecoveryScheduler {

    private final TaskService     taskService;
    private final TaskMetrics     taskMetrics;
    private final RecoveryProperties properties;

    public void recoverStaleTasks() {
        recoverStuckRunningTasks();
        republishOrphanedPendingTasks();
    }

    private void recoverStuckRunningTasks() {
        Instant cutoff = Instant.now().minus(properties.executionTimeout());
        List<Task> stuck = taskService.getStaleRunningTasks(cutoff, properties.batchSize());

        if (stuck.isEmpty()) {
            return;
        }
        log.info("Recovery scheduler: found {} stale RUNNING task(s) past execution timeout", stuck.size());

        int recovered = 0;
        for (Task task : stuck) {
            UUID taskId = task.getId();
            try {
                taskService.failTask(taskId, "Execution timed out: worker presumed dead");
                taskMetrics.recordTaskRecoveredFromTimeout();
                recovered++;
            } catch (OptimisticLockingFailureException | IllegalStateException ex) {
                // Another instance already recovered it, or the original worker
                // finished (completed/failed it) between our query and this call.
                log.debug("Skipping stale task recovery, already transitioned: taskId={} reason={}",
                        taskId, ex.getMessage());
            }
        }

        if (recovered > 0) {
            log.info("Recovery scheduler: recovered {} stale RUNNING task(s)", recovered);
        }
    }

    private void republishOrphanedPendingTasks() {
        Instant now    = Instant.now();
        Instant cutoff = now.minus(properties.orphanThreshold());
        List<Task> orphans = taskService.getOrphanedPendingTasks(now, cutoff, properties.batchSize());

        if (orphans.isEmpty()) {
            return;
        }
        log.info("Recovery scheduler: found {} orphaned PENDING task(s)", orphans.size());

        int republished = 0;
        for (Task task : orphans) {
            UUID taskId = task.getId();
            try {
                taskService.republishOrphanedTask(taskId);
                taskMetrics.recordOrphanTaskRepublished();
                republished++;
            } catch (OptimisticLockingFailureException | IllegalStateException ex) {
                log.debug("Skipping orphan republish, task already transitioned: taskId={} reason={}",
                        taskId, ex.getMessage());
            }
        }

        if (republished > 0) {
            log.info("Recovery scheduler: re-published {} orphaned PENDING task(s)", republished);
        }
    }
}
