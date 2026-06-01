package com.taskscheduler.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TaskMetrics using SimpleMeterRegistry.
 *
 * SimpleMeterRegistry is an in-memory registry — no Spring context,
 * no Prometheus endpoint, runs in milliseconds.
 * It's the standard way to test custom Micrometer instrumentation.
 */
@DisplayName("TaskMetrics")
class TaskMetricsTest {

    private SimpleMeterRegistry registry;
    private TaskMetrics         metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics  = new TaskMetrics(registry);
    }

    @Test
    @DisplayName("recordTaskCreated should increment counter with type tag")
    void shouldIncrementCreatedCounter() {
        metrics.recordTaskCreated("EMAIL_SEND");
        metrics.recordTaskCreated("EMAIL_SEND");
        metrics.recordTaskCreated("REPORT_GEN");

        Counter emailCounter = registry.find("tasks.submitted.total")
                .tag("type", "EMAIL_SEND").counter();
        Counter reportCounter = registry.find("tasks.submitted.total")
                .tag("type", "REPORT_GEN").counter();

        assertThat(emailCounter).isNotNull();
        assertThat(emailCounter.count()).isEqualTo(2.0);
        assertThat(reportCounter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordTaskCompleted should increment completed counter")
    void shouldIncrementCompletedCounter() {
        metrics.recordTaskCompleted("EMAIL_SEND");

        Counter c = registry.find("tasks.completed.total")
                .tag("type", "EMAIL_SEND").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordTaskFailed should increment failed counter")
    void shouldIncrementFailedCounter() {
        metrics.recordTaskFailed("EMAIL_SEND");

        Counter c = registry.find("tasks.failed.total")
                .tag("type", "EMAIL_SEND").counter();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordTaskDeadLettered should increment DLQ counter")
    void shouldIncrementDeadLetteredCounter() {
        metrics.recordTaskDeadLettered("EMAIL_SEND");

        Counter c = registry.find("tasks.dead_lettered.total")
                .tag("type", "EMAIL_SEND").counter();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordLockAcquired and recordLockRejected should track separately")
    void shouldTrackLockMetricsSeparately() {
        metrics.recordLockAcquired();
        metrics.recordLockAcquired();
        metrics.recordLockRejected();

        assertThat(registry.find("worker.lock.acquired.total").counter().count()).isEqualTo(2.0);
        assertThat(registry.find("worker.lock.rejected.total").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("executionTimer should record duration with type tag")
    void shouldRecordExecutionDuration() throws Exception {
        Timer timer = metrics.executionTimer("EMAIL_SEND");
        timer.record(Duration.ofMillis(50));
        timer.record(Duration.ofMillis(150));

        Timer found = registry.find("tasks.execution.duration")
                .tag("type", "EMAIL_SEND").timer();
        assertThat(found).isNotNull();
        assertThat(found.count()).isEqualTo(2);
        assertThat(found.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(200.0);
    }

    @Test
    @DisplayName("executionTimer should return same instance for same type")
    void shouldCacheTimerByType() {
        Timer t1 = metrics.executionTimer("EMAIL_SEND");
        Timer t2 = metrics.executionTimer("EMAIL_SEND");
        assertThat(t1).isSameAs(t2);
    }

    /**
     * Regression guard for the meter-naming bug.
     *
     * SimpleMeterRegistry does NOT apply Prometheus naming conventions,
     * so the other tests can't catch suffix mangling. This one scrapes a
     * real PrometheusMeterRegistry and asserts the EXPORTED name.
     *
     * A counter named "tasks.created.total" collapses to "tasks_total"
     * because the Prometheus naming convention strips the reserved
     * OpenMetrics suffixes _created and _total. The submitted counter
     * must export as "tasks_submitted_total" and must NOT produce a bare
     * "tasks_total" series.
     */
    @Test
    @DisplayName("recordTaskCreated should export as tasks_submitted_total in Prometheus")
    void shouldExportSubmittedCounterWithCorrectPrometheusName() {
        PrometheusMeterRegistry prometheus =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        TaskMetrics prometheusMetrics = new TaskMetrics(prometheus);

        prometheusMetrics.recordTaskCreated("EMAIL_SEND");

        String scrape = prometheus.scrape();
        assertThat(scrape).contains("tasks_submitted_total{");
        assertThat(scrape).doesNotContain("tasks_total{");
    }
}