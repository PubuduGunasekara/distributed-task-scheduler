package com.taskscheduler.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;
import java.util.UUID;

/**
 * Core domain entity — a unit of deferred work.
 *
 * Design decisions:
 *
 * 1. Protected no-arg constructor: JPA requires it internally,
 *    but application code must use Task.create() instead.
 *    This prevents Tasks from being created in invalid states.
 *
 * 2. No setters: state changes happen only through named business
 *    methods (start, complete, fail, cancel). This makes the state
 *    machine explicit and impossible to bypass.
 *
 * 3. @EqualsAndHashCode(of = "id"): equality based only on the
 *    database ID. Mutable fields like status must never drive
 *    equality — it breaks HashSets when the object changes.
 *
 * 4. @Version: Hibernate reads this on load, increments on UPDATE.
 *    Two concurrent writers → second throws OptimisticLockException.
 *    No SELECT FOR UPDATE needed. Scales horizontally.
 */
@Entity
@Table(
        name = "tasks",
        indexes = {
                @Index(name = "idx_tasks_status",       columnList = "status"),
                @Index(name = "idx_tasks_scheduled_at", columnList = "scheduled_at"),
                @Index(name = "idx_tasks_created_at",   columnList = "created_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode(of = "id")
@ToString(exclude = "payload")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "type", nullable = false, length = 100)
    private String type;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private TaskStatus status;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    // =========================================================
    // FACTORY METHOD — only way to create a valid Task
    // =========================================================

    public static Task create(
            String name,
            String type,
            String payload,
            int priority,
            Instant scheduledAt
    ) {
        validatePriority(priority);

        Task task        = new Task();
        task.name        = name;
        task.type        = type;
        task.payload     = payload;
        task.priority    = priority;
        task.scheduledAt = scheduledAt;
        task.status      = TaskStatus.PENDING;
        task.createdAt   = Instant.now();
        task.updatedAt   = Instant.now();
        return task;
    }

    // =========================================================
    // STATE MACHINE — business methods only, no setStatus()
    // =========================================================

    /** PENDING → RUNNING */
    public void start() {
        requireStatus(TaskStatus.PENDING, "start");
        this.status    = TaskStatus.RUNNING;
        this.startedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** RUNNING → COMPLETED */
    public void complete() {
        requireStatus(TaskStatus.RUNNING, "complete");
        this.status      = TaskStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.updatedAt   = Instant.now();
    }

    /** RUNNING → FAILED (retryable) or RUNNING → DEAD_LETTER (exhausted) */
    public void fail(String errorMessage) {
        requireStatus(TaskStatus.RUNNING, "fail");
        this.retryCount++;
        this.errorMessage = errorMessage;
        this.updatedAt    = Instant.now();

        if (this.retryCount >= this.maxRetries) {
            this.status   = TaskStatus.DEAD_LETTER;
            this.failedAt = Instant.now();
        } else {
            this.status = TaskStatus.FAILED;
        }
    }

    /** FAILED → PENDING (re-queue for retry) */
    public void scheduleRetry() {
        requireStatus(TaskStatus.FAILED, "scheduleRetry");
        this.status    = TaskStatus.PENDING;
        this.updatedAt = Instant.now();
    }

    /** PENDING → CANCELLED */
    public void cancel() {
        requireStatus(TaskStatus.PENDING, "cancel");
        this.status    = TaskStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    // =========================================================
    // QUERY METHODS
    // =========================================================

    public boolean isRetryable() {
        return retryCount < maxRetries;
    }

    public boolean isDue() {
        return !Instant.now().isBefore(scheduledAt);
    }

    // =========================================================
    // PRIVATE GUARDS
    // =========================================================

    private void requireStatus(TaskStatus required, String operation) {
        if (this.status != required) {
            throw new IllegalStateException(
                    "Cannot '%s' task [id=%s] in status [%s]. Required: [%s]"
                            .formatted(operation, this.id, this.status, required)
            );
        }
    }

    private static void validatePriority(int priority) {
        if (priority < 0 || priority > 10) {
            throw new IllegalArgumentException(
                    "Priority must be 0–10, got: " + priority
            );
        }
    }
}