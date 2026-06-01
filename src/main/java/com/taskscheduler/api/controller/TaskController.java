package com.taskscheduler.api.controller;

import com.taskscheduler.api.dto.CreateTaskRequest;
import com.taskscheduler.api.dto.TaskResponse;
import com.taskscheduler.api.mapper.TaskMapper;
import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.service.TaskService;
import com.taskscheduler.infrastructure.metrics.TaskMetrics;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for task management.
 *
 * @Validated (class level) — enables constraint validation on
 *   @RequestParam and @PathVariable parameters (the @Min/@Max on limit).
 *   @Valid (method parameter level) — validates @RequestBody objects.
 *   These are different annotations with different scopes.
 *
 * Controllers are deliberately thin:
 *   1. Validate input
 *   2. Call service
 *   3. Map result to DTO
 *   4. Return response
 * No business logic lives here.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskMapper  taskMapper;
    private final TaskMetrics taskMetrics;

    /**
     * Submit a new task.
     * Returns 201 Created — semantically correct for resource creation.
     * The response body contains the created task including its generated ID.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskResponse createTask(@Valid @RequestBody CreateTaskRequest request) {
        log.debug("Create task request: name={} type={}", request.name(), request.type());

        Task saved = taskService.createTask(       // ← capture in variable first
                request.name(),
                request.type(),
                request.payload(),
                request.priority(),
                request.scheduledAt()
        );
        taskMetrics.recordTaskCreated(saved.getType());

        return taskMapper.toResponse(saved);
    }

    /**
     * Retrieve a task by its ID.
     * Returns 200 OK or 404 if not found (handled by GlobalExceptionHandler).
     */
    @GetMapping("/{id}")
    public TaskResponse getTask(@PathVariable UUID id) {
        return taskMapper.toResponse(taskService.getTask(id));
    }

    /**
     * Retrieve tasks that are due for execution.
     * Used by workers to poll for work — also useful for monitoring dashboards.
     *
     * @param limit max tasks to return (1–100, default 10)
     */
    @GetMapping("/due")
    public List<TaskResponse> getDueTasks(
            @RequestParam(defaultValue = "10")
            @Min(value = 1,   message = "limit must be at least 1")
            @Max(value = 100, message = "limit must be at most 100")
            int limit
    ) {
        return taskMapper.toResponseList(taskService.getDueTasks(limit));
    }

    /**
     * Cancel a pending task.
     * Returns 409 Conflict if the task is already RUNNING or terminal
     * (handled by GlobalExceptionHandler catching IllegalStateException).
     */
    @PatchMapping("/{id}/cancel")
    public TaskResponse cancelTask(@PathVariable UUID id) {
        return taskMapper.toResponse(taskService.cancelTask(id));
    }
}