package com.taskscheduler.api.dto;

import com.taskscheduler.domain.model.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO returned to API clients.
 *
 * This is the API contract — changing this breaks clients.
 * The Task entity can change freely (add DB columns, rename fields)
 * without breaking the API as long as this record stays stable.
 *
 * Note: version is exposed so clients can implement optimistic
 * concurrency on their side if needed (e.g. "only cancel if still
 * at version 3, otherwise someone else already modified it").
 */
public record TaskResponse(
        UUID id,
        String name,
        String type,
        String payload,
        TaskStatus status,
        int priority,
        Instant scheduledAt,
        Instant startedAt,
        Instant completedAt,
        Instant failedAt,
        String errorMessage,
        int retryCount,
        int maxRetries,
        Instant createdAt,
        Instant updatedAt,
        long version
) {}