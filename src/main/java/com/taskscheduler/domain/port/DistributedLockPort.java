package com.taskscheduler.domain.port;

import java.util.UUID;

/**
 * Port for distributed mutual exclusion across worker instances.
 *
 * Contract:
 * - acquireLock() returns true only if THIS caller now exclusively owns the lock.
 * - releaseLock() MUST be called in a finally block after a successful acquire.
 * - Lock auto-expires via TTL — no manual cleanup needed on worker crash.
 * - releaseLock() is a no-op if the lock was already released or expired.
 *
 * The domain defines what it needs (a lock). The infrastructure
 * decides how (Redis SETNX). Swapping to ZooKeeper or a DB-based
 * lock requires only a new implementation — zero domain changes.
 */
public interface DistributedLockPort {

    /**
     * Try to acquire an exclusive lock for this task.
     *
     * @param taskId  the task to lock
     * @param ownerId unique identifier of this worker instance
     * @return true if the lock was acquired, false if another worker holds it
     */
    boolean acquireLock(UUID taskId, String ownerId);

    /**
     * Release the lock only if this caller is the current owner.
     * Safe to call even if the lock has already expired.
     *
     * @param taskId  the task whose lock to release
     * @param ownerId must match the ownerId used in acquireLock()
     */
    void releaseLock(UUID taskId, String ownerId);
}