package com.taskscheduler.worker.retry;

import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.service.TaskService;
import com.taskscheduler.infrastructure.metrics.TaskMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls for FAILED tasks and re-queues them after their backoff period.
 *
 * Why polling instead of event-driven retry?
 *   Event-driven retry (e.g. Spring @RetryableTopic) is powerful but opaque.
 *   A polling scheduler is explicit: you can query the DB and see exactly
 *   which tasks are waiting and when they'll be retried.
 *   Operationally: SELECT * FROM tasks WHERE status = 'FAILED' answers
 *   "what's retrying and when?" instantly.
 *
 * Poll interval: 30 seconds.
 *   Fine-grained enough for most use cases without hammering the DB.
 *   Tasks with a 10s backoff might wait up to 40s (backoff + poll interval).
 *   Acceptable for background task processing.
 *
 * initialDelay = 30s:
 *   Prevents the scheduler from firing immediately on startup before
 *   Kafka connections and worker threads are fully initialized.
 *
 * Error handling:
 *   Per-task try/catch ensures one broken task doesn't block retries
 *   for all other FAILED tasks in the same batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryScheduler {

    private final TaskService taskService;
    private final TaskMetrics taskMetrics;

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void processRetries() {
        List<Task> failedTasks = taskService.getFailedTasks();

        if (failedTasks.isEmpty()) {
            return;
        }

        log.debug("Checking {} FAILED task(s) for retry eligibility", failedTasks.size());

        int scheduled = 0;
        for (Task task : failedTasks) {
            if (RetryPolicy.isEligibleForRetry(task)) {
                try {
                    taskService.scheduleRetry(task.getId());
                    taskMetrics.recordRetryScheduled();
                    scheduled++;
                } catch (Exception ex) {
                    // Log and continue — one broken task must not block others
                    log.error("Failed to schedule retry: taskId={} error={}",
                            task.getId(), ex.getMessage(), ex);
                }
            }
        }

        if (scheduled > 0) {
            log.info("Retry scheduler: queued {} task(s) for re-execution", scheduled);
        }
    }
}