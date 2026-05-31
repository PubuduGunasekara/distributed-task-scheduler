package com.taskscheduler.worker.executor;

import com.taskscheduler.domain.model.Task;

/**
 * Strategy interface for task execution.
 *
 * Each implementation handles one task type (EMAIL_SEND, REPORT_GEN, etc).
 * The TaskExecutorRegistry maps task.getType() → correct implementation.
 *
 * This is the Strategy design pattern. Adding a new task type means
 * adding a new @Component — zero changes to existing code.
 *
 * Interview note: this extensibility is the Open/Closed Principle —
 * open for extension (new executors), closed for modification (registry
 * and consumer don't change).
 */
public interface TaskExecutor {

    /**
     * The task type this executor handles.
     * Must match exactly what's stored in Task.type.
     */
    String supportedType();

    /**
     * Execute the task. Throw any exception to signal failure —
     * TaskWorkerService will catch it and call failTask().
     */
    void execute(Task task) throws Exception;
}