package com.sde.chronoqueue.repositories;

import com.sde.chronoqueue.entities.JobEntity;
import com.sde.chronoqueue.enums.JobState;
import com.sde.chronoqueue.enums.QueueType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface JobEntityRepository extends JpaRepository<JobEntity, UUID> {
    List<JobEntity> findByState(JobState state);

    List<JobEntity> findByQueueType(QueueType queueType);
    List<JobEntity> findByStateAndScheduledAtBefore(JobState state, Instant time);


    @Query("""
        SELECT j FROM JobEntity j
        WHERE (:state IS NULL OR j.state = :state)
        AND (:queueType IS NULL OR j.queueType = :queueType)
        AND (:taskType IS NULL OR j.taskType = :taskType)
        ORDER BY j.createdAt DESC
    """)
    List<JobEntity> findFiltered(
            @Param("state") JobState state,
            @Param("queueType") QueueType queueType,
            @Param("taskType") String taskType
    );

    List<JobEntity> findByStateAndUpdatedAtBefore(JobState jobState, Instant now);

    List<JobEntity> findByStateIn(List<JobState> ready);

    Iterable<JobEntity> findTop10ByStateOrderByScheduledAtAsc(JobState jobState);
}