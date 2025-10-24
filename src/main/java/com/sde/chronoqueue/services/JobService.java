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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
        // Check idempotency - if job already exists, return existing
        if (request.idempotencyKey() != null) {
            Optional<JobEntity> existing = jobRepo.findByIdempotencyKey(request.idempotencyKey());
            if (existing.isPresent()) {
                System.out.println("⚠️ Duplicate job creation prevented by idempotency key: " +
                        request.idempotencyKey());
                return mapToResponse(existing.get());
            }
        }

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
                .idempotencyKey(request.idempotencyKey())
                .state(JobState.PENDING)
                .archived(false)
                .build();

        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());

        JobEntity saved = jobRepo.save(job);

        System.out.println("✅ Created job " + saved.getId() +
                " [queue=" + saved.getQueueType() +
                ", scheduled=" + saved.getScheduledAt() + "]");

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public JobCreateResponse getJobStatus(UUID jobId) {
        JobEntity job = jobRepo.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found with ID: " + jobId));
        return mapToResponse(job);
    }

    @Transactional(readOnly = true)
    public List<JobCreateResponse> getAllJobs() {
        return jobRepo.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private JobCreateResponse mapToResponse(JobEntity job) {
        Map<String, Object> payloadMap;
        try {
            payloadMap = objectMapper.readValue(job.getPayload(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            payloadMap = Map.of();
        }

        return new JobCreateResponse(
                job.getId(),
                job.getQueueType(),
                job.getTaskType(),
                payloadMap,
                job.getScheduledAt(),
                job.getState(),
                job.getPriority(),
                job.getMaxAttempts(),
                job.getCreatedAt()
        );
    }
}