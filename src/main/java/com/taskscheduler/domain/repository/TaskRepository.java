package com.taskscheduler.domain.repository;

import com.taskscheduler.domain.model.Task;
import com.taskscheduler.domain.model.TaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    /**
     * Hot path — worker polls this to find work.
     * Returns PENDING tasks whose scheduled time has passed,
     * ordered by priority (highest first), then age (oldest first).
     * Pageable limits how many tasks one poll cycle can claim.
     */
    @Query("""
            SELECT t FROM Task t
            WHERE t.status = :status
              AND t.scheduledAt <= :now
            ORDER BY t.priority DESC, t.scheduledAt ASC
            """)
    List<Task> findDueTasks(
            @Param("status") TaskStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );

    /** Used by retry scheduler (Milestone 6). */
    List<Task> findByStatusOrderByUpdatedAtAsc(TaskStatus status);

    /** Used by metrics and Grafana dashboards (Milestone 7). */
    @Query("SELECT COUNT(t) FROM Task t WHERE t.status = :status")
    long countByStatus(@Param("status") TaskStatus status);
}