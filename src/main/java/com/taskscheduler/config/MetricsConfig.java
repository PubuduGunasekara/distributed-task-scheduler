package com.taskscheduler.config;

import com.taskscheduler.domain.model.TaskStatus;
import com.taskscheduler.domain.repository.TaskRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

/**
 * Registers Gauge metrics backed by live database queries.
 *
 * Gauges represent current state (not cumulative counts).
 * Prometheus scrapes these every 10 seconds — each scrape
 * triggers a COUNT query against PostgreSQL.
 *
 * Why Gauge instead of Counter for queue depth?
 *   Counters only go up. Queue depth goes up AND down.
 *   A gauge reflects the actual current value at scrape time.
 *
 * The repository reference is passed as a strong reference
 * (not lambda capture) so Micrometer's weak-ref GC safety
 * applies correctly — the gauge won't prevent GC.
 */
@Configuration
@RequiredArgsConstructor
public class MetricsConfig {

    private final MeterRegistry  meterRegistry;
    private final TaskRepository taskRepository;

    @PostConstruct
    void registerGauges() {
        gauge("tasks.pending.count",
                "Tasks waiting to be picked up by a worker",
                TaskStatus.PENDING);

        gauge("tasks.running.count",
                "Tasks currently executing on a worker",
                TaskStatus.RUNNING);

        gauge("tasks.failed.count",
                "Tasks in FAILED state awaiting retry scheduler",
                TaskStatus.FAILED);

        gauge("tasks.dead_lettered.count",
                "Tasks that exhausted all retries — require manual intervention",
                TaskStatus.DEAD_LETTER);
    }

    private void gauge(String name, String description, TaskStatus status) {
        Gauge.builder(name, taskRepository,
                        repo -> repo.countByStatus(status))
                .description(description)
                .tag("status", status.name())
                .register(meterRegistry);
    }
}