package com.sde.chronoqueue.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sde.chronoqueue.entities.JobEntity;
import com.sde.chronoqueue.enums.JobState;
import com.sde.chronoqueue.repositories.JobEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class WorkerService {

    private final JobEntityRepository jobRepo;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    // 🧠 PriorityQueue acts like min-heap by scheduledAt time, and then by priority
    private final PriorityQueue<JobEntity> jobQueue = new PriorityQueue<>(
            Comparator.<JobEntity, Instant>comparing(JobEntity::getScheduledAt)
                    .thenComparing(Comparator.comparing(JobEntity::getPriority).reversed())
    );

    private String queueKey(String queueType) {
        return "chrono:queue:" + queueType.toLowerCase() + ":ready";
    }

    // 🕒 Poll Redis & DB every few seconds to refill queue
    @Scheduled(fixedRate = 3000)
    public void fetchAndQueueJobs() {
        for (String queue : new String[]{"email", "notification", "report"}) {
            String redisKey = queueKey(queue);
            String jobId = redisTemplate.opsForList().rightPop(redisKey);

            if (jobId != null) {
                jobRepo.findById(java.util.UUID.fromString(jobId))
                        .filter(job -> job.getState() == JobState.PENDING)
                        .ifPresent(jobQueue::offer);
            }
        }

        // Also check DB fallback (fault tolerance)
        jobRepo.findTop10ByStateOrderByScheduledAtAsc(JobState.PENDING)
                .forEach(jobQueue::offer);
    }

    // ⚙️ Main worker loop — executes ready jobs
    @Scheduled(fixedRate = 1000)
    public void processReadyJobs() {
        while (!jobQueue.isEmpty()) {
            JobEntity job = jobQueue.peek();

            if (job.getScheduledAt().isAfter(Instant.now())) {
                // ⏳ Job is not ready yet — stop processing
                break;
            }

            // Pop from queue and execute
            jobQueue.poll();
            processJob(job);
        }
    }

    private void processJob(JobEntity job) {
        System.out.println("⚙️ Executing job " + job.getId() +
                " [queue=" + job.getQueueType() + ", priority=" + job.getPriority() + "]");

        job.setState(JobState.RUNNING);
        job.setUpdatedAt(Instant.now());
        jobRepo.save(job);

        try {
            // Simulate task work
            Thread.sleep(1000);

            boolean success = ThreadLocalRandom.current().nextInt(100) > 40; // 60% success rate

            if (success) {
                job.setState(JobState.SUCCEEDED);
                System.out.println("✅ Job " + job.getId() + " completed successfully.");
            } else {
                job.setAttempts(job.getAttempts() + 1);
                if (job.getAttempts() < job.getMaxAttempts()) {
                    long delay = (long) Math.pow(2, job.getAttempts()) * 5; // exponential backoff
                    job.setScheduledAt(Instant.now().plusSeconds(delay));
                    job.setState(JobState.PENDING);
                    jobQueue.offer(job); // 🔁 Re-enqueue for future attempt
                    System.out.println("🔁 Retry scheduled in " + delay + "s for job " + job.getId());
                } else {
                    job.setState(JobState.DEAD);
                    System.out.println("💀 Job " + job.getId() + " permanently failed.");
                }
            }

            job.setUpdatedAt(Instant.now());
            jobRepo.save(job);

        } catch (Exception e) {
            job.setState(JobState.FAILED);
            jobRepo.save(job);
        }
    }
}