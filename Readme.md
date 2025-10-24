# ChronoQueue ⚡

> A distributed job scheduling system built with Spring Boot, Redis, and PostgreSQL

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Redis](https://img.shields.io/badge/Redis-7.x-red.svg)](https://redis.io/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue.svg)](https://www.postgresql.org/)

## 📋 Table of Contents

- [About](#about)
- [Features](#features)
- [How It Works](#how-it-works)
- [Technologies Used](#technologies-used)
- [Getting Started](#getting-started)
- [API Usage](#api-usage)
- [Architecture](#architecture)
- [Key Concepts](#key-concepts)
- [What I Learned](#what-i-learned)
- [Future Improvements](#future-improvements)

## About

ChronoQueue is a background job scheduling system that allows you to schedule tasks to run at specific times or with delays. It's similar to systems like Sidekiq (Ruby), Celery (Python), or Bull (Node.js).

**Real-world use cases:**
- Send welcome emails after user registration
- Generate daily/weekly reports
- Send reminder notifications
- Process background tasks without blocking the main application

**Project Goal:** Build a working distributed job scheduler to understand how background job processing works in production systems.

## Features

✅ **What's Implemented:**
- Schedule jobs to run at specific times
- Priority-based job execution (high priority jobs run first)
- Automatic retry with exponential backoff on failures
- Multiple worker instances can run simultaneously
- Redis queues for fast job distribution
- PostgreSQL for persistent job storage
- Distributed locking to prevent duplicate job execution
- Automatic recovery when workers crash
- Job status tracking (PENDING → RUNNING → SUCCEEDED/FAILED/DEAD)
- Idempotency keys to prevent duplicate jobs

## How It Works

### Simple Flow

```
1. Client creates a job via REST API
   ↓
2. Job saved to PostgreSQL (state: PENDING)
   ↓
3. Scheduler finds jobs that are due
   ↓
4. Scheduler pushes job IDs to Redis queue
   ↓
5. Worker pulls job from Redis
   ↓
6. Worker acquires a "lease" on the job (distributed lock)
   ↓
7. Worker executes the job
   ↓
8. Worker updates job status (SUCCEEDED or FAILED)
```

### When Things Go Wrong

**If a worker crashes while processing:**
- The job has a `leaseExpiresAt` timestamp
- LeaseReaperService detects expired leases
- Job is automatically requeued for retry

**If Redis goes down:**
- Scheduler can't push to Redis
- Workers fall back to querying database directly
- System continues working (a bit slower)

**If job fails:**
- Automatic retry with exponential backoff (5s → 10s → 20s → 40s...)
- After max attempts, job marked as DEAD

## Technologies Used

| Technology | Why I Used It |
|-----------|---------------|
| **Spring Boot 3.x** | Main framework - handles REST APIs, scheduling, dependency injection |
| **PostgreSQL** | Stores jobs persistently - survives crashes and restarts |
| **Redis** | Fast in-memory queue for distributing jobs to workers |
| **Spring Data JPA** | Simplified database operations |
| **Jackson** | JSON serialization for job payloads |
| **Lombok** | Reduced boilerplate code |

## Getting Started

### Prerequisites

```bash
- Java 17
- PostgreSQL 15
- Redis 7
- Maven
```

### Installation Steps

**1. Install PostgreSQL and Redis**
```bash
# On Ubuntu/Debian
sudo apt install postgresql redis-server

# On Mac
brew install postgresql redis

# Or use Docker
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=postgres postgres:15
docker run -d -p 6379:6379 redis:7
```

**2. Clone and configure**
```bash
git clone https://github.com/yourusername/chronoqueue.git
cd chronoqueue
```

**3. Update `application.yml`**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/chronoqueue
    username: postgres
    password: postgres
  data:
    redis:
      host: localhost
      port: 6379
```

**4. Run the application**
```bash
mvn clean install
mvn spring-boot:run
```

**5. Test with multiple workers (optional)**
```bash
# Terminal 1
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080

# Terminal 2
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

## API Usage

### Create a Job

**POST** `http://localhost:8080/api/jobs`

```json
{
  "queueType": "EMAIL",
  "taskType": "email.send",
  "payload": {
    "userId": 123,
    "email": "user@example.com",
    "message": "Welcome to our platform!"
  },
  "scheduledAt": "2025-10-24T18:00:00Z",
  "priority": 100,
  "maxAttempts": 5,
  "idempotencyKey": "welcome-email-user-123"
}
```

**Response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "queueType": "EMAIL",
  "state": "PENDING",
  "priority": 100,
  "createdAt": "2025-10-24T17:00:00Z",
  "scheduledAt": "2025-10-24T18:00:00Z"
}
```

### Check Job Status

**GET** `http://localhost:8080/api/jobs/{jobId}`

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "queueType": "EMAIL",
  "state": "SUCCEEDED",
  "attempts": 1,
  "createdAt": "2025-10-24T17:00:00Z"
}
```

### Get All Jobs

**GET** `http://localhost:8080/api/jobs`

## Architecture

### Components

```
┌─────────────────┐
│  REST API       │ ← Client creates jobs
│  (JobService)   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│  PostgreSQL     │ ← Jobs stored here (source of truth)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│SchedulerService │ ← Scans for due jobs every 5 seconds
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│     Redis       │ ← Fast queue (job IDs pushed here)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ WorkerService   │ ← Pulls jobs, executes them
└─────────────────┘
         │
         ▼
┌─────────────────┐
│LeaseReaperService│ ← Recovers stuck jobs
└─────────────────┘
```

### Job Lifecycle

```
PENDING ──┐
          │
          ├─► RUNNING ──┐
          │             │
          │             ├─► SUCCEEDED ✅
          │             │
          │             ├─► PENDING (retry with backoff) 🔁
          │             │
          │             └─► DEAD (max attempts) ❌
```

### Running Multiple Workers

When you run 3 instances:

```
Worker 1 (port 8080)    Worker 2 (port 8081)    Worker 3 (port 8082)
      │                        │                        │
      └────────────────────────┼────────────────────────┘
                               │
                    ┌──────────┴──────────┐
                    │                     │
              PostgreSQL              Redis
```

**Key Point:** All workers share the same database and Redis, but they don't step on each other's toes because of **distributed locking**.

## Key Concepts

### 1. Distributed Locking

**Problem:** If 2 workers pull the same job from Redis, both might process it.

**Solution:**
```java
@Transactional
public boolean acquireLease(JobEntity job) {
    // Re-fetch job from database
    JobEntity fresh = jobRepo.findById(job.getId()).get();
    
    // Check if still PENDING
    if (fresh.getState() != JobState.PENDING) {
        return false; // Another worker already claimed it
    }
    
    // Claim it atomically
    fresh.setState(JobState.RUNNING);
    fresh.setOwnerWorkerId(workerId);
    fresh.setLeaseExpiresAt(now + 30 seconds);
    jobRepo.save(fresh);
    
    return true;
}
```

Only one worker succeeds in changing state from PENDING → RUNNING. Others fail and skip the job.

### 2. Lease Management

Workers claim jobs with a 30-second "lease". Every 10 seconds, they send a heartbeat to extend it:

```java
@Scheduled(fixedRate = 10000)
public void sendHeartbeat() {
    // Extend lease for all my jobs
    for (JobEntity job : myJobs) {
        job.setLeaseExpiresAt(now + 30 seconds);
        jobRepo.save(job);
    }
}
```

If a worker crashes, it stops sending heartbeats. After 30 seconds, the lease expires and LeaseReaperService requeues the job.

### 3. Exponential Backoff

When jobs fail, we don't retry immediately:

```
Attempt 1 fails → Wait 5 seconds
Attempt 2 fails → Wait 10 seconds
Attempt 3 fails → Wait 20 seconds
Attempt 4 fails → Wait 40 seconds
Attempt 5 fails → Mark as DEAD
```

This prevents overwhelming external services with retry spam.

### 4. Idempotency

If a client sends the same job creation request twice:

```java
if (request.idempotencyKey() != null) {
    Optional<JobEntity> existing = jobRepo.findByIdempotencyKey(key);
    if (existing.isPresent()) {
        return existing.get(); // Return existing job, don't create duplicate
    }
}
```

Same `idempotencyKey` = same job. Only created once.

### 5. Thread Safety

The in-memory job queue is accessed by multiple scheduled methods:

```java
// Thread-safe collections
private final Queue<JobEntity> jobQueue = new ConcurrentLinkedQueue<>();
private final Set<UUID> queuedJobIds = Collections.synchronizedSet(new HashSet<>());
```

`ConcurrentLinkedQueue` allows multiple threads to add/remove safely without corruption.

## What I Learned

### Technical Skills

1. **Spring Boot Scheduling** - Used `@Scheduled` annotations for periodic tasks
2. **Database Transactions** - Learned when to use `@Transactional` to prevent race conditions
3. **Redis Operations** - Used Redis lists (LPUSH/RPOP) for queue management
4. **Concurrency** - Understood thread-safe collections and synchronization
5. **State Machines** - Designed clear state transitions for job lifecycle
6. **Distributed Systems Basics** - Learned about distributed locking, leases, and fault tolerance

### Design Decisions

**Why Redis + PostgreSQL?**
- PostgreSQL = Durable storage (survives crashes)
- Redis = Fast distribution (low latency)
- Together = Best of both worlds

**Why distributed locking?**
- Multiple workers need to coordinate
- Can't rely on shared memory (separate processes)
- Database provides atomic operations we need

**Why exponential backoff?**
- Fixed delays don't work well
- Too fast = overwhelm failing services
- Exponential = give services time to recover

### Problems I Solved

**Problem 1: Race Conditions**
- Initial code used `PriorityQueue` (not thread-safe)
- Fixed by using `ConcurrentLinkedQueue`

**Problem 2: Jobs Processed Twice**
- Multiple workers pulled same job from Redis
- Fixed with `acquireLease()` distributed lock

**Problem 3: Lost Jobs on Crash**
- Workers crashed, jobs stuck forever
- Fixed with `LeaseReaperService` checking expired leases

**Problem 4: Redis Downtime**
- System broke when Redis unavailable
- Fixed with database fallback query

## Future Improvements

**Things I want to add:**
- [ ] Web UI to view job status and queue depths
- [ ] Metrics (how many jobs succeeded/failed per hour)
- [ ] Support for cron-like recurring jobs
- [ ] Job dependencies (Job B runs only after Job A succeeds)
- [ ] Dead letter queue for manual inspection of failed jobs
- [ ] Better error messages and logging
- [ ] Integration tests

**If I were building this for production:**
- Add proper monitoring (Prometheus + Grafana)
- Implement circuit breakers for external API calls
- Add authentication/authorization
- Use connection pooling for better performance
- Add rate limiting per queue type
- Implement graceful shutdown handling
- Add more comprehensive error handling

## Project Structure

```
chronoqueue/
├── src/main/java/com/sde/chronoqueue/
│   ├── entities/
│   │   └── JobEntity.java          # Database model
│   ├── enums/
│   │   ├── JobState.java           # PENDING, RUNNING, etc.
│   │   └── QueueType.java          # EMAIL, NOTIFICATION, etc.
│   ├── dtos/
│   │   ├── JobCreateRequest.java   # API request format
│   │   └── JobCreateResponse.java  # API response format
│   ├── repositories/
│   │   └── JobEntityRepository.java # Database queries
│   ├── services/
│   │   ├── JobService.java          # Job creation/query API
│   │   ├── SchedulerService.java    # Moves jobs to Redis
│   │   ├── WorkerService.java       # Executes jobs
│   │   ├── LeaseReaperService.java  # Recovery service
│   │   └── RedisRecoveryService.java # Startup recovery
│   └── ChronoQueueApplication.java  # Main class
├── application.yml                   # Configuration
└── pom.xml                          # Dependencies
```

## Common Issues

**Issue: "Connection refused to PostgreSQL"**
```bash
# Make sure PostgreSQL is running
sudo systemctl start postgresql

# Or if using Docker
docker start postgres-container
```

**Issue: "Connection refused to Redis"**
```bash
# Make sure Redis is running
sudo systemctl start redis

# Or if using Docker
docker start redis-container
```

**Issue: Jobs stuck in PENDING**
- Check if SchedulerService is running (look for logs)
- Check if Redis is accessible
- Verify `scheduledAt` time is in the past

**Issue: Jobs not processing**
- Check if WorkerService is running (look for "⚙️ Executing job" logs)
- Verify Redis has job IDs (`redis-cli` → `LLEN chrono:queue:email:ready`)

## Acknowledgments

Inspired by:
- **Sidekiq** - Ruby background job processor
- **Celery** - Python distributed task queue
- **Bull** - Node.js Redis-based queue

Built as a learning project to understand how distributed job schedulers work.

---

**Made by an engineering graduate trying to understand distributed systems 🎓**