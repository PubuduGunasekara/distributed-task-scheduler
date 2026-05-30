package com.taskscheduler.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Request DTO for task creation.
 *
 * Java record = immutable data carrier with auto-generated
 * constructor, getters, equals, hashCode, toString.
 * Perfect for DTOs — they should never change after construction.
 *
 * Bean Validation annotations here enforce the API contract.
 * If a request violates these, Spring returns 400 before your
 * controller method is even called.
 */
public record CreateTaskRequest(

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "type is required")
        String type,

        // payload is optional — null means no parameters
        String payload,

        @Min(value = 0, message = "priority must be between 0 and 10")
        @Max(value = 10, message = "priority must be between 0 and 10")
        int priority,

        @NotNull(message = "scheduledAt is required")
        Instant scheduledAt
) {}