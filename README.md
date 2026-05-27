# Distributed Task Scheduler

A production-grade distributed task scheduler built with Java 21, Spring Boot 3.5, Apache Kafka, Redis, and PostgreSQL. Designed to demonstrate FAANG-level backend engineering practices including distributed systems patterns, observability, fault tolerance, and horizontal scalability.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client / API Layer                       │
│                    REST API  (Spring Boot)                      │
└────────────────────────────┬────────────────────────────────────┘
                             │
              ┌──────────────▼──────────────┐
              │        Domain Layer          │
              │   Task State Machine         │
              │   Business Rules             │
              │   Idempotency Logic          │
              └──────┬───────────┬───────────┘
                     │           │
         ┌───────────▼──┐   ┌───▼────────────┐
         │   PostgreSQL  │   │  Apache Kafka   │
         │  Task Registry│   │  Task Events    │
         │  Flyway Mgmt  │   │  KRaft Mode     │
         └───────────────┘   └───────┬─────────┘
                                     │
                         ┌───────────▼─────────┐
                         │    Worker Layer       │
                         │  Kafka Consumers      │
                         │  Redis Dist. Locking  │
                         │  Retry + DLQ Logic    │
                         └─────────────────────┘
                                     │
              ┌──────────────────────▼──────────────────────┐
              │              Observability Stack              │
              │         Prometheus  →  Grafana               │
              │         Micrometer Metrics + Health          │
              └─────────────────────────────────────────────┘
```

### Package Structure (Hexagonal Architecture)

```
src/main/java/com/taskscheduler/
├── api/                    # REST controllers, DTOs, request/response mappers
│   └── controller/
├── domain/                 # Core business logic — zero infrastructure dependencies
│   ├── model/              # Task entity, TaskStatus state machine
│   ├── repository/         # Repository interfaces (not implementations)
│   ├── service/            # Application services, transaction boundaries
│   └── exception/          # Domain-specific exceptions
├── worker/                 # Kafka consumers, task executors, distributed locking
│   ├── consumer/
│   ├── executor/
│   └── lock/
├── infrastructure/         # External system adapters (Kafka, Redis, JPA)
│   ├── kafka/
│   ├── redis/
│   └── persistence/
└── config/                 # Spring @Configuration classes
```

---

## Technology Stack

| Component | Technology | Version | Purpose |
|---|---|---|---|
| Language | Java | 21 (LTS) | Virtual threads (Project Loom) |
| Framework | Spring Boot | 3.5.14 | Application framework |
| Messaging | Apache Kafka | 3.7.1 (KRaft) | Task event streaming |
| Cache / Lock | Redis | 7.2 | Distributed locking, caching |
| Database | PostgreSQL | 16 | Task persistence |
| Migration | Flyway | 10.x | Schema version control |
| Metrics | Micrometer + Prometheus | 1.14.x / 2.47 | Observability |
| Dashboards | Grafana | 10.2 | Metrics visualization |
| Containers | Docker + Compose | 29.x / 5.x | Local infrastructure |
| Build | Maven | 3.9.x | Dependency management |
| Testing | JUnit 5 + Mockito + Testcontainers | — | Multi-layer test strategy |
| Arch Tests | ArchUnit | 1.2.1 | Architecture rule enforcement |

---

## Key Engineering Decisions

**KRaft Mode (No ZooKeeper):** Kafka runs in KRaft mode, eliminating ZooKeeper as an operational dependency. ZooKeeper is deprecated in Kafka 3.x and removed in 4.0. KRaft simplifies the deployment topology and reduces failure surface area.

**Hexagonal Architecture:** The domain layer has zero imports from Kafka, Redis, or Spring Data. All external dependencies are accessed through interfaces defined in the domain layer and implemented in the infrastructure layer. This means the entire business logic is testable without starting any infrastructure.

**Dual Kafka Listener Config:** Kafka exposes two listeners — `EXTERNAL://localhost:9092` for the host-based application and `PLAINTEXT://kafka:29092` for Docker-internal clients. This prevents the common "advertised listener" bug where Docker-internal clients receive host-only addresses.

**Optimistic Locking:** Task entities carry a `@Version` field managed by Hibernate. Concurrent worker updates trigger `OptimisticLockException` rather than silent data corruption. Combined with Redis distributed locks in the worker layer, this provides defense-in-depth against duplicate task execution.

**Virtual Threads:** `spring.threads.virtual.enabled=true` enables Java 21 Project Loom virtual threads. Task workers performing blocking I/O (DB queries, Redis calls, Kafka polls) are scheduled on lightweight virtual threads instead of platform threads, dramatically increasing throughput per CPU core.

---

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| Java | 21 (LTS) | `java -version` |
| Docker Desktop | 4.30+ | `docker --version` |
| Docker Compose | V2 (v5.x) | `docker compose version` |
| IntelliJ IDEA | 2024+ (Ultimate recommended) | — |

---

## Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/PubuduGunasekara/distributed-task-scheduler.git
cd distributed-task-scheduler
```

### 2. Configure Environment

```bash
cp .env.example .env
# Edit .env if you need to change any defaults
```

### 3. Start Infrastructure

```bash
make infra-up
```

Wait ~30 seconds for all services to become healthy, then verify:

```bash
docker compose ps
```

All five services should show `(healthy)`:
- `taskscheduler-postgres`
- `taskscheduler-redis`
- `taskscheduler-kafka`
- `taskscheduler-prometheus`
- `taskscheduler-grafana`

### 4. Run the Application

**Option A — IntelliJ (recommended for development):**

1. Open Run Configurations → `TaskScheduler [local]`
2. Confirm Active Profile is set to `local`
3. Click Run ▶

**Option B — Terminal:**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### 5. Verify Everything is Running

```bash
# Application status
curl http://localhost:8080/api/v1/status

# Component health (db, redis, kafka should all be UP)
curl http://localhost:8080/actuator/health

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus | head -20
```

---

## Service URLs

| Service | URL | Credentials |
|---|---|---|
| Application API | http://localhost:8080 | — |
| Health Check | http://localhost:8080/actuator/health | — |
| Prometheus Metrics | http://localhost:8080/actuator/prometheus | — |
| Prometheus UI | http://localhost:9090 | — |
| Grafana Dashboards | http://localhost:3000 | admin / admin |
| PostgreSQL | localhost:5432 | taskscheduler / taskscheduler |
| Redis | localhost:6379 | — |
| Kafka | localhost:9092 | — |

---

## API Reference

### System

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/v1/status` | Application status and version |
| GET | `/actuator/health` | Component health (db, redis, kafka) |
| GET | `/actuator/prometheus` | Prometheus-format metrics |

> More endpoints added in each milestone — see [Milestones](#milestones) below.

---

## Running Tests

```bash
# Unit tests only (no Docker required, runs in ~5 seconds)
./mvnw test -Dtest="*Test"

# All tests including integration tests (Docker required)
./mvnw test

# Full build with coverage report + 80% threshold enforcement
./mvnw verify

# View coverage report
open target/site/jacoco/index.html
```

### Test Strategy

This project follows a strict four-layer testing pyramid:

| Layer | Annotation | What It Tests | Speed |
|---|---|---|---|
| Unit | `@ExtendWith(MockitoExtension.class)` | Pure business logic, state machines, edge cases | ~50ms |
| Repository | `@DataJpaTest` + Testcontainers | Real SQL queries, indexes, constraints, migrations | ~5s |
| API | `@WebMvcTest` | Controllers, validation, HTTP contracts, error responses | ~2s |
| Architecture | `@AnalyzeClasses` (ArchUnit) | Package dependency rules, naming conventions | ~1s |

Coverage is enforced at **80% line and branch coverage** via JaCoCo on every `./mvnw verify` run. The CI pipeline fails if coverage drops below this threshold.

---

## Development Workflow

```bash
make infra-up       # Start all Docker services
make infra-down     # Stop services (keep data volumes)
make infra-clean    # Stop services + delete all data
make infra-logs     # Tail all service logs
make build          # Build without tests
make test           # Run all tests
```

---

## Milestones

| Milestone | Status | Description |
|---|---|---|
| M0 | ✅ Complete | Project scaffold, Docker infrastructure, health checks, observability |
| M1 | 🔄 In Progress | Task domain model, state machine, Flyway schema, repository layer |
| M2 | ⏳ Planned | REST API — task submission, validation, error handling, @WebMvcTest |
| M3 | ⏳ Planned | Kafka producer — task event publishing, idempotency, at-least-once delivery |
| M4 | ⏳ Planned | Kafka consumer — worker polling, offset management, consumer groups |
| M5 | ⏳ Planned | Redis distributed locking — SETNX, TTL, fencing tokens, deadlock prevention |
| M6 | ⏳ Planned | Retry strategy + Dead Letter Queue — exponential backoff, poison pill handling |
| M7 | ⏳ Planned | Observability — custom Micrometer metrics, Grafana dashboards, alerting |
| M8 | ⏳ Planned | Rate limiting — Redis token bucket, per-client limits |
| M9 | ⏳ Planned | GitHub Actions CI/CD — build, test, coverage gate, Docker image push |
| M10 | ⏳ Planned | Cloud readiness — externalized config, secrets management, production hardening |

---

## Distributed Systems Concepts Demonstrated

- **At-least-once delivery** — Kafka consumer manual offset commit after successful processing
- **Idempotent producers** — Kafka producer deduplication via sequence numbers
- **Optimistic concurrency control** — `@Version`-based conflict detection without row locking
- **Distributed mutual exclusion** — Redis SETNX with TTL-based automatic lock expiration
- **Event-driven state machine** — Task lifecycle managed through explicit state transitions
- **Defense in depth** — Multiple independent guards against duplicate task execution
- **Graceful degradation** — Circuit breakers and retry policies with exponential backoff
- **Observable systems** — Structured logging, Micrometer metrics, Prometheus scraping

---

## Interview Talking Points

**"Why Kafka over a database queue?"**
Kafka provides horizontal scalability through partition-based parallelism, durable message replay, and decoupled producer/consumer scaling. A database queue (polling) creates write contention at scale and can't replay historical events for auditing or reprocessing.

**"How do you prevent a task from running twice?"**
Defense in depth: (1) Redis distributed lock prevents two workers from claiming the same task ID simultaneously. (2) Optimistic locking at the database layer means a concurrent update throws `OptimisticLockException` before any state transition commits. Either guard alone is sufficient; both together make the system robust to partial failures.

**"How does your system scale horizontally?"**
Workers are stateless Kafka consumers. Adding more worker instances automatically triggers Kafka's consumer group rebalancing, distributing topic partitions across available workers. PostgreSQL scales reads via read replicas; Redis scales via cluster mode. The only stateful component is the task registry (PostgreSQL), which is accessed through a connection pool with explicit limits.

---

## Project Status

Built as a co-op preparation project for FAANG-level backend engineering internships (January 2027). Each milestone is designed to introduce one distributed systems concept at production depth, with full test coverage and git history that demonstrates professional engineering practices.

---

## License

MIT License — see [LICENSE](LICENSE) for details.