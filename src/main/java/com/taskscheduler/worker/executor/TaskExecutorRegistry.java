package com.taskscheduler.worker.executor;

import com.taskscheduler.domain.model.Task;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry mapping task type strings to TaskExecutor implementations.
 *
 * Spring injects ALL TaskExecutor beans into the list constructor.
 * @PostConstruct builds the lookup map once at startup.
 *
 * Lookup order:
 *   1. Exact match on task.getType() (e.g. "EMAIL_SEND" → EmailSendExecutor)
 *   2. DEFAULT fallback if no specific executor exists
 *   3. Exception if no DEFAULT executor exists either
 *
 * Adding a new executor = create @Component implementing TaskExecutor.
 * Zero registry changes required.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskExecutorRegistry {

    private final List<TaskExecutor> executors;
    private Map<String, TaskExecutor> registry;

    @PostConstruct
    void init() {
        registry = executors.stream()
                .collect(Collectors.toMap(
                        TaskExecutor::supportedType,
                        Function.identity()
                ));
        log.info("TaskExecutorRegistry initialized with {} executor(s): {}",
                registry.size(), registry.keySet());
    }

    public void execute(Task task) throws Exception {
        TaskExecutor executor = registry.getOrDefault(
                task.getType(),
                registry.get(DefaultTaskExecutor.DEFAULT_TYPE)
        );

        if (executor == null) {
            throw new IllegalStateException(
                    "No executor found for task type '%s' and no DEFAULT executor registered"
                            .formatted(task.getType())
            );
        }

        executor.execute(task);
    }
}