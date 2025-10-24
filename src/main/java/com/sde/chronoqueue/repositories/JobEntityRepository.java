package com.sde.chronoqueue.repositories;

import com.sde.chronoqueue.entities.JobEntity;
import com.sde.chronoqueue.enums.JobState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobEntityRepository extends JpaRepository<JobEntity, UUID> {

    /**
     * For scheduler: Find jobs that are due and not yet queued to Redis
     */
    List<JobEntity> findByStateAndScheduledAtBeforeAndQueuedAtIsNull(
            JobState state, Instant before
    );

    /**
     * For recovery service: Find jobs that were queued but system crashed
     */
    List<JobEntity> findByStateAndQueuedAtIsNotNull(JobState state);

    /**
     * For lease reaper: Find jobs with expired leases
     */
    List<JobEntity> findByStateAndLeaseExpiresAtBefore(JobState state, Instant before);

    /**
     * For worker fallback: Fetch jobs that missed Redis queuing
     */
    List<JobEntity> findTop10ByStateAndQueuedAtIsNullAndScheduledAtBeforeOrderByPriorityDescScheduledAtAsc(
            JobState state, Instant before
    );

    /**
     * For heartbeat: Find all jobs owned by a specific worker
     */
    List<JobEntity> findByStateAndOwnerWorkerId(JobState state, String workerId);

    /**
     * For idempotency check
     */
    Optional<JobEntity> findByIdempotencyKey(String idempotencyKey);
}