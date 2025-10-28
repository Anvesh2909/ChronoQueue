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
    private final Queue<JobEntity> jobQueue = new ConcurrentLinkedQueue<>();
    private final Set<UUID> queuedJobIds = Collections.synchronizedSet(new HashSet<>());

    private static final int LEASE_DURATION_SECONDS = 30;
    private static final int MAX_EXECUTION_SECONDS = 300; // 5 minutes max

    private String queueKey(String queueType) {
        return "chrono:queue:" + queueType.toLowerCase() + ":ready";
    }

    @Scheduled(fixedRate = 3000)
    public void fetchAndQueueJobs() {
        for (String queue : new String[]{"EMAIL", "NOTIFICATION", "REPORT"}) {
            String redisKey = queueKey(queue);

            for (int i = 0; i < 10; i++) {
                String jobId = redisTemplate.opsForList().rightPop(redisKey);
                if (jobId == null) break;

                try {
                    UUID uuid = UUID.fromString(jobId);
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

        List<JobEntity> missedJobs = jobRepo
                .findTop10ByStateAndQueuedAtIsNullAndScheduledAtBeforeOrderByPriorityDescScheduledAtAsc(
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
                    " jobs from DB fallback");
        }
    }

    @Scheduled(fixedRate = 500)
    public void processReadyJobs() {
        Instant now = Instant.now();

        for (int i = 0; i < 5; i++) {
            JobEntity job = jobQueue.poll();
            if (job == null) break;

            queuedJobIds.remove(job.getId());

            if (job.getScheduledAt().isAfter(now)) {
                jobQueue.offer(job);
                queuedJobIds.add(job.getId());
                break;
            }

            if (acquireLease(job)) {
                processJob(job);
            } else {
                System.out.println("⚠️ Job " + job.getId() + " already claimed");
            }
        }
    }

    @Transactional
    public boolean acquireLease(JobEntity job) {
        Optional<JobEntity> fresh = jobRepo.findById(job.getId());

        if (fresh.isEmpty() || fresh.get().getState() != JobState.PENDING) {
            return false;
        }

        JobEntity locked = fresh.get();
        Instant now = Instant.now();

        locked.setState(JobState.RUNNING);
        locked.setOwnerWorkerId(workerId);
        locked.setLeaseExpiresAt(now.plusSeconds(LEASE_DURATION_SECONDS));
        locked.setMaxLeaseDeadline(now.plusSeconds(MAX_EXECUTION_SECONDS)); // ← NEW
        locked.setUpdatedAt(now);

        jobRepo.save(locked);

        job.setState(JobState.RUNNING);
        job.setOwnerWorkerId(workerId);

        return true;
    }

    @Transactional
    public void processJob(JobEntity job) {
        System.out.println("⚙️ [Worker:" + workerId.substring(0, 8) + "] Executing job " +
                job.getId() + " [queue=" + job.getQueueType() +
                ", priority=" + job.getPriority() +
                ", attempt=" + (job.getAttempts() + 1) + "/" + job.getMaxAttempts() + "]");

        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(500, 2000));
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

    @Transactional
    public void handleFailure(JobEntity job, Exception e) {
        job.setAttempts(job.getAttempts() + 1);
        job.setLastError(e.getMessage());
        job.setUpdatedAt(Instant.now());

        if (job.getAttempts() < job.getMaxAttempts()) {
            long delaySeconds = (long) Math.pow(2, job.getAttempts()) * 5;
            job.setScheduledAt(Instant.now().plusSeconds(delaySeconds));
            job.setState(JobState.PENDING);
            job.setOwnerWorkerId(null);
            job.setLeaseExpiresAt(null);
            job.setMaxLeaseDeadline(null); // ← RESET
            job.setQueuedAt(null);

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
     * Heartbeat with timeout protection
     */
    @Scheduled(fixedRate = 10000)
    @Transactional
    public void sendHeartbeat() {
        Instant now = Instant.now();
        List<JobEntity> myJobs = jobRepo.findByStateAndOwnerWorkerId(JobState.RUNNING, workerId);

        int extended = 0;
        int timedOut = 0;

        for (JobEntity job : myJobs) {
            // Check if job exceeded max execution time
            if (now.isAfter(job.getMaxLeaseDeadline())) {
                // Job taking too long, kill it
                job.setState(JobState.DEAD);
                job.setLastError("Exceeded max execution time (" + MAX_EXECUTION_SECONDS + "s)");
                job.setOwnerWorkerId(null);
                job.setLeaseExpiresAt(null);
                job.setMaxLeaseDeadline(null);
                job.setUpdatedAt(now);
                jobRepo.save(job);

                timedOut++;
                System.out.println("⏰ Job " + job.getId() + " timed out after " +
                        MAX_EXECUTION_SECONDS + "s");
            } else {
                // Normal heartbeat - extend lease
                job.setLeaseExpiresAt(now.plusSeconds(LEASE_DURATION_SECONDS));
                job.setHeartbeatAt(now);
                job.setUpdatedAt(now);
                jobRepo.save(job);
                extended++;
            }
        }

        if (extended > 0 || timedOut > 0) {
            System.out.println("💓 [Worker:" + workerId.substring(0, 8) + "] Heartbeat: " +
                    extended + " extended" +
                    (timedOut > 0 ? ", " + timedOut + " timed out" : ""));
        }
    }
}