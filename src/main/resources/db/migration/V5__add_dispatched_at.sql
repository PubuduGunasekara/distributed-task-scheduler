-- ============================================================
-- V5: Track when a task's TASK_CREATED event was actually dispatched
-- ============================================================
-- createTask() previously published TASK_CREATED unconditionally,
-- ignoring scheduledAt entirely — a task scheduled for the future ran
-- immediately. dispatched_at distinguishes "still waiting for
-- scheduledAt, never published" (dispatched_at IS NULL) from
-- "published, but maybe lost" (dispatched_at IS NOT NULL and old),
-- so DueTaskDispatchScheduler and StaleTaskRecoveryScheduler's orphan
-- sweep can each query for exactly the case they handle instead of
-- both guessing off updated_at.
-- ============================================================

ALTER TABLE tasks ADD COLUMN dispatched_at TIMESTAMPTZ;

-- Replaces idx_tasks_pending_updated_at (V3): the orphan-recovery query
-- now filters on dispatched_at, not updated_at.
DROP INDEX idx_tasks_pending_updated_at;

-- Serves both DueTaskDispatchScheduler's "never dispatched" query
-- (dispatched_at IS NULL) and the orphan sweep's "dispatched but stale"
-- query (dispatched_at < cutoff) — a partial index still covers NULLs.
CREATE INDEX idx_tasks_pending_dispatched_at
    ON tasks (dispatched_at)
    WHERE status = 'PENDING';
