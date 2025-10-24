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

    /**
     * Detect jobs with expired leases and requeue them
     * This handles worker crashes or network partitions
     */
    @Scheduled(fixedRate = 15000)
    @Transactional
    public void detectAndRecoverStuckJobs() {
        Instant now = Instant.now();

        // Find jobs where lease has expired
        List<JobEntity> stuckJobs = jobRepo.findByStateAndLeaseExpiresAtBefore(
                JobState.RUNNING, now
        );

        for (JobEntity job : stuckJobs) {
            System.out.println("💀 Detected stuck job " + job.getId() +
                    " (worker: " + job.getOwnerWorkerId() + "), requeueing");

            job.setState(JobState.PENDING);
            job.setScheduledAt(Instant.now().plusSeconds(5)); // retry soon
            job.setOwnerWorkerId(null);
            job.setLeaseExpiresAt(null);
            job.setQueuedAt(null); // Allow scheduler to re-queue
            job.setUpdatedAt(Instant.now());

            jobRepo.save(job);
        }

        if (!stuckJobs.isEmpty()) {
            System.out.println("♻️ Lease Reaper recovered " + stuckJobs.size() + " stuck jobs");
        }
    }
}