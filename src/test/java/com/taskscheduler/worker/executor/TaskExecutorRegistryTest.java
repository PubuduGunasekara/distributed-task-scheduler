package com.taskscheduler.worker.executor;

import com.taskscheduler.domain.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaskExecutorRegistry")
class TaskExecutorRegistryTest {

    private TaskExecutorRegistry registry;
    private DefaultTaskExecutor  defaultExecutor;

    @BeforeEach
    void setUp() {
        defaultExecutor = new DefaultTaskExecutor();
        registry        = new TaskExecutorRegistry(List.of(defaultExecutor));
        registry.init();
    }

    @Test
    @DisplayName("should initialize with registered executors")
    void shouldInitializeWithExecutors() {
        Task task = mock(Task.class);
        when(task.getType()).thenReturn(DefaultTaskExecutor.DEFAULT_TYPE);

        assertThatCode(() -> registry.execute(task)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should fall back to DEFAULT executor for unknown task type")
    void shouldFallbackToDefault() throws Exception {
        Task task = mock(Task.class);
        when(task.getType()).thenReturn("UNKNOWN_TYPE");

        // Should not throw — falls back to DEFAULT executor
        assertThatCode(() -> registry.execute(task)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should throw when no executor and no DEFAULT registered")
    void shouldThrowWhenNoExecutorFound() {
        // Registry with NO executors — no DEFAULT fallback
        TaskExecutorRegistry emptyRegistry = new TaskExecutorRegistry(List.of());
        emptyRegistry.init();

        Task task = mock(Task.class);
        when(task.getType()).thenReturn("EMAIL_SEND");

        assertThatThrownBy(() -> emptyRegistry.execute(task))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No executor found");
    }
}