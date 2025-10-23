package com.sde.chronoqueue.entities;

import com.sde.chronoqueue.enums.AttemptOutcome;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.GenericGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "job_attempts",
        indexes = {
                @Index(name = "idx_attempts_job", columnList = "job_id, attemptNumber DESC"),
                @Index(name = "idx_attempts_worker", columnList = "workerId, startedAt")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobAttemptEntity {

    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private JobEntity job;

    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    private AttemptOutcome outcome;

    @Column(name = "worker_id")
    private String workerId;

    @Column(name = "duration_ms")
    private Long durationMs;

    private String error;

    @Column(name = "error_payload", columnDefinition = "jsonb")
    private String errorPayload;

    @Column(name = "logs", columnDefinition = "jsonb")
    private String logs = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
