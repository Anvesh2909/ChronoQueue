package com.sde.chronoqueue.services;

import com.sde.chronoqueue.entities.JobEntity;
import com.sde.chronoqueue.enums.JobState;
import com.sde.chronoqueue.repositories.JobEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final JobEntityRepository jobRepo;
    private final RedisTemplate<String, String> redisTemplate;

    private String queueKey(String queueType) {
        return "chrono:queue:" + queueType.toLowerCase() + ":ready";
    }

    /**
     * Move due jobs from DB to Redis queues
     */
    @Scheduled(fixedRate = 5000)
    @Transactional
    public void moveDueJobsToRedis() {
        Instant now = Instant.now();

        // Find jobs that are PENDING, due, and not yet queued
        List<JobEntity> dueJobs = jobRepo.findByStateAndScheduledAtBeforeAndQueuedAtIsNull(
                JobState.PENDING, now
        );

        int queued = 0;
        int failed = 0;

        for (JobEntity job : dueJobs) {
            String redisKey = queueKey(job.getQueueType().name());

            try {
                // Push to Redis
                redisTemplate.opsForList().leftPush(redisKey, job.getId().toString());

                // Mark as queued (but keep state as PENDING)
                job.setQueuedAt(Instant.now());
                job.setUpdatedAt(Instant.now());
                jobRepo.save(job);

                queued++;

            } catch (Exception redisError) {
                // Redis is down - queuedAt stays null, will retry next cycle
                System.err.println("⚠️ Redis unavailable, will retry job " + job.getId() +
                        " in next cycle: " + redisError.getMessage());
                failed++;
            }
        }

        if (queued > 0) {
            System.out.println("📤 Scheduler queued " + queued + " jobs to Redis" +
                    (failed > 0 ? " (" + failed + " failed)" : ""));
        }
    }
}