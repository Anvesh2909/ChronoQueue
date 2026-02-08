package com.sde.chronoqueue.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sde.chronoqueue.entities.JobEntity;
import com.sde.chronoqueue.enums.JobState;
import com.sde.chronoqueue.repositories.JobEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class WorkerService {

    private final JobEntityRepository jobRepo;
    private final RedisTemplate<String, String> redisTemplate;

    private final String workerId = UUID.randomUUID().toString();
    private final Queue<JobEntity> jobQueue = new ConcurrentLinkedQueue<>();

    private static final int LEASE_DURATION_SECONDS = 30;
    private static final int MAX_EXECUTION_SECONDS = 300;

    private String queueKey(String queueType) {
        return "chrono:queue:" + queueType.toLowerCase() + ":ready";
    }

    @Scheduled(fixedRate = 3000)
    public void fetchJobs() {
        for (String queue : new String[]{"EMAIL", "NOTIFICATION", "REPORT"}) {
            for (int i = 0; i < 10; i++) {
                String jobId = redisTemplate.opsForList().rightPop(queueKey(queue));
                if (jobId == null) break;

                try {
                    jobRepo.findById(UUID.fromString(jobId))
                            .ifPresent(jobQueue::offer);
                } catch (Exception ignored) {}
            }
        }

        jobQueue.addAll(
                jobRepo.findTop10ByStateAndQueuedAtIsNullAndScheduledAtBeforeOrderByPriorityDescScheduledAtAsc(
                        JobState.PENDING, Instant.now()
                )
        );
    }

    @Scheduled(fixedRate = 500)
    public void processJobs() {
        for (int i = 0; i < 5; i++) {
            JobEntity job = jobQueue.poll();
            if (job == null) return;

            Instant now = Instant.now();

            int acquired = jobRepo.acquireLease(
                    job.getId(),
                    workerId,
                    now.plusSeconds(LEASE_DURATION_SECONDS),
                    now.plusSeconds(MAX_EXECUTION_SECONDS),
                    now
            );

            if (acquired == 1) {
                execute(job);
            }
        }
    }

    private void execute(JobEntity job) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(500, 2000));

            if (ThreadLocalRandom.current().nextInt(100) < 30) {
                throw new RuntimeException("Simulated failure");
            }

            job.setState(JobState.SUCCEEDED);
            jobRepo.save(job);

        } catch (Exception e) {
            handleFailure(job, e);
        }
    }

    private void handleFailure(JobEntity job, Exception e) {
        job.setAttempts(job.getAttempts() + 1);
        job.setLastError(e.getMessage());

        if (job.getAttempts() < job.getMaxAttempts()) {
            job.setState(JobState.PENDING);
            job.setScheduledAt(Instant.now().plusSeconds(
                    (long) Math.pow(2, job.getAttempts()) * 5
            ));
            job.setQueuedAt(null);
        } else {
            job.setState(JobState.DEAD);
        }

        job.setOwnerWorkerId(null);
        job.setLeaseExpiresAt(null);
        job.setMaxLeaseDeadline(null);
        jobRepo.save(job);
    }


    /**
     * Heartbeat with timeout protection
     */
    @Scheduled(fixedRate = 10000)
    @Transactional
    public void heartbeat() {
        Instant now = Instant.now();

        for (JobEntity job :
                jobRepo.findByStateAndOwnerWorkerId(JobState.RUNNING, workerId)) {

            int updated = jobRepo.extendLease(
                    job.getId(),
                    workerId,
                    now.plusSeconds(LEASE_DURATION_SECONDS),
                    now
            );

            if (updated == 0) {
                // Lost ownership → STOP touching this job
                System.out.println("⚠️ Lost lease for job " + job.getId());
            }
        }
    }

}