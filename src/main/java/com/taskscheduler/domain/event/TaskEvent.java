package com.taskscheduler.domain.event;

import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record representing a task lifecycle event.
 *
 * Design decisions:
 *
 * 1. eventId (UUID string): unique per event, not per task.
 *    Used for consumer-side idempotency — if the same event
 *    is delivered twice (at-least-once), consumers deduplicate
 *    using eventId, not taskId.
 *
 * 2. occurredAt: when the state change happened on the producer.
 *    Consumers should use this for ordering, not Kafka offset time,
 *    because broker clock ≠ producer clock.
 *
 * 3. All task fields are copied into the event (denormalization).
 *    Consumers get all they need without calling back to the API.
 *    This is the "fat event" pattern — preferred when consumers
 *    need context without additional round-trips.
 */
public record TaskEvent(
        String eventId,
        TaskEventType eventType,
        UUID taskId,
        String taskName,
        String taskType,
        TaskStatus taskStatus,
        int priority,
        Instant scheduledAt,
        Instant occurredAt,
        int retryCount
) {
    /**
     * Factory method — builds an event from a Task entity.
     * Keeps the construction logic in one place.
     */
    public static TaskEvent from(Task task, TaskEventType eventType) {
        return new TaskEvent(
                UUID.randomUUID().toString(),
                eventType,
                task.getId(),
                task.getName(),
                task.getType(),
                task.getStatus(),
                task.getPriority(),
                task.getScheduledAt(),
                Instant.now(),
                task.getRetryCount()
        );
    }
}