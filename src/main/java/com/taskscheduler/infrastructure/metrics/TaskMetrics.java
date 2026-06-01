package com.taskscheduler.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central metrics recording component.
 *
 * Wraps MeterRegistry with domain-meaningful method names.
 * All meters are tagged with task type for Grafana filtering:
 *   tasks_submitted_total{type="EMAIL_SEND"} 42
 *   tasks_submitted_total{type="REPORT_GEN"} 18
 *
 * NOTE: meter names must avoid OpenMetrics reserved suffixes
 * (_created, _total, _count, _sum, _bucket). A counter named
 * "tasks.created.total" gets both _created and _total stripped
 * by the Prometheus naming convention and collapses to
 * "tasks_total" — hence "submitted" instead of "created".
 *
 * Micrometer caches meters by name+tags — calling register()
 * multiple times is safe and returns the same instance.
 * We use a local ConcurrentMap for timer instances since Timer
 * objects are heavier than counters.
 */
@Component
@RequiredArgsConstructor
public class TaskMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    // =========================================================
    // TASK LIFECYCLE COUNTERS
    // =========================================================

    public void recordTaskCreated(String taskType) {
        counter("tasks.submitted.total", "Total tasks submitted", taskType).increment();
    }

    public void recordTaskCompleted(String taskType) {
        counter("tasks.completed.total", "Tasks completed successfully", taskType).increment();
    }

    public void recordTaskFailed(String taskType) {
        counter("tasks.failed.total", "Task execution failures eligible for retry", taskType).increment();
    }

    public void recordTaskDeadLettered(String taskType) {
        counter("tasks.dead_lettered.total", "Tasks that exhausted all retries", taskType).increment();
    }

    // =========================================================
    // WORKER METRICS
    // =========================================================

    public void recordLockAcquired() {
        Counter.builder("worker.lock.acquired.total")
                .description("Distributed locks successfully acquired")
                .register(registry)
                .increment();
    }

    public void recordLockRejected() {
        Counter.builder("worker.lock.rejected.total")
                .description("Distributed lock acquisition failures — task already claimed")
                .register(registry)
                .increment();
    }

    public void recordRetryScheduled() {
        Counter.builder("tasks.retry.scheduled.total")
                .description("Tasks re-queued after backoff period")
                .register(registry)
                .increment();
    }

    // =========================================================
    // EXECUTION TIMER
    // =========================================================

    /**
     * Returns a Timer for measuring task execution duration.
     * Tagged by task type so you can compare EMAIL_SEND vs REPORT_GEN latency.
     * Publishes p50/p95/p99 percentiles to Prometheus.
     */
    public Timer executionTimer(String taskType) {
        return timerCache.computeIfAbsent(taskType, type ->
                Timer.builder("tasks.execution.duration")
                        .description("End-to-end task execution duration")
                        .tag("type", type)
                        .publishPercentileHistogram(true)
                        .register(registry)
        );
    }

    // =========================================================
    // PRIVATE
    // =========================================================

    private Counter counter(String name, String description, String taskType) {
        return Counter.builder(name)
                .description(description)
                .tag("type", taskType)
                .register(registry);
    }
}