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

import java.util.List;
import java.util.Map;
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

        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }

        // Idempotency check
        jobRepo.findByIdempotencyKey(request.idempotencyKey())
                .ifPresent(existing -> {
                    throw new IdempotentJobException(existing);
                });

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(request.payload());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Invalid payload JSON", e);
        }

        JobEntity job = JobEntity.builder()
                .queueType(request.queueType())
                .taskType(request.taskType())
                .payload(payloadJson)
                .scheduledAt(request.scheduledAt())
                .priority(request.priority() != null ? request.priority() : 100)
                .maxAttempts(request.maxAttempts() != null ? request.maxAttempts() : 5)
                .attempts(0)
                .state(JobState.PENDING)
                .idempotencyKey(request.idempotencyKey())
                .archived(false)
                .build();

        JobEntity saved = jobRepo.save(job);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public JobCreateResponse getJobStatus(UUID jobId) {
        JobEntity job = jobRepo.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        return mapToResponse(job);
    }

    @Transactional(readOnly = true)
    public List<JobCreateResponse> getAllJobs() {
        return jobRepo.findAll()
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private JobCreateResponse mapToResponse(JobEntity job) {
        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(job.getPayload(), new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            payload = Map.of();
        }

        return new JobCreateResponse(
                job.getId(),
                job.getQueueType(),
                job.getTaskType(),
                payload,
                job.getScheduledAt(),
                job.getState(),
                job.getPriority(),
                job.getMaxAttempts(),
                job.getCreatedAt()
        );
    }

    /**
     * Internal exception to short-circuit idempotent creates cleanly
     */
    private static class IdempotentJobException extends RuntimeException {
        private final JobEntity job;
        IdempotentJobException(JobEntity job) {
            this.job = job;
        }
    }
}
