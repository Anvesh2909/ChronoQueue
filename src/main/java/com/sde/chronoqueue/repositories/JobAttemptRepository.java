package com.sde.chronoqueue.repositories;

import com.sde.chronoqueue.entities.JobAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface JobAttemptRepository extends JpaRepository<JobAttemptEntity, UUID> {
}
