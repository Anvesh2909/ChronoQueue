package com.sde.chronoqueue.services;

import com.sde.chronoqueue.entities.JobEntity;
import com.sde.chronoqueue.enums.JobState;
import com.sde.chronoqueue.repositories.JobEntityRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RedisRecoveryService {

    private final JobEntityRepository jobRepo;
    private final RedisTemplate<String, String> redisTemplate;

    private String queueKey(String queueType) {
        return "chrono:queue:" + queueType.toLowerCase() + ":ready";
    }

    /**
     * Rebuild Redis queues on application startup
     * Recovers jobs that were queued before a crash
     */
    @PostConstruct
    public void rebuildRedisQueues() {
        System.out.println("🔄 Starting Redis queue recovery...");

        // Only recover PENDING jobs that were queued before crash
        List<JobEntity> recoverableJobs = jobRepo.findByStateAndQueuedAtIsNotNull(
                JobState.PENDING
        );

        int recovered = 0;
        int failed = 0;

        for (JobEntity job : recoverableJobs) {
            try {
                redisTemplate.opsForList().leftPush(
                        queueKey(job.getQueueType().name()),
                        job.getId().toString()
                );
                recovered++;
            } catch (Exception e) {
                System.err.println("⚠️ Could not recover job " + job.getId() + ": " + e.getMessage());
                failed++;
            }
        }

        System.out.println("♻️ Redis queues rebuilt: " + recovered + " jobs recovered" +
                (failed > 0 ? ", " + failed + " failed" : ""));
    }
}