package com.sde.chronoqueue.dtos;

import com.sde.chronoqueue.enums.QueueType;

import java.time.Instant;
import java.util.Map;

public record JobCreateRequest(
        QueueType queueType,
        String taskType,
        Map<String, Object> payload,
        Instant scheduledAt,
        Integer priority,
        Integer maxAttempts
) {}

