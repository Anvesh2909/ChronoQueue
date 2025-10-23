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

    @PostConstruct
    public void rebuildRedisQueues() {
        // ✅ CHANGE: Only recover PENDING jobs that were queued before crash
        List<JobEntity> recoverableJobs = jobRepo.findByStateAndQueuedAtIsNotNull(
                JobState.PENDING
        );

        for (JobEntity job : recoverableJobs) {
            try {
                redisTemplate.opsForList().leftPush(
                        queueKey(job.getQueueType().name()),
                        job.getId().toString()
                );
                System.out.println("♻️ Recovered job " + job.getId() + " to Redis");
            } catch (Exception e) {
                System.err.println("⚠️ Could not recover job " + job.getId() + ": " + e.getMessage());
            }
        }

        System.out.println("♻️ Redis queues rebuilt with " + recoverableJobs.size() + " jobs.");
    }
}