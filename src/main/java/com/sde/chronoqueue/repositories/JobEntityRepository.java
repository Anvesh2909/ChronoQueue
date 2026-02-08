package com.sde.chronoqueue.repositories;

import com.sde.chronoqueue.entities.JobEntity;
import com.sde.chronoqueue.enums.JobState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobEntityRepository extends JpaRepository<JobEntity, UUID> {

    /* ---------- READ QUERIES (SAFE) ---------- */

    List<JobEntity> findByStateAndScheduledAtBeforeAndQueuedAtIsNull(
            JobState state, Instant before
    );

    List<JobEntity> findByStateAndQueuedAtIsNotNull(JobState state);

    List<JobEntity> findTop10ByStateAndQueuedAtIsNullAndScheduledAtBeforeOrderByPriorityDescScheduledAtAsc(
            JobState state, Instant before
    );

    List<JobEntity> findByStateAndOwnerWorkerId(JobState state, String workerId);

    Optional<JobEntity> findByIdempotencyKey(String idempotencyKey);

    List<JobEntity> findByStateAndMaxLeaseDeadlineBefore(JobState state, Instant now);

    /* ---------- WRITE (CAS) QUERIES ---------- */

    @Modifying
    @Query("""
    UPDATE JobEntity j
    SET j.state = com.sde.chronoqueue.enums.JobState.RUNNING,
        j.ownerWorkerId = :workerId,
        j.leaseExpiresAt = :leaseExpiresAt,
        j.maxLeaseDeadline = :maxLeaseDeadline,
        j.updatedAt = :now
    WHERE j.id = :jobId
      AND j.state = com.sde.chronoqueue.enums.JobState.PENDING
    """)
    int acquireLease(
            UUID jobId,
            String workerId,
            Instant leaseExpiresAt,
            Instant maxLeaseDeadline,
            Instant now
    );

    @Modifying
    @Query("""
    UPDATE JobEntity j
    SET j.leaseExpiresAt = :leaseExpiresAt,
        j.heartbeatAt = :now,
        j.updatedAt = :now
    WHERE j.id = :jobId
      AND j.state = com.sde.chronoqueue.enums.JobState.RUNNING
      AND j.ownerWorkerId = :workerId
    """)
    int extendLease(
            UUID jobId,
            String workerId,
            Instant leaseExpiresAt,
            Instant now
    );

    @Modifying
    @Query("""
    UPDATE JobEntity j
    SET j.state = com.sde.chronoqueue.enums.JobState.PENDING,
        j.ownerWorkerId = NULL,
        j.leaseExpiresAt = NULL,
        j.maxLeaseDeadline = NULL,
        j.queuedAt = NULL,
        j.updatedAt = :now
    WHERE j.id = :jobId
      AND j.state = com.sde.chronoqueue.enums.JobState.RUNNING
      AND j.leaseExpiresAt < :now
    """)
    int recoverExpiredLease(UUID jobId, Instant now);

    @Modifying
    @Query("""
    UPDATE JobEntity j
    SET j.state = com.sde.chronoqueue.enums.JobState.DEAD,
        j.lastError = :reason,
        j.ownerWorkerId = NULL,
        j.leaseExpiresAt = NULL,
        j.maxLeaseDeadline = NULL,
        j.updatedAt = :now
    WHERE j.id = :jobId
      AND j.state = com.sde.chronoqueue.enums.JobState.RUNNING
      AND j.maxLeaseDeadline < :now
    """)
    int killExceededJob(UUID jobId, String reason, Instant now);

    JobEntity[] findByStateAndLeaseExpiresAtBefore(JobState jobState, Instant now);
}
