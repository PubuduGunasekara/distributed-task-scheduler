package com.taskscheduler.worker.executor;

import com.taskscheduler.domain.model.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fallback executor for any task type without a specific handler.
 *
 * In production this would be replaced by type-specific executors:
 *   EmailSendExecutor (supportedType = "EMAIL_SEND")
 *   ReportGeneratorExecutor (supportedType = "REPORT_GEN")
 *   etc.
 *
 * For now, it simulates work with a short sleep and logs the execution.
 * This proves the worker pipeline is functional before real integrations
 * are added — the "walking skeleton" principle applied to workers.
 */
@Slf4j
@Component
public class DefaultTaskExecutor implements TaskExecutor {

    public static final String DEFAULT_TYPE = "DEFAULT";

    @Override
    public String supportedType() {
        return DEFAULT_TYPE;
    }

    @Override
    public void execute(Task task) throws Exception {
        log.info("Executing task: id={} type='{}' name='{}' priority={}",
                task.getId(), task.getType(), task.getName(), task.getPriority());

        // Simulate I/O work — replace with real logic per task type
        Thread.sleep(50);

        log.info("Task execution finished: id={}", task.getId());
    }
}