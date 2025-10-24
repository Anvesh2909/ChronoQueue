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
    private final ObjectMapper objectMapper;

    private final String workerId = UUID.randomUUID().toString();

    // Thread-safe queue for in-memory job processing
    private final Queue<JobEntity> jobQueue = new ConcurrentLinkedQueue<>();

    // Track jobs already in queue to prevent duplicates
    private final Set<UUID> queuedJobIds = Collections.synchronizedSet(new HashSet<>());

    private String queueKey(String queueType) {
        return "chrono:queue:" + queueType.toLowerCase() + ":ready";
    }

    /**
     * Poll Redis & DB to refill in-memory queue
     */
    @Scheduled(fixedRate = 3000)
    public void fetchAndQueueJobs() {
        // 1. Try Redis first (fast path)
        for (String queue : new String[]{"EMAIL", "NOTIFICATION", "REPORT"}) {
            String redisKey = queueKey(queue);

            // Pop multiple jobs at once for efficiency
            for (int i = 0; i < 10; i++) {
                String jobId = redisTemplate.opsForList().rightPop(redisKey);
                if (jobId == null) break;

                try {
                    UUID uuid = UUID.fromString(jobId);

                    // Skip if already in our queue
                    if (queuedJobIds.contains(uuid)) continue;

                    jobRepo.findById(uuid)
                            .filter(job -> job.getState() == JobState.PENDING)
                            .ifPresent(job -> {
                                jobQueue.offer(job);
                                queuedJobIds.add(job.getId());
                            });
                } catch (Exception e) {
                    System.err.println("⚠️ Invalid job ID from Redis: " + jobId);
                }
            }
        }

        // 2. DB fallback for missed jobs (Redis was down during scheduling)
        // Only fetch jobs that were NOT successfully queued to Redis
        List<JobEntity> missedJobs = jobRepo.findTop10ByStateAndQueuedAtIsNullAndScheduledAtBeforeOrderByPriorityDescScheduledAtAsc(
                JobState.PENDING, Instant.now()
        );

        for (JobEntity job : missedJobs) {
            if (!queuedJobIds.contains(job.getId())) {
                jobQueue.offer(job);
                queuedJobIds.add(job.getId());
            }
        }

        if (!missedJobs.isEmpty()) {
            System.out.println("🔄 Worker fetched " + missedJobs.size() +
                    " jobs from DB fallback (Redis was unavailable)");
        }
    }

    /**
     * Process jobs from in-memory queue
     */
    @Scheduled(fixedRate = 500)
    public void processReadyJobs() {
        Instant now = Instant.now();

        // Process up to 5 jobs per cycle
        for (int i = 0; i < 5; i++) {
            JobEntity job = jobQueue.poll();
            if (job == null) break;

            // Remove from tracking set
            queuedJobIds.remove(job.getId());

            // Check if job is ready
            if (job.getScheduledAt().isAfter(now)) {
                // Not ready yet, put it back
                jobQueue.offer(job);
                queuedJobIds.add(job.getId());
                break; // Stop processing, jobs are roughly sorted
            }

            // Acquire lease before processing
            if (acquireLease(job)) {
                processJob(job);
            } else {
                // Another worker grabbed it, skip
                System.out.println("⚠️ Job " + job.getId() + " already claimed by another worker");
            }
        }
    }

    /**
     * Acquire a lease on the job (distributed lock via DB)
     */
    @Transactional
    public boolean acquireLease(JobEntity job) {
        // Re-fetch and lock the job
        Optional<JobEntity> fresh = jobRepo.findById(job.getId());

        if (fresh.isEmpty() || fresh.get().getState() != JobState.PENDING) {
            return false; // Already processed or deleted
        }

        JobEntity locked = fresh.get();
        locked.setState(JobState.RUNNING);
        locked.setOwnerWorkerId(workerId);
        locked.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        locked.setUpdatedAt(Instant.now());

        jobRepo.save(locked);

        // Update our local reference
        job.setState(JobState.RUNNING);
        job.setOwnerWorkerId(workerId);

        return true;
    }

    /**
     * Execute job logic
     */
    @Transactional
    public void processJob(JobEntity job) {
        System.out.println("⚙️ [Worker:" + workerId.substring(0, 8) + "] Executing job " + job.getId() +
                " [queue=" + job.getQueueType() +
                ", priority=" + job.getPriority() +
                ", attempt=" + (job.getAttempts() + 1) + "/" + job.getMaxAttempts() + "]");

        try {
            // Simulate work with random duration
            Thread.sleep(ThreadLocalRandom.current().nextInt(500, 2000));

            // Simulate 70% success rate
            boolean success = ThreadLocalRandom.current().nextInt(100) > 30;

            if (success) {
                job.setState(JobState.SUCCEEDED);
                job.setUpdatedAt(Instant.now());
                jobRepo.save(job);
                System.out.println("✅ Job " + job.getId() + " completed successfully");
            } else {
                throw new RuntimeException("Simulated task failure");
            }

        } catch (Exception e) {
            handleFailure(job, e);
        }
    }

    /**
     * Handle job failure with exponential backoff
     */
    @Transactional
    public void handleFailure(JobEntity job, Exception e) {
        job.setAttempts(job.getAttempts() + 1);
        job.setLastError(e.getMessage());
        job.setUpdatedAt(Instant.now());

        if (job.getAttempts() < job.getMaxAttempts()) {
            // Exponential backoff: 5s, 10s, 20s, 40s, 80s
            long delaySeconds = (long) Math.pow(2, job.getAttempts()) * 5;
            job.setScheduledAt(Instant.now().plusSeconds(delaySeconds));
            job.setState(JobState.PENDING);
            job.setOwnerWorkerId(null);
            job.setLeaseExpiresAt(null);
            job.setQueuedAt(null); // Allow re-queuing by scheduler

            jobRepo.save(job);

            System.out.println("🔁 Job " + job.getId() + " retry " + job.getAttempts() +
                    "/" + job.getMaxAttempts() + " scheduled in " + delaySeconds + "s");
        } else {
            job.setState(JobState.DEAD);
            jobRepo.save(job);
            System.out.println("💀 Job " + job.getId() + " permanently failed after " +
                    job.getAttempts() + " attempts: " + e.getMessage());
        }
    }

    /**
     * Heartbeat to extend lease while processing long jobs
     */
    @Scheduled(fixedRate = 10000)
    @Transactional
    public void sendHeartbeat() {
        List<JobEntity> myJobs = jobRepo.findByStateAndOwnerWorkerId(JobState.RUNNING, workerId);

        for (JobEntity job : myJobs) {
            job.setLeaseExpiresAt(Instant.now().plusSeconds(30));
            job.setHeartbeatAt(Instant.now());
            jobRepo.save(job);
        }

        if (!myJobs.isEmpty()) {
            System.out.println("💓 [Worker:" + workerId.substring(0, 8) +
                    "] Heartbeat sent for " + myJobs.size() + " jobs");
        }
    }
}