-- ============================================================
-- V3: Indexes for the stale-task recovery scheduler
-- ============================================================
-- StaleTaskRecoveryScheduler runs two queries every poll cycle:
--   1. RUNNING tasks whose started_at predates the execution timeout
--   2. PENDING tasks whose updated_at predates the orphan threshold
-- idx_tasks_status (status only) and idx_tasks_pending_due (scheduled_at,
-- priority; PENDING only) don't cover either access pattern, so both
-- queries would fall back to scanning every row in that status.
-- ============================================================

CREATE INDEX idx_tasks_running_started_at
    ON tasks (started_at)
    WHERE status = 'RUNNING';

CREATE INDEX idx_tasks_pending_updated_at
    ON tasks (updated_at)
    WHERE status = 'PENDING';
