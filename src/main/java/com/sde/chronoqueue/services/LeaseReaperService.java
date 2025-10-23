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
public class LeaseReaperService {

    private final JobEntityRepository jobRepo;
    private final RedisTemplate<String, String> redisTemplate;

    private String queueKey(String queueType) {
        return "chrono:queue:" + queueType.toLowerCase() + ":ready";
    }

    // every 10 seconds
    @Scheduled(fixedRate = 10000)
    public void detectAndRecoverStuckJobs() {
        Instant now = Instant.now().minusSeconds(30); // lease expiry = 30s
        List<JobEntity> stuckJobs = jobRepo.findByStateAndUpdatedAtBefore(JobState.RUNNING, now);

        for (JobEntity job : stuckJobs) {
            System.out.println("💀 Detected stuck job " + job.getId() + ", requeueing.");

            job.setState(JobState.PENDING);
            job.setScheduledAt(Instant.now().plusSeconds(5)); // retry soon
            jobRepo.save(job);

            try {
                redisTemplate.opsForList().leftPush(queueKey(job.getQueueType().name()), job.getId().toString());
            } catch (Exception e) {
                System.err.println("⚠️ Redis unavailable, job will be retried in DB next cycle: " + job.getId());
            }
        }
    }
}
