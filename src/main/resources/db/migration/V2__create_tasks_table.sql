-- ============================================================
-- V2: Task registry table
-- ============================================================
-- Key decisions:
--   UUID primary key  → no sequential ID guessing, safe to expose in APIs
--   TIMESTAMPTZ       → timezone-aware; "9am" means nothing without timezone
--   version column    → optimistic locking (Hibernate increments on UPDATE)
--   Partial index     → only indexes PENDING rows, stays small as tasks complete
-- ============================================================

CREATE TABLE tasks (
                       id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
                       name            VARCHAR(255)    NOT NULL,
                       type            VARCHAR(100)    NOT NULL,
                       payload         TEXT,
                       status          VARCHAR(50)     NOT NULL    DEFAULT 'PENDING',
                       priority        INTEGER         NOT NULL    DEFAULT 0,
                       scheduled_at    TIMESTAMPTZ     NOT NULL,
                       started_at      TIMESTAMPTZ,
                       completed_at    TIMESTAMPTZ,
                       failed_at       TIMESTAMPTZ,
                       error_message   TEXT,
                       retry_count     INTEGER         NOT NULL    DEFAULT 0,
                       max_retries     INTEGER         NOT NULL    DEFAULT 3,
                       created_at      TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),
                       updated_at      TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),
                       version         BIGINT          NOT NULL    DEFAULT 0,

                       CONSTRAINT chk_tasks_priority    CHECK (priority BETWEEN 0 AND 10),
                       CONSTRAINT chk_tasks_retry_count CHECK (retry_count >= 0),
                       CONSTRAINT chk_tasks_max_retries CHECK (max_retries > 0),
                       CONSTRAINT chk_tasks_retry_limit CHECK (retry_count <= max_retries)
);

-- General status filtering
CREATE INDEX idx_tasks_status ON tasks (status);

-- Time-based ordering
CREATE INDEX idx_tasks_scheduled_at ON tasks (scheduled_at);

-- Hot path: scheduler polls this constantly.
-- Partial index = only PENDING rows indexed = stays small forever.
CREATE INDEX idx_tasks_pending_due
    ON tasks (scheduled_at, priority DESC)
    WHERE status = 'PENDING';

-- Audit queries
CREATE INDEX idx_tasks_created_at ON tasks (created_at);

COMMENT ON TABLE  tasks         IS 'Central task registry for the distributed scheduler';
COMMENT ON COLUMN tasks.version IS 'Optimistic locking — Hibernate increments on every UPDATE';
COMMENT ON COLUMN tasks.payload IS 'JSON task parameters, schema is type-specific';
COMMENT ON COLUMN tasks.type    IS 'Determines which WorkerExecutor handles this task';