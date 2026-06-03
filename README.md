<div align="center">

# Distributed Task Scheduler

### Schedule background jobs over an API, run them reliably across multiple workers, and watch the whole system in real time

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-3.7-231F20?style=flat-square&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)
[![Redis](https://img.shields.io/badge/Redis-7.2-DC382D?style=flat-square&logo=redis&logoColor=white)](https://redis.io/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-4169E1?style=flat-square&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Prometheus](https://img.shields.io/badge/Prometheus-Metrics-E6522C?style=flat-square&logo=prometheus&logoColor=white)](https://prometheus.io/)
[![Grafana](https://img.shields.io/badge/Grafana-Dashboards-F46800?style=flat-square&logo=grafana&logoColor=white)](https://grafana.com/)
[![Docker](https://img.shields.io/badge/Docker-Containerized-2496ED?style=flat-square&logo=docker&logoColor=white)](https://www.docker.com/)

[![CI/CD Pipeline](https://github.com/PubuduGunasekara/distributed-task-scheduler/actions/workflows/ci.yml/badge.svg)](https://github.com/PubuduGunasekara/distributed-task-scheduler/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

</div>

---

## Demo

<!-- ADD YOUR DEMO HERE.
     Easiest way to host a video on GitHub:
     1. Record a 60-90s screen capture (.mp4).
     2. Open a new GitHub Issue in this repo and drag the .mp4 into the comment box.
     3. GitHub uploads it and gives you a URL. Copy that URL and paste it below, then close the issue (the video stays hosted).
     For images: save them in docs/screenshots/ and uncomment the table below. -->

A short walkthrough video is coming soon.

<!--
| Creating a task | Live Grafana dashboard |
|:---:|:---:|
| ![Create a task](docs/screenshots/01-create-task.png) | ![Grafana metrics](docs/screenshots/02-grafana.png) |
-->

---

## What is this, in plain English?

Imagine your app needs to do work that shouldn't happen while a user waits, like sending 10,000 emails, generating a report, or processing an upload. You don't want your web server stuck doing that. Instead, you hand the job to a task scheduler: it accepts the job, stores it safely, and lets background workers pick it up and run it.

This project is that system, built the way real production services such as AWS SQS or Google Cloud Tasks work. "Distributed" means the work is spread across several workers running at the same time, so it scales out, and if one worker crashes the others keep going and nothing is lost.

---

## Why it's interesting

Running background jobs *reliably* is harder than it looks. Here are the real problems this system handles, in plain terms:

- **Run a job at least once, but never twice.** Kafka guarantees a job reaches a worker at least once, but a hiccup can deliver it twice. A database-backed state machine acts as a guard so the same job can't actually execute twice.
- **Let only one worker touch a job at a time.** When workers run in parallel, two could grab the same job. A Redis distributed lock ensures exactly one runs it. The lock is released with a small Lua script, so a worker can never accidentally release another worker's lock.
- **Don't give up on failures, but don't hammer them either.** When a job fails, it is retried with exponential backoff (wait 10s, then 30s, then 90s) instead of retrying instantly forever.
- **Never silently lose a job.** Once retries are exhausted, the job moves to a Dead-Letter Queue so it can be inspected later rather than dropped.
- **See what's happening.** Every component reports metrics to Prometheus, which are visualized in Grafana dashboards.
- **Keep the code clean.** The business logic is isolated from the infrastructure with a ports-and-adapters (hexagonal) architecture, and a build-time ArchUnit test fails the build if that boundary is ever broken.

---

## Architecture

```mermaid
graph TB
    Client([HTTP Client]) -->|POST /api/v1/tasks| RL[Rate Limiter<br/>Redis token bucket]
    RL -->|429 if over limit| Client
    RL -->|allowed| API[REST API<br/>Spring Boot]
    API -->|save| DB[(PostgreSQL)]
    API -->|publish event| K[Apache Kafka]

    K -->|consume| W1[Worker]
    K -->|consume| W2[Worker]
    K -->|consume| W3[Worker]

    W1 -.acquire lock.-> R[(Redis)]
    W2 -.acquire lock.-> R
    W3 -.acquire lock.-> R

    W1 -->|update status| DB
    W2 -->|update status| DB
    W3 -->|update status| DB

    RS[Retry Scheduler<br/>every 30s] -->|find failed jobs| DB
    RS -->|re-publish with backoff| K
    DB -->|retries exhausted| DLQ[Dead-Letter Queue]

    API -->|metrics| P[Prometheus] -->|dashboards| G[Grafana]
```

The flow, step by step: a request comes in, the rate limiter checks it, the task is saved to PostgreSQL and an event is published to Kafka. A free worker consumes the event, grabs a Redis lock, runs the task, and updates its status. If it failed, the retry scheduler re-publishes it with backoff. If it keeps failing, it lands in the dead-letter queue. Prometheus and Grafana watch the whole thing.

---

## Tech Stack

| Layer | Technology | Why |
|---|---|---|
| Language | Java 21 | Virtual threads, records, pattern matching |
| Framework | Spring Boot 3.5 | REST API, dependency injection, scheduling |
| Messaging | Apache Kafka 3.7 (KRaft) | Decouples the API from the workers |
| Database | PostgreSQL 16 (Flyway) | Source of truth for task state |
| Cache and locks | Redis 7.2 | Distributed locking and rate limiting |
| Observability | Micrometer, Prometheus, Grafana | Metrics and dashboards |
| Quality | Maven, JaCoCo (80% gate), ArchUnit | Tests and enforced architecture |
| Packaging | Docker (multi-stage), GitHub Actions | Build, test, containerize |

---

## Getting Started

### Prerequisites
- Docker and Docker Compose (for Postgres, Redis, Kafka, Prometheus, Grafana)
- Java 21 (the project ships with `./mvnw`, so a separate Maven install isn't required)

### 1. Start the infrastructure
```bash
make infra-up        # starts Postgres, Redis, Kafka, Prometheus, Grafana
```

### 2. Run the application
```bash
./mvnw spring-boot:run
```
The API starts on http://localhost:8080

### 3. Open the dashboards

| Service | URL |
|---|---|
| API status | http://localhost:8080/api/v1/status |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 |

### Useful commands
```bash
make build       # compile and package
make test        # run the test suite
make infra-logs  # tail the infrastructure logs
make infra-down  # stop the infrastructure
make infra-clean # stop and remove all data volumes
```

---

## API Reference

Base path: `/api/v1`

| Method | Endpoint | What it does |
|---|---|---|
| `POST` | `/tasks` | Create and schedule a new task |
| `GET` | `/tasks/{id}` | Get a single task by its ID |
| `GET` | `/tasks/due` | List tasks that are due to run |
| `PATCH` | `/tasks/{id}/cancel` | Cancel a pending task |
| `GET` | `/status` | System health and status |

Example, creating a task:
```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{
    "type": "SEND_EMAIL",
    "payload": { "to": "user@example.com" },
    "scheduledAt": "2027-01-01T10:00:00Z"
  }'
```

---

## Project Structure

```
src/main/java/com/taskscheduler/
├── domain/          # Core business logic and ports (interfaces). No framework code.
├── application/     # Use cases that orchestrate the domain
├── infrastructure/  # Adapters: Redis lock, Kafka, PostgreSQL (the "how")
├── api/             # REST controllers (the entry point)
└── config/          # Spring wiring
```

The `domain` layer depends on nothing external. That boundary is checked automatically by an ArchUnit test, so the architecture can't quietly rot over time.

---

## What I Learned

This project was a hands-on study of the patterns behind real distributed systems: at-least-once delivery and idempotency, safe distributed locking, retry and backoff strategies, dead-letter handling, and production-grade observability, while keeping all of it testable through a clean architecture.

---

## Author

**Pubudu Gunasekara**
M.S. Computer Science, Northeastern University (Silicon Valley)
Backend and distributed systems. Open to a software engineering co-op (Jan to Aug 2027).

[![Portfolio](https://img.shields.io/badge/Portfolio-Visit-0A0A0A?style=flat-square&logo=googlechrome&logoColor=white)](https://pubudugunasekara.github.io/)
[![LinkedIn](https://img.shields.io/badge/LinkedIn-Connect-0A66C2?style=flat-square&logo=linkedin&logoColor=white)](https://www.linkedin.com/in/pubudugunasekera/)
[![GitHub](https://img.shields.io/badge/GitHub-Follow-181717?style=flat-square&logo=github&logoColor=white)](https://github.com/PubuduGunasekara)

---

<div align="center">

**Thanks for checking out this project.**
If you found it useful or interesting, consider leaving a star, and feel free to reach out about backend, distributed systems, or co-op opportunities.

Built by Pubudu Gunasekara · MIT License

</div>
