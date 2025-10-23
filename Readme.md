⚡ ChronoQueue — Distributed Job Scheduling Framework
🚀 Overview

ChronoQueue is a distributed delayed job scheduling framework — built with Spring Boot, Redis, and PostgreSQL.

It’s designed to schedule, persist, and execute background jobs reliably — even when services crash, restart, or scale.
Think of it as building your own version of Celery (Python) or Sidekiq (Ruby) — but focused on understanding the architecture, not just the APIs.

💡 What It Does

ChronoQueue takes in jobs that need to run in the future — for example:

Send an email at a specific time

Generate a report every few minutes

Trigger a webhook when data is ready

It queues, stores, and executes them automatically at the right time — handling retries, failures, and prioritization.

🎯 Why I Built It

I built ChronoQueue to explore how real-world job scheduling systems work internally:

How can you reliably delay tasks?

What happens if the system crashes mid-job?

How do workers coordinate without stepping on each other?

How can data structures (DSA) like priority queues be used for efficient scheduling?

Instead of using pre-built libraries, I built it from scratch to deeply understand:

“How distributed systems schedule work, maintain state, and recover from failure.”

This project bridges algorithms + system design — turning DSA (PriorityQueue, backoff logic) into real backend infrastructure.

🧠 Architecture Overview

ChronoQueue is made of three main components:

1️⃣ Job Creation API (JobService + JobController)

Accepts new jobs via REST API

Validates and stores job details in PostgreSQL

Example payload:

{
"queueType": "EMAIL",
"taskType": "email.send",
"payload": {
"userId": 42,
"email": "user42@example.com",
"template": "welcome"
},
"scheduledAt": "2025-10-22T11:30:00Z",
"priority": 100,
"maxAttempts": 3
}


Each job is stored in DB with state = PENDING.

2️⃣ Scheduler Service

Runs periodically (using @Scheduled).

Picks jobs whose scheduledAt <= now() and state = PENDING.

Pushes them into a Redis list (chrono:queue:<type>:ready).

Acts like a bridge between persistent storage (DB) and fast distributed memory (Redis).

🧩 Goal:
Redis now acts as a shared queue — accessible by multiple worker nodes in future.

3️⃣ Worker Service

Periodically polls Redis and DB for ready jobs.

Maintains a PriorityQueue (min-heap) in memory, ordered by:

scheduledAt (earliest first)

then by priority (higher first)

Simulates job execution (mocked for now).

Execution Logic:

Marks job → RUNNING

Simulates success/failure (60% success rate)

On failure → retry with exponential backoff (5s, 10s, 20s...)

Updates DB each time with state transitions:

PENDING → RUNNING → SUCCEEDED

or → FAILED → RETRY

or → DEAD (after max attempts)

This worker is the brain of the system — handling DSA-driven scheduling, retries, and state management.

⚙️ How It Works (Flow Diagram)
sequenceDiagram
participant API as JobController
participant DB as PostgreSQL
participant Redis as Redis Queue
participant Scheduler as SchedulerService
participant Worker as WorkerService

    API->>DB: Save new Job (state=PENDING)
    Scheduler->>DB: Fetch PENDING jobs with time <= now
    Scheduler->>Redis: Push job IDs to chrono:queue:<type>:ready
    Worker->>Redis: Pop job IDs
    Worker->>DB: Fetch job details
    Worker->>Worker: Add to PriorityQueue
    Worker->>Worker: Execute job at scheduled time
    Worker->>DB: Update job state (RUNNING → SUCCEEDED/FAILED)
    Worker->>DB: Retry with backoff if needed

🧩 Core Features
Feature	Description
Job Persistence	PostgreSQL stores all jobs with full metadata (state, attempts, retries).
In-Memory Scheduling	Uses Java PriorityQueue (min-heap) for efficient job execution ordering.
Fault Tolerance	DB ensures recovery after crashes — no job is lost.
Retry System	Exponential backoff with max attempts logic.
Redis Queue	Connects scheduler and worker asynchronously — ready for distributed scaling.
Thread-safe Execution	Workers process only ready jobs at correct timestamps.
Extensible Job Types	Supports multiple queues (email, notification, report).
🧮 Data Structures & DSA Integration

ChronoQueue applies DSA concepts in real-world backend code:

DSA Concept	Real Usage
Priority Queue (Heap)	Orders jobs by scheduledAt and priority efficiently (O(log n)).
Exponential Backoff (Math logic)	Controls retry delay dynamically based on attempt count.
State Machine (Enum)	Tracks job lifecycle through predictable transitions.

Why this matters:
Most developers only solve DSA problems — here, you’ve used them to design a live system.

🔁 Example Job Lifecycle
State	Description
PENDING	Job is waiting to be scheduled
RUNNING	Worker is executing the job
SUCCEEDED	Job completed successfully
FAILED	Temporary error occurred
RETRY	Scheduled again with delay
DEAD	Max retries exceeded
🔧 Technologies Used
Layer	Stack
Backend	Spring Boot (Java 17)
Database	PostgreSQL (via JPA/Hibernate)
Queue System	Redis
Scheduler	Spring Task Scheduler
Serialization	Jackson (JSON Mapper)
DSA Concepts	PriorityQueue, Exponential Backoff
🧱 Architecture Summary
Component	Purpose
JobController	Accepts API requests to create jobs
JobService	Saves job with metadata and validation
SchedulerService	Moves ready jobs from DB → Redis
WorkerService	Pulls from Redis, runs via PriorityQueue
PostgreSQL	Persistent storage for job state tracking
Redis	Fast in-memory distributed queue
⚙️ Design Principles

Reliability First: Every job persisted before execution.

Recoverable by Design: System resumes safely after restart.

Decoupled Components: Job creation, scheduling, and execution separated.

Extensible: Adding new queue types or executors is trivial.

DSA-Driven: Algorithms power scheduling and retry logic.

🧩 Future Enhancements

🔒 Distributed Locks (for multi-worker scaling)

📈 Dashboard UI for job monitoring

⚙️ Worker Pool System for concurrency limits

📬 Custom Job Executors (HTTP calls, email, ML pipelines)

🔁 Cron-based Recurring Jobs

🧠 What I Learned

How background schedulers like Celery, BullMQ, and Sidekiq actually work under the hood.

How to connect PostgreSQL persistence + Redis coordination + in-memory DSA into one system.

How to design for fault tolerance, retries, and recovery — not just happy paths.

How algorithms and system design principles combine to create real-world reliability.

🧩 Example Demo Flow

Create a job from Postman:

POST /api/jobs
Content-Type: application/json

{
"queueType": "EMAIL",
"taskType": "email.send",
"payload": { "email": "user@example.com" },
"scheduledAt": "2025-10-22T11:30:00Z",
"priority": 100,
"maxAttempts": 3
}


Scheduler detects the job at correct time → pushes to Redis

Worker picks it up → executes → retries or marks success

PostgreSQL logs every state transition

🏁 Summary

ChronoQueue isn’t about sending emails —
it’s about mastering how jobs are scheduled, retried, and recovered across distributed systems.
It demonstrates understanding of:

Algorithms (priority queues, backoff)

System design (fault tolerance, decoupling)

Practical reliability (state tracking, retry logic)

It’s both a DSA-backed learning project and a miniature version of a real production scheduler —
designed to showcase how an engineer thinks, not just codes.