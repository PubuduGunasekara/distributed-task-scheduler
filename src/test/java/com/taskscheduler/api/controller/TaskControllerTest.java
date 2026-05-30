package com.taskscheduler.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskscheduler.api.dto.CreateTaskRequest;
import com.taskscheduler.api.dto.TaskResponse;
import com.taskscheduler.api.exception.GlobalExceptionHandler;
import com.taskscheduler.api.mapper.TaskMapper;
import com.taskscheduler.domain.exception.TaskNotFoundException;
import com.taskscheduler.domain.model.TaskStatus;
import com.taskscheduler.domain.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web layer tests using @WebMvcTest.
 *
 * @WebMvcTest(TaskController.class) spins up ONLY:
 *   - TaskController
 *   - Spring MVC infrastructure (serialization, validation, routing)
 *   - @RestControllerAdvice beans in the app (GlobalExceptionHandler)
 *
 * @MockBean replaces real beans with Mockito mocks in the Spring context.
 * TaskService and TaskMapper have no real implementations loaded here.
 *
 * What these tests verify:
 *   - HTTP method and URL routing is correct
 *   - Request body is deserialized correctly
 *   - @Valid validation fires and returns 400 on bad input
 *   - Correct HTTP status codes are returned
 *   - Response JSON structure matches expectations
 *   - Exceptions are translated to correct HTTP responses
 *
 * What these tests do NOT verify:
 *   - Business logic (that's TaskServiceTest)
 *   - Database queries (that's TaskRepositoryTest)
 */
@WebMvcTest(TaskController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("TaskController")
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TaskService taskService;

    @MockBean
    private TaskMapper taskMapper;

    // =========================================================
    // POST /api/v1/tasks
    // =========================================================

    @Nested
    @DisplayName("POST /api/v1/tasks")
    class CreateTask {

        @Test
        @DisplayName("should return 201 with task response when request is valid")
        void shouldReturn201WhenValid() throws Exception {
            CreateTaskRequest request = new CreateTaskRequest(
                    "send-email", "EMAIL_SEND", "{}", 5,
                    Instant.now().plusSeconds(60)
            );
            TaskResponse response = buildTaskResponse(TaskStatus.PENDING);

            when(taskService.createTask(any(), any(), any(), anyInt(), any()))
                    .thenReturn(null); // TaskMapper handles the conversion
            when(taskMapper.toResponse(any())).thenReturn(response);

            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(response.id().toString()))
                    .andExpect(jsonPath("$.name").value("send-email"))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        @DisplayName("should return 400 when name is blank")
        void shouldReturn400WhenNameIsBlank() throws Exception {
            CreateTaskRequest request = new CreateTaskRequest(
                    "", "EMAIL_SEND", "{}", 5, Instant.now().plusSeconds(60)
            );

            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Validation Failed"))
                    .andExpect(jsonPath("$.errors.name").value("name is required"));
        }

        @Test
        @DisplayName("should return 400 when type is blank")
        void shouldReturn400WhenTypeIsBlank() throws Exception {
            CreateTaskRequest request = new CreateTaskRequest(
                    "send-email", "", "{}", 5, Instant.now().plusSeconds(60)
            );

            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.type").value("type is required"));
        }

        @Test
        @DisplayName("should return 400 when priority exceeds 10")
        void shouldReturn400WhenPriorityTooHigh() throws Exception {
            CreateTaskRequest request = new CreateTaskRequest(
                    "task", "TYPE", "{}", 11, Instant.now().plusSeconds(60)
            );

            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.priority").exists());
        }

        @Test
        @DisplayName("should return 400 when scheduledAt is null")
        void shouldReturn400WhenScheduledAtIsNull() throws Exception {
            // Build JSON manually to send null scheduledAt
            String json = """
                    {
                      "name": "task",
                      "type": "TYPE",
                      "priority": 5,
                      "scheduledAt": null
                    }
                    """;

            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.scheduledAt").exists());
        }

        @Test
        @DisplayName("should return 400 when body is missing entirely")
        void shouldReturn400WhenBodyMissing() throws Exception {
            mockMvc.perform(post("/api/v1/tasks")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }

    // =========================================================
    // GET /api/v1/tasks/{id}
    // =========================================================

    @Nested
    @DisplayName("GET /api/v1/tasks/{id}")
    class GetTask {

        @Test
        @DisplayName("should return 200 with task when found")
        void shouldReturn200WhenFound() throws Exception {
            UUID id       = UUID.randomUUID();
            TaskResponse response = buildTaskResponse(TaskStatus.PENDING);

            when(taskService.getTask(id)).thenReturn(null);
            when(taskMapper.toResponse(any())).thenReturn(response);

            mockMvc.perform(get("/api/v1/tasks/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(response.id().toString()))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        @Test
        @DisplayName("should return 404 with ProblemDetail when task not found")
        void shouldReturn404WhenNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(taskService.getTask(id))
                    .thenThrow(new TaskNotFoundException(id));

            mockMvc.perform(get("/api/v1/tasks/{id}", id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Task Not Found"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.taskId").value(id.toString()));
        }

        @Test
        @DisplayName("should return 400 when ID is not a valid UUID")
        void shouldReturn400WhenIdIsNotUuid() throws Exception {
            mockMvc.perform(get("/api/v1/tasks/not-a-uuid"))
                    .andExpect(status().isBadRequest());
        }
    }

    // =========================================================
    // GET /api/v1/tasks/due
    // =========================================================

    @Nested
    @DisplayName("GET /api/v1/tasks/due")
    class GetDueTasks {

        @Test
        @DisplayName("should return 200 with list of due tasks")
        void shouldReturn200WithList() throws Exception {
            TaskResponse response = buildTaskResponse(TaskStatus.PENDING);
            when(taskService.getDueTasks(10)).thenReturn(List.of());
            when(taskMapper.toResponseList(any())).thenReturn(List.of(response));

            mockMvc.perform(get("/api/v1/tasks/due"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$[0].id").value(response.id().toString()));
        }

        @Test
        @DisplayName("should use custom limit when provided")
        void shouldUseCustomLimit() throws Exception {
            when(taskService.getDueTasks(25)).thenReturn(List.of());
            when(taskMapper.toResponseList(any())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/tasks/due")
                            .param("limit", "25"))
                    .andExpect(status().isOk());

            verify(taskService).getDueTasks(25);
        }

        @Test
        @DisplayName("should return 400 when limit is 0")
        void shouldReturn400WhenLimitIsZero() throws Exception {
            mockMvc.perform(get("/api/v1/tasks/due")
                            .param("limit", "0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when limit exceeds 100")
        void shouldReturn400WhenLimitExceeds100() throws Exception {
            mockMvc.perform(get("/api/v1/tasks/due")
                            .param("limit", "101"))
                    .andExpect(status().isBadRequest());
        }
    }

    // =========================================================
    // PATCH /api/v1/tasks/{id}/cancel
    // =========================================================

    @Nested
    @DisplayName("PATCH /api/v1/tasks/{id}/cancel")
    class CancelTask {

        @Test
        @DisplayName("should return 200 with cancelled task")
        void shouldReturn200WhenCancelled() throws Exception {
            UUID id       = UUID.randomUUID();
            TaskResponse response = buildTaskResponse(TaskStatus.CANCELLED);

            when(taskService.cancelTask(id)).thenReturn(null);
            when(taskMapper.toResponse(any())).thenReturn(response);

            mockMvc.perform(patch("/api/v1/tasks/{id}/cancel", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("should return 409 when task is in wrong state")
        void shouldReturn409WhenInvalidStateTransition() throws Exception {
            UUID id = UUID.randomUUID();
            when(taskService.cancelTask(id))
                    .thenThrow(new IllegalStateException(
                            "Cannot 'cancel' task in status [RUNNING]"));

            mockMvc.perform(patch("/api/v1/tasks/{id}/cancel", id))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title")
                            .value("Invalid Task State Transition"))
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @DisplayName("should return 404 when task does not exist")
        void shouldReturn404WhenNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(taskService.cancelTask(id))
                    .thenThrow(new TaskNotFoundException(id));

            mockMvc.perform(patch("/api/v1/tasks/{id}/cancel", id))
                    .andExpect(status().isNotFound());
        }
    }

    // =========================================================
    // HELPER
    // =========================================================

    private TaskResponse buildTaskResponse(TaskStatus status) {
        return new TaskResponse(
                UUID.randomUUID(),
                "send-email",
                "EMAIL_SEND",
                "{}",
                status,
                5,
                Instant.now().plusSeconds(60),
                null, null, null, null,
                0, 3,
                Instant.now(),
                Instant.now(),
                0L
        );
    }
}