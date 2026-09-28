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

    /** Used by the retry scheduler to find FAILED tasks waiting on backoff. */
    List<Task> findByStatusOrderByUpdatedAtAsc(TaskStatus status);

    /** Used by metrics and Grafana dashboards. */
    @Query("SELECT COUNT(t) FROM Task t WHERE t.status = :status")
    long countByStatus(@Param("status") TaskStatus status);

    /**
     * Used by the stale-task recovery scheduler.
     * Finds RUNNING tasks whose startedAt predates the execution timeout —
     * the worker that claimed them is presumed dead.
     * Oldest first, so the longest-stuck tasks recover first.
     */
    @Query("""
            SELECT t FROM Task t
            WHERE t.status = :status
              AND t.startedAt < :cutoff
            ORDER BY t.startedAt ASC
            """)
    List<Task> findStaleRunningTasks(
            @Param("status") TaskStatus status,
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    /**
     * Used by the stale-task recovery scheduler's orphan sweep.
     * Finds PENDING tasks that WERE dispatched (dispatchedAt set) but not
     * since before the orphan threshold — their TASK_CREATED event was
     * likely lost after publishing. Tasks never dispatched at all are
     * findUndispatchedDueTasks()'s job, not this one's.
     */
    @Query("""
            SELECT t FROM Task t
            WHERE t.status = :status
              AND t.dispatchedAt IS NOT NULL
              AND t.dispatchedAt < :cutoff
            ORDER BY t.dispatchedAt ASC
            """)
    List<Task> findOrphanedPendingTasks(
            @Param("status") TaskStatus status,
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    /**
     * Used by DueTaskDispatchScheduler — the primary dispatch mechanism.
     * Finds PENDING tasks that are due and have never been dispatched
     * (dispatchedAt IS NULL), whether created already-due or created for
     * the future and now due.
     */
    @Query("""
            SELECT t FROM Task t
            WHERE t.status = :status
              AND t.dispatchedAt IS NULL
              AND t.scheduledAt <= :now
            ORDER BY t.scheduledAt ASC
            """)
    List<Task> findUndispatchedDueTasks(
            @Param("status") TaskStatus status,
            @Param("now") Instant now,
            Pageable pageable
    );
}