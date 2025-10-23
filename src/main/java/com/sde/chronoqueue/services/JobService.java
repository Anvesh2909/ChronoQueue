package com.sde.chronoqueue.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sde.chronoqueue.dtos.JobCreateRequest;
import com.sde.chronoqueue.dtos.JobCreateResponse;
import com.sde.chronoqueue.entities.JobEntity;
import com.sde.chronoqueue.enums.JobState;
import com.sde.chronoqueue.repositories.JobEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
public class JobService {
    private final JobEntityRepository jobRepo;
    private final ObjectMapper objectMapper;

    public JobService(JobEntityRepository jobRepo, ObjectMapper objectMapper) {
        this.jobRepo = jobRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public JobCreateResponse createJob(JobCreateRequest request) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(request.payload());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting payload to JSON", e);
        }

        JobEntity job = JobEntity.builder()
                .queueType(request.queueType())
                .taskType(request.taskType())
                .payload(payloadJson)
                .scheduledAt(request.scheduledAt())
                .priority(Optional.ofNullable(request.priority()).orElse(100))
                .maxAttempts(Optional.ofNullable(request.maxAttempts()).orElse(5))
                .state(JobState.PENDING)
                .archived(false)
                .build();

        // ✅ Explicitly set timestamps
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());

        JobEntity saved = jobRepo.save(job);

        Map<String, Object> payloadMap;
        try {
            payloadMap = objectMapper.readValue(saved.getPayload(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            payloadMap = Map.of();
        }

        return new JobCreateResponse(
                saved.getId(),
                saved.getQueueType(),
                saved.getTaskType(),
                payloadMap,
                saved.getScheduledAt(),
                saved.getState(),
                saved.getPriority(),
                saved.getMaxAttempts(),
                saved.getCreatedAt()
        );
    }
}
