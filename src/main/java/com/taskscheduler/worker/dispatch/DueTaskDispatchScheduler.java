package com.taskscheduler.worker.dispatch;

import com.taskscheduler.config.DispatchProperties;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The primary dispatch mechanism for tasks whose scheduledAt was in the
 * future at creation time.
 *
 * TaskService.createTask() only publishes TASK_CREATED immediately if the
 * task is already due; otherwise it's saved as PENDING with dispatchedAt
 * left null. This scheduler polls for exactly those tasks — PENDING,
 * never dispatched, now due — and publishes TASK_CREATED for them.
 *
 * Deliberately narrow: it only looks at dispatchedAt IS NULL. A task that
 * WAS dispatched but whose event seems lost is StaleTaskRecoveryScheduler's
 * orphan sweep's job, not this one's — the split keeps "dispatch it for
 * the first time" and "recover a possibly-lost dispatch" as two independent
 * queries instead of one that has to guess which case it's looking at.
 *
 * Per-task try/catch so one lost race (another instance dispatched it
 * first) doesn't stop the rest of the batch — same pattern as
 * RetryScheduler and StaleTaskRecoveryScheduler.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DueTaskDispatchScheduler {

    private final TaskService taskService;
    private final DispatchProperties properties;

    public void dispatchDueTasks() {
        List<Task> due = taskService.getUndispatchedDueTasks(Instant.now(), properties.batchSize());

        if (due.isEmpty()) {
            return;
        }
        log.debug("Dispatch scheduler: found {} due, undispatched task(s)", due.size());

        int dispatched = 0;
        for (Task task : due) {
            UUID taskId = task.getId();
            try {
                taskService.dispatchTask(taskId);
                dispatched++;
            } catch (OptimisticLockingFailureException | IllegalStateException ex) {
                log.debug("Skipping dispatch, task already transitioned: taskId={} reason={}",
                        taskId, ex.getMessage());
            }
        }

        if (dispatched > 0) {
            log.info("Dispatch scheduler: dispatched {} due task(s)", dispatched);
        }
    }
}
