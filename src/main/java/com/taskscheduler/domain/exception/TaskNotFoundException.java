package com.taskscheduler.domain.exception;

import java.util.UUID;

/**
 * Thrown when a task ID is well-formed but doesn't exist in the database.
 * Maps to HTTP 404 in the API layer (Milestone 2).
 *
 * Using a typed exception instead of a generic RuntimeException means
 * the API layer can catch it precisely and return exactly the right
 * HTTP status — no string matching on error messages required.
 */
public class TaskNotFoundException extends RuntimeException {

    private final UUID taskId;

    public TaskNotFoundException(UUID taskId) {
        super("Task not found: " + taskId);
        this.taskId = taskId;
    }

    public UUID getTaskId() {
        return taskId;
    }
}