package com.taskscheduler.domain.model;

/**
 * Task lifecycle states.
 *
 * Rules:
 * - Transitions are enforced by Task's business methods only.
 * - Never call setStatus() — it doesn't exist.
 * - Terminal states have no outgoing transitions.
 */
public enum TaskStatus {

    /** Waiting to be claimed by a worker. */
    PENDING,

    /** Actively executing on a worker node. */
    RUNNING,

    /** Finished successfully. Terminal. */
    COMPLETED,

    /** Execution failed. Will retry if retryCount < maxRetries. */
    FAILED,

    /** Exhausted all retries. Requires manual intervention. Terminal. */
    DEAD_LETTER,

    /** Manually cancelled before execution. Terminal. */
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == DEAD_LETTER || this == CANCELLED;
    }
}