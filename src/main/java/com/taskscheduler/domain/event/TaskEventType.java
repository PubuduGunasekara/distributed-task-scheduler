package com.taskscheduler.domain.event;

/**
 * Types of domain events the task scheduler emits.
 *
 * Each value represents something that HAPPENED — past tense.
 * Events are facts, not commands. "TASK_CREATED" means a task
 * was created, not "please create a task".
 *
 * Interview note: this distinction matters in system design.
 * Commands can be rejected. Events cannot — they already happened.
 */
public enum TaskEventType {
    TASK_CREATED,
    TASK_STARTED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CANCELLED
}