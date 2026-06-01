package com.taskscheduler.api.interceptor;

import com.taskscheduler.api.controller.TaskController;
import com.taskscheduler.api.dto.TaskResponse;
import com.taskscheduler.api.exception.GlobalExceptionHandler;
import com.taskscheduler.api.mapper.TaskMapper;
import com.taskscheduler.config.RateLimitConfig;
import com.taskscheduler.domain.port.RateLimitResult;
import com.taskscheduler.domain.port.RateLimiterPort;
import com.taskscheduler.domain.service.TaskService;
import com.taskscheduler.infrastructure.metrics.TaskMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Tests the rate limiting layer in isolation.
 *
 * @WebMvcTest loads the full web layer including interceptors registered
 * via WebMvcConfigurer. RateLimitConfig is imported to register the
 * interceptor, and RateLimiterPort is mocked to control token outcomes.
 *
 * These tests verify HTTP contract — status codes, response headers,
 * and body format — not the Redis implementation details.
 */
@WebMvcTest(TaskController.class)
@Import({GlobalExceptionHandler.class, RateLimitConfig.class, RateLimitInterceptor.class})
@TestPropertySource(properties = {
        "rate-limit.enabled=true",
        "rate-limit.requests-per-window=5",
        "rate-limit.window-seconds=60"
})
@DisplayName("RateLimitInterceptor")
class RateLimitInterceptorTest {

    @Autowired   private MockMvc         mockMvc;
    @Autowired   private ObjectMapper    objectMapper;
    @MockitoBean private TaskService     taskService;
    @MockitoBean private TaskMapper      taskMapper;
    @MockitoBean private TaskMetrics     taskMetrics;
    @MockitoBean private RateLimiterPort rateLimiterPort;

    @TestConfiguration
    static class MetricsConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private static final String VALID_REQUEST = """
            {
              "name": "test-task",
              "type": "EMAIL_SEND",
              "payload": "{}",
              "priority": 5,
              "scheduledAt": "2026-06-01T10:00:00Z"
            }
            """;

    @Test
    @DisplayName("should allow request when tokens are available")
    void shouldAllowWhenTokensAvailable() throws Exception {
        // stub rate limiter
        when(rateLimiterPort.tryConsume(anyString()))
                .thenReturn(RateLimitResult.allowed(4, 5, 60));

        // stub controller dependencies — needed because interceptor passes
        // the request through to the controller which calls taskService
        Task mockTask = mock(Task.class);
        when(mockTask.getType()).thenReturn("EMAIL_SEND");
        when(taskService.createTask(any(), any(), any(), anyInt(), any()))
                .thenReturn(mockTask);
        when(taskMapper.toResponse(any()))
                .thenReturn(buildTaskResponse(TaskStatus.PENDING));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string("X-RateLimit-Limit",     "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "4"));
    }

    @Test
    @DisplayName("should return 429 when rate limit is exceeded")
    void shouldReturn429WhenRateLimitExceeded() throws Exception {
        when(rateLimiterPort.tryConsume(anyString()))
                .thenReturn(RateLimitResult.denied(5, 60));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit",     "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().string("Retry-After",           "60"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"));
    }

    @Test
    @DisplayName("should set rate limit headers on allowed requests")
    void shouldSetRateLimitHeaders() throws Exception {
        when(rateLimiterPort.tryConsume(anyString()))
                .thenReturn(RateLimitResult.allowed(1, 5, 60));

        Task mockTask = mock(Task.class);
        when(mockTask.getType()).thenReturn("EMAIL_SEND");
        when(taskService.createTask(any(), any(), any(), anyInt(), any()))
                .thenReturn(mockTask);
        when(taskMapper.toResponse(any()))
                .thenReturn(buildTaskResponse(TaskStatus.PENDING));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(header().string("X-RateLimit-Limit",     "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "1"));
    }

    @Test
    @DisplayName("should not rate limit paths outside /api/v1/tasks (e.g. actuator)")
    void shouldNotRateLimitActuator() throws Exception {
        // The interceptor is registered only for /api/v1/tasks(/**), so a request
        // to a non-matching path must NOT consult the rate limiter.
        //
        // Note: this is a @WebMvcTest(TaskController.class) slice, so /actuator/health
        // is not even mapped here — it returns 404. That's fine; the assertion that
        // matters is that the rate limiter was never invoked for this path.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(rateLimiterPort);
    }

    // =========================================================
    // HELPER
    // =========================================================

    private TaskResponse buildTaskResponse(TaskStatus status) {
        return new TaskResponse(
                UUID.randomUUID(), "send-email", "EMAIL_SEND", "{}",
                status, 5, Instant.now().plusSeconds(60),
                null, null, null, null, 0, 3,
                Instant.now(), Instant.now(), 0L
        );
    }
}