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
     * Recover jobs with expired leases OR exceeded max deadline
     */
    @Scheduled(fixedRate = 15000)
    @Transactional
    public void detectAndRecoverStuckJobs() {
        Instant now = Instant.now();

        // 1. Find jobs where lease expired (worker crashed)
        List<JobEntity> expiredLeases = jobRepo.findByStateAndLeaseExpiresAtBefore(
                JobState.RUNNING, now
        );

        // 2. Find jobs that exceeded max execution time (worker alive but job hung)
        List<JobEntity> exceededDeadline = jobRepo.findByStateAndMaxLeaseDeadlineBefore(
                JobState.RUNNING, now
        );

        int recovered = 0;
        int killed = 0;

        // Handle expired leases - requeue for retry
        for (JobEntity job : expiredLeases) {
            System.out.println("💀 Detected stuck job " + job.getId() +
                    " (worker: " + job.getOwnerWorkerId() + "), requeueing");

            job.setState(JobState.PENDING);
            job.setScheduledAt(now.plusSeconds(5));
            job.setOwnerWorkerId(null);
            job.setLeaseExpiresAt(null);
            job.setMaxLeaseDeadline(null);
            job.setQueuedAt(null);
            job.setUpdatedAt(now);

            jobRepo.save(job);
            recovered++;
        }

        // Handle exceeded deadlines - mark as dead (already took too long)
        for (JobEntity job : exceededDeadline) {
            System.out.println("⏰ Job " + job.getId() + " exceeded max execution time, marking dead");

            job.setState(JobState.DEAD);
            job.setLastError("Exceeded maximum execution time");
            job.setOwnerWorkerId(null);
            job.setLeaseExpiresAt(null);
            job.setMaxLeaseDeadline(null);
            job.setUpdatedAt(now);

            jobRepo.save(job);
            killed++;
        }

        if (recovered > 0 || killed > 0) {
            System.out.println("♻️ Lease Reaper: " + recovered + " recovered, " + killed + " killed");
        }
    }
}