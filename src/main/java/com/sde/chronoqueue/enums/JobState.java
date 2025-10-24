package com.sde.chronoqueue.enums;

public enum JobState {
    PENDING,    // Job created, waiting to be executed
    RUNNING,    // Job is currently being processed by a worker
    SUCCEEDED,  // Job completed successfully
    FAILED,     // Job failed but will retry
    DEAD        // Job permanently failed after max retries
}