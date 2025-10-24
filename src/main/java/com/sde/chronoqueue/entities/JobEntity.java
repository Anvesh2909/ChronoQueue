package com.sde.chronoqueue.entities;

import com.sde.chronoqueue.enums.JobState;
import com.sde.chronoqueue.enums.QueueType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs", indexes = {
        @Index(name = "idx_job_processing", columnList = "state,scheduledAt,priority"),
        @Index(name = "idx_lease_expiry", columnList = "state,leaseExpiresAt"),
        @Index(name = "idx_queued_jobs", columnList = "state,queuedAt")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "queue_type", nullable = false)
    private QueueType queueType;

    @Column(name = "task_type", nullable = false)
    private String taskType;

    @Column(columnDefinition = "text", nullable = false)
    private String payload;

    @Column(columnDefinition = "text")
    private String metadata = "{}";

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobState state = JobState.PENDING;

    @Column(nullable = false)
    private Integer priority = 100;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "max_attempts")
    private Integer maxAttempts = 5;

    @Column(unique = true)
    private String idempotencyKey;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "retry_backoff", columnDefinition = "text")
    private String retryBackoff = """
        {"type":"exponential","initial_delay_seconds":5,"max_delay_seconds":86400}
        """;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "last_error_payload", columnDefinition = "text")
    private String lastErrorPayload = "{}";

    @Column(name = "owner_worker_id")
    private String ownerWorkerId;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    @Column(nullable = false)
    private Boolean archived = false;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}