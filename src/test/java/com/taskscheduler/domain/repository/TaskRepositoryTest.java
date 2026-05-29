package com.taskscheduler.domain.repository;

import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests using a real PostgreSQL database via Testcontainers.
 *
 * @DataJpaTest — loads only the JPA slice (entities, repositories, Flyway).
 *   No web layer, no Kafka, no Redis. Starts in ~3s vs ~15s for full context.
 *
 * @AutoConfigureTestDatabase(replace = NONE) — prevents Spring from
 *   swapping our datasource for in-memory H2. We want real PostgreSQL
 *   because H2 doesn't support TIMESTAMPTZ, partial indexes, or
 *   PostgreSQL-specific constraints.
 *
 * Static @Container — one container shared across all tests in this class.
 *   Starting a new container per test would add ~3s overhead per test.
 *
 * @DynamicPropertySource — wires the container's JDBC URL into Spring
 *   after the container starts, before the application context loads.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("TaskRepository")
class TaskRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("taskscheduler_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
    }

    @Nested
    @DisplayName("save()")
    class Save {

        @Test
        @DisplayName("should persist task and assign UUID")
        void shouldPersistAndAssignId() {
            Task saved = taskRepository.save(buildTask(Instant.now()));
            assertThat(saved.getId()).isNotNull();
        }

        @Test
        @DisplayName("should initialize version to 0")
        void shouldInitializeVersionToZero() {
            Task saved = taskRepository.save(buildTask(Instant.now()));
            assertThat(saved.getVersion()).isZero();
        }

        @Test
        @DisplayName("should increment version on update — optimistic locking")
        void shouldIncrementVersionOnUpdate() {
            Task saved = taskRepository.saveAndFlush(buildTask(Instant.now()));
            assertThat(saved.getVersion()).isZero();

            saved.start();
            Task updated = taskRepository.saveAndFlush(saved);

            assertThat(updated.getVersion()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("findDueTasks()")
    class FindDueTasks {

        @Test
        @DisplayName("should return PENDING tasks past their scheduledAt")
        void shouldReturnDuePendingTasks() {
            taskRepository.save(buildTask(Instant.now().minusSeconds(60)));

            List<Task> result = taskRepository.findDueTasks(
                    TaskStatus.PENDING, Instant.now(), PageRequest.of(0, 10));

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("should NOT return future tasks")
        void shouldExcludeFutureTasks() {
            taskRepository.save(buildTask(Instant.now().plusSeconds(3600)));

            List<Task> result = taskRepository.findDueTasks(
                    TaskStatus.PENDING, Instant.now(), PageRequest.of(0, 10));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should order by priority descending")
        void shouldOrderByPriorityDesc() {
            Instant past = Instant.now().minusSeconds(60);
            taskRepository.save(Task.create("low",  "T", "{}", 1, past));
            taskRepository.save(Task.create("high", "T", "{}", 9, past));
            taskRepository.save(Task.create("mid",  "T", "{}", 5, past));

            List<Task> result = taskRepository.findDueTasks(
                    TaskStatus.PENDING, Instant.now(), PageRequest.of(0, 10));

            assertThat(result)
                    .extracting(Task::getName)
                    .containsExactly("high", "mid", "low");
        }

        @Test
        @DisplayName("should respect page size limit")
        void shouldRespectPageLimit() {
            Instant past = Instant.now().minusSeconds(60);
            for (int i = 0; i < 5; i++) {
                taskRepository.save(Task.create("task-" + i, "T", "{}", 1, past));
            }

            List<Task> result = taskRepository.findDueTasks(
                    TaskStatus.PENDING, Instant.now(), PageRequest.of(0, 3));

            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("should NOT return RUNNING tasks")
        void shouldExcludeRunningTasks() {
            Task running = buildTask(Instant.now().minusSeconds(60));
            running.start();
            taskRepository.save(running);

            List<Task> result = taskRepository.findDueTasks(
                    TaskStatus.PENDING, Instant.now(), PageRequest.of(0, 10));

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("countByStatus()")
    class CountByStatus {

        @Test
        @DisplayName("should return accurate count per status")
        void shouldCountAccurately() {
            taskRepository.save(buildTask(Instant.now()));
            taskRepository.save(buildTask(Instant.now()));

            Task running = buildTask(Instant.now());
            running.start();
            taskRepository.save(running);

            assertThat(taskRepository.countByStatus(TaskStatus.PENDING)).isEqualTo(2);
            assertThat(taskRepository.countByStatus(TaskStatus.RUNNING)).isEqualTo(1);
        }
    }

    private Task buildTask(Instant scheduledAt) {
        return Task.create("test-task", "EMAIL", "{}", 5, scheduledAt);
    }
}