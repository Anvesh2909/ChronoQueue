package com.sde.chronoqueue.services;

import com.sde.chronoqueue.entities.JobEntity;
import com.sde.chronoqueue.enums.JobState;
import com.sde.chronoqueue.repositories.JobEntityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

    @Scheduled(fixedRate = 5000)
    public void moveDueJobsToRedis() {
        Instant now = Instant.now();

        // ✅ CHANGE: Find jobs that are PENDING and not yet queued
        List<JobEntity> dueJobs = jobRepo.findByStateAndScheduledAtBeforeAndQueuedAtIsNull(
                JobState.PENDING, now
        );

        for (JobEntity job : dueJobs) {
            String redisKey = queueKey(job.getQueueType().name());

            try {
                redisTemplate.opsForList().leftPush(redisKey, job.getId().toString());

                // ✅ CHANGE: Just mark as queued, don't change state
                job.setQueuedAt(Instant.now());
                job.setUpdatedAt(Instant.now());
                jobRepo.save(job);

                System.out.println("✅ Queued job " + job.getId() + " to Redis");
            } catch (Exception redisError) {
                // ✅ CHANGE: queuedAt stays null, will retry next cycle
                System.err.println("⚠️ Redis unavailable, will retry job " + job.getId() + " later.");
            }
        }
    }
}