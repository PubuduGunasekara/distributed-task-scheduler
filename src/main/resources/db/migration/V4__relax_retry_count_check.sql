-- ============================================================
-- V4: Allow retry_count to exceed max_retries by exactly one
-- ============================================================
-- Task.fail() now dead-letters on the attempt AFTER retryCount exceeds
-- maxRetries (initial attempt + maxRetries retries, backoff 10s/30s/90s),
-- not when retryCount merely reaches maxRetries. That means retry_count
-- transiently equals max_retries + 1 at the moment a task dead-letters,
-- which the original chk_tasks_retry_limit constraint (retry_count <=
-- max_retries) would reject.
-- ============================================================

ALTER TABLE tasks DROP CONSTRAINT chk_tasks_retry_limit;

ALTER TABLE tasks ADD CONSTRAINT chk_tasks_retry_limit
    CHECK (retry_count <= max_retries + 1);
