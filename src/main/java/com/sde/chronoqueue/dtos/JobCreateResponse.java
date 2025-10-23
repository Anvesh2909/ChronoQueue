package com.sde.chronoqueue.dtos;

import com.sde.chronoqueue.enums.JobState;
import com.sde.chronoqueue.enums.QueueType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record JobCreateResponse(
        UUID id,
        QueueType queueType,
        String taskType,
        Map<String, Object> payload,
        Instant scheduledAt,
        JobState state,
        Integer priority,
        Integer maxAttempts,
        Instant createdAt
) {}

