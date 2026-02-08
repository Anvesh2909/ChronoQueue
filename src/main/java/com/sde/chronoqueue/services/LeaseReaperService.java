package com.sde.chronoqueue.services;

import com.sde.chronoqueue.entities.JobEntity;
import com.sde.chronoqueue.enums.JobState;
import com.sde.chronoqueue.repositories.JobEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaseReaperService {

    private final JobEntityRepository jobRepo;

    @Scheduled(fixedRate = 15000)
    @Transactional
    public void reap() {
        Instant now = Instant.now();

        for (JobEntity job :
                jobRepo.findByStateAndLeaseExpiresAtBefore(JobState.RUNNING, now)) {

            jobRepo.recoverExpiredLease(job.getId(), now);
        }

        for (JobEntity job :
                jobRepo.findByStateAndMaxLeaseDeadlineBefore(JobState.RUNNING, now)) {

            jobRepo.killExceededJob(
                    job.getId(),
                    "Exceeded max execution time",
                    now
            );
        }
    }
}
