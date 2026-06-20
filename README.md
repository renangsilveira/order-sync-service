# order-sync-service

> Kotlin · Spring Boot 3 · PostgreSQL · Coroutines · Resilience4j · Testcontainers · WireMock

[![CI](https://github.com/renangsilveira/order-sync-service/actions/workflows/ci.yml/badge.svg)](https://github.com/renangsilveira/order-sync-service/actions/workflows/ci.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-6DB33F?logo=spring-boot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?logo=postgresql&logoColor=white)](https://www.postgresql.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](./LICENSE)

---

## What it does

`order-sync-service` is a production-grade **order intake and ERP synchronisation microservice**.

It receives purchase orders from upstream systems (e-commerce, mobile apps, B2B partners), persists them durably, and reliably syncs them to a downstream ERP over HTTP — all without coupling the caller's availability to the ERP's availability.

The service:
- accepts orders immediately and returns `202 Accepted`
- persists the order atomically before responding
- dispatches ERP synchronisation **asynchronously** via Kotlin Coroutines
- retries transient ERP failures with **exponential backoff** (Resilience4j)
- guarantees **duplicate-safe order creation** via `Idempotency-Key`, safe under concurrent requests
- maintains an **immutable audit trail** of every state transition and integration attempt
- exposes a manual **reprocessing endpoint** for operations teams

---

## Technical highlights

- **Idempotent order ingestion** — `Idempotency-Key` header + DB unique constraint + race condition recovery via `DataIntegrityViolationException` catch
- **Async processing with coroutine context propagation** — `MDCContext()` carries the `correlationId` from the HTTP thread into the background coroutine
- **Resilient ERP integration** — transient errors (5xx, timeout) are retried up to 3 times with exponential backoff; permanent errors (4xx) abort immediately
- **Full audit trail** — every attempt, status transition, and event is persisted independently with `REQUIRES_NEW` transaction propagation
- **Real PostgreSQL + Flyway** — schema managed via versioned SQL migrations; `ddl-auto: validate` in production
- **End-to-end tests with WireMock and Testcontainers** — no mocks for the DB or HTTP layer in integration tests
- **Concurrency-safe order creation** — verified by an 8-thread concurrent-request test

---

## Architecture

```
┌──────────────────────────────────────┐
│         Upstream Systems             │
│   (mobile apps, e-commerce, B2B)    │
└──────────────────┬───────────────────┘
                   │ HTTP POST /api/v1/orders
                   │ + Idempotency-Key header
┌──────────────────▼───────────────────┐
│          OrderController             │
│  validates · extracts idempotency    │
└──────────────────┬───────────────────┘
                   │
┌──────────────────▼───────────────────┐
│            OrderService              │
│  idempotency check → persist →       │
│  record ORDER_RECEIVED event →       │
│  202 response → launch coroutine     │
└──────────────────┬───────────────────┘
                   │ (async, non-blocking)
┌──────────────────▼───────────────────┐
│       OrderProcessingService         │
│  (Kotlin Coroutines + MDCContext)    │
│  PROCESSING → ERP call → SYNCED      │
│                        or FAILED     │
└──────────────────┬───────────────────┘
                   │ RestClient (sync, Dispatchers.IO)
┌──────────────────▼───────────────────┐
│       ErpIntegrationClient           │
│   Resilience4j Retry · 3 attempts   │
└──────────────────┬───────────────────┘
                   │ HTTP
┌──────────────────▼───────────────────┐
│          External ERP System         │
│       (WireMock in dev/test)         │
└──────────────────────────────────────┘

               PostgreSQL
  orders · order_items · processing_events
          · integration_attempts
```

---

## Main flow

```
POST /api/v1/orders
  │
  ├─ idempotency check (SELECT by key)
  ├─ persist Order + OrderItems atomically
  ├─ record ORDER_RECEIVED event
  ├─ return 202 Accepted ← caller gets response here
  │
  └─ [async coroutine]
       ├─ status → PROCESSING
       ├─ record PROCESSING_STARTED
       ├─ call ERP (with retry on transient errors)
       │    ├─ success → status → SYNCED
       │    └─ failure → status → FAILED (retriable via POST /retry)
       └─ persist IntegrationAttempt for every call
```

---

## Idempotency

The `Idempotency-Key` header must be provided on every `POST /api/v1/orders` request. It is the caller's responsibility to generate a stable, unique key per logical order.

**Three-layer strategy:**

| Layer | Mechanism | Purpose |
|-------|-----------|---------|
| Pre-check | `SELECT` by `idempotency_key` | Fast-path for replays; no write issued |
| DB constraint | `UNIQUE` on `idempotency_key` column | Final guard against concurrent inserts |
| Race recovery | Catch `DataIntegrityViolationException` → re-read the row | Returns the winner's record; caller never sees 5xx |

Under concurrent load with the same key, exactly one INSERT succeeds. All other threads find the existing record and return it transparently. This is verified by a dedicated 8-thread concurrency test.

---

## Retry strategy

| ERP response | Classification | Behaviour |
|--------------|---------------|-----------|
| 5xx, timeout, network error | Transient | Retry up to 3× with exponential backoff (1s base, ×2) |
| 4xx | Permanent | Abort immediately, no retry |
| 2xx | Success | Transition to `SYNCED` |

Every attempt — success or failure — is persisted as an `IntegrationAttempt` record with the request summary, response status, and error message. The full history is visible on `GET /api/v1/orders/{id}`.

Implemented using `resilience4j-kotlin`'s `executeSuspendFunction` so retry integrates correctly with Kotlin coroutines.

---

## Order state machine

```
                  POST /api/v1/orders
                          │
                          ▼
                      RECEIVED
                          │
                    [async coroutine]
                          │
                          ▼
                      PROCESSING
                       /       \
                      /         \
                   SYNCED      FAILED ──► POST /retry
```

| Status | Description |
|--------|-------------|
| `RECEIVED` | Order accepted and persisted; async processing queued |
| `PROCESSING` | ERP call in progress |
| `SYNCED` | ERP confirmed reception |
| `FAILED` | All retry attempts exhausted or permanent error received |

Only `FAILED` orders can be manually retried.

---

## Observability

**Correlation ID** — every request (inbound or initiated internally) gets a `correlationId`:
- read from `X-Correlation-Id` header if present
- generated as UUID if absent
- written to MDC under key `correlationId` and echoed in the response header
- propagated into async coroutines via `MDCContext()`, so every log line (HTTP + async) shares the same ID

**Structured logging** — all log lines include `correlationId`, logger name, and level. Pattern:
```
2024-01-15 10:30:00.123 [http-nio-8080-exec-1] [abc-123] INFO  o.r.ordersync.service.OrderService - Order created orderId=[...] externalId=[...]
```

**Actuator** — `/actuator/health`, `/actuator/info`, `/actuator/metrics` exposed. Health details and components are always shown.

---

## Test strategy

| Suite | Type | Infrastructure | What it verifies |
|-------|------|---------------|-----------------|
| `OrderMapperTest` | Unit | None | Total calculation, item wiring, event ordering in responses |
| `OrderServiceTest` | Unit | MockK | Idempotency hit, missing header, race condition recovery, retry gate |
| `OrderProcessingServiceTest` | Unit | MockK + real Resilience4j | Status transitions, attempt persistence, exception handling |
| `ErpIntegrationClientTest` | Unit | MockK (RestClient chain) | 2xx, 4xx→permanent, 5xx→transient, network failure |
| `OrderControllerIntegrationTest` | Integration | Testcontainers PostgreSQL | All endpoints, payload validation, business rules via HTTP |
| `OrderProcessingFlowIntegrationTest` | E2E | Testcontainers + WireMock | Async flow: success, retry-then-success, permanent failure, retry exhaustion |
| `OrderIdempotencyConcurrencyIntegrationTest` | Concurrency | Testcontainers | 8 concurrent identical requests → 1 DB record, 0 errors |

Integration tests require Docker. No manual setup — Testcontainers starts and tears down PostgreSQL automatically.

---

## Running locally

### Prerequisites

- Docker Desktop (or Engine + Compose v2)
- JDK 21

### 1. Start infrastructure only

```bash
docker-compose up
```

Starts **PostgreSQL** on `localhost:5432` and **WireMock** (simulated ERP) on `localhost:9090`.

### 2. Run the application

```bash
./gradlew bootRun
```

Service starts on `http://localhost:8080`. Flyway runs migrations on startup.

### 3. Full Docker stack

```bash
docker-compose --profile app up --build
```

### 4. Run tests

```bash
# All tests (requires Docker for integration tests)
./gradlew test

# Generate coverage report (HTML + XML)
./gradlew jacocoTestReport
# then open build/reports/jacoco/test/html/index.html in your browser
# macOS: open build/reports/jacoco/test/html/index.html
# Linux: xdg-open build/reports/jacoco/test/html/index.html

# Enforce the 80% coverage gate (same check CI runs)
./gradlew jacocoTestCoverageVerification

# Unit tests only (no Docker needed)
./gradlew test --tests "com.renan.ordersync.mapper.*" \
               --tests "com.renan.ordersync.service.*" \
               --tests "com.renan.ordersync.client.*"

# Integration tests only
./gradlew test --tests "com.renan.ordersync.integration.*"

# Test report — open build/reports/tests/test/index.html in your browser
```

### 5. Open Swagger UI

```
http://localhost:8080/swagger-ui.html
```

---

## API reference

| Method | Path | Description | Auth |
|--------|------|-------------|------|
| `POST` | `/api/v1/orders` | Create order (requires `Idempotency-Key` header) | — |
| `GET` | `/api/v1/orders/{orderId}` | Get order by internal ID (items + events) | — |
| `GET` | `/api/v1/orders?externalOrderId=` | Get orders by external reference | — |
| `POST` | `/api/v1/orders/{orderId}/retry` | Reprocess a `FAILED` order | — |
| `GET` | `/actuator/health` | Health check | — |
| `GET` | `/swagger-ui.html` | Interactive API docs | — |

### Create order

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{
    "externalOrderId": "ORD-2024-00123",
    "sourceSystem": "ecommerce-web",
    "customer": {
      "name": "Jane Doe",
      "email": "jane.doe@example.com"
    },
    "currency": "BRL",
    "items": [
      { "sku": "PROD-001", "name": "Notebook Dell", "quantity": 1, "unitPrice": 3499.99 },
      { "sku": "PROD-002", "name": "Mouse Logitech", "quantity": 2, "unitPrice": 349.90 }
    ]
  }'
```

**Response (202 Accepted):**

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "externalOrderId": "ORD-2024-00123",
  "status": "RECEIVED",
  "message": "Order received and queued for processing"
}
```

### Get order details

```bash
curl http://localhost:8080/api/v1/orders/550e8400-e29b-41d4-a716-446655440000
```

**Response (200 OK):**

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "externalOrderId": "ORD-2024-00123",
  "sourceSystem": "ecommerce-web",
  "customer": { "name": "Jane Doe", "email": "jane.doe@example.com" },
  "currency": "BRL",
  "totalAmount": 4199.79,
  "status": "SYNCED",
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:30:02",
  "items": [
    { "sku": "PROD-001", "name": "Notebook Dell", "quantity": 1, "unitPrice": 3499.99, "totalPrice": 3499.99 }
  ],
  "events": [
    { "eventType": "ORDER_RECEIVED",      "attemptNumber": 0, "createdAt": "2024-01-15T10:30:00" },
    { "eventType": "PROCESSING_STARTED",  "attemptNumber": 0, "createdAt": "2024-01-15T10:30:01" },
    { "eventType": "ERP_SYNC_ATTEMPTED",  "attemptNumber": 1, "createdAt": "2024-01-15T10:30:01" },
    { "eventType": "ERP_SYNC_SUCCEEDED",  "attemptNumber": 1, "createdAt": "2024-01-15T10:30:02" }
  ]
}
```

### Retry a failed order

```bash
curl -X POST http://localhost:8080/api/v1/orders/550e8400-.../retry
```

---

## Project structure

```
order-sync-service/
├── .github/workflows/ci.yml       # GitHub Actions (build + test, Testcontainers-native)
├── docs/
│   ├── SDD.md                     # System Design Document
│   ├── ADR-001-coroutines-over-async.md
│   ├── ADR-002-restclient-over-webclient.md
│   ├── ADR-003-idempotency-race-recovery.md
│   └── portfolio-positioning.md   # Interview prep and portfolio context
├── wiremock/mappings/             # WireMock ERP stubs (used in dev + integration tests)
├── src/
│   ├── main/
│   │   ├── kotlin/com/renan/ordersync/
│   │   │   ├── config/            # Spring beans: CoroutineScope, RestClient
│   │   │   ├── controller/        # OrderController
│   │   │   ├── dto/
│   │   │   │   ├── request/       # CreateOrderRequest (with Bean Validation)
│   │   │   │   └── response/      # CreateOrderResponse, OrderDetailResponse, ErrorResponse
│   │   │   ├── domain/
│   │   │   │   ├── entity/        # Order, OrderItem, ProcessingEvent, IntegrationAttempt
│   │   │   │   └── enums/         # OrderStatus, ProcessingEventType
│   │   │   ├── repository/        # Spring Data JPA (with JPQL fetch-join queries)
│   │   │   ├── service/           # OrderService, OrderProcessingService, ProcessingEventService
│   │   │   ├── client/            # ErpIntegrationClient (RestClient)
│   │   │   ├── mapper/            # OrderMapper (manual, no MapStruct)
│   │   │   ├── filter/            # CorrelationIdFilter (MDC)
│   │   │   └── exception/         # Exception hierarchy + GlobalExceptionHandler
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/      # V1–V4 Flyway SQL migrations
│   └── test/
│       ├── kotlin/com/renan/ordersync/
│       │   ├── fixtures/          # TestFixtures (shared test data builders)
│       │   ├── mapper/            # OrderMapperTest
│       │   ├── service/           # OrderServiceTest, OrderProcessingServiceTest
│       │   ├── client/            # ErpIntegrationClientTest
│       │   └── integration/       # IntegrationTestBase, Controller, Flow, Concurrency tests
│       └── resources/
│           └── application-test.yml  # Fast retry config for integration tests
├── build.gradle.kts
├── docker-compose.yml
├── Dockerfile
└── settings.gradle.kts
```

---

## Tech stack

| Layer | Technology |
|-------|------------|
| Language | Kotlin 2.1 + JDK 21 |
| Framework | Spring Boot 3.4 |
| HTTP client | Spring `RestClient` (Spring 6) |
| Async | Kotlin Coroutines + `kotlinx-coroutines-slf4j` |
| Retry | Resilience4j 2.2 (`resilience4j-kotlin`) |
| Database | PostgreSQL 16 + Spring Data JPA + Hibernate |
| Migrations | Flyway |
| API docs | Springdoc OpenAPI / Swagger UI |
| Observability | Spring Boot Actuator · MDC correlation IDs |
| Unit tests | JUnit 5 + MockK |
| Integration tests | Testcontainers + WireMock |
| Build | Gradle Kotlin DSL |
| CI | GitHub Actions |
| Container | Docker + Docker Compose |

---

## Key technical decisions

### Kotlin Coroutines, not `@Async`

A custom `CoroutineScope(SupervisorJob() + Dispatchers.IO)` bean launches processing after the HTTP response is committed. `SupervisorJob` ensures one failed coroutine never cancels others. All I/O-bound work runs inside `withContext(Dispatchers.IO)`. This gives structured concurrency, predictable cancellation, and idiomatic Kotlin — without Reactive Programming complexity.

### `RestClient`, not `WebClient`

Spring 6's `RestClient` is synchronous and sufficient when calls run inside `Dispatchers.IO`. Adding WebFlux + Reactor for outbound HTTP calls only would increase complexity for no benefit.

### Idempotency at two levels with race recovery

The SELECT-before-INSERT fast path handles replays. For concurrent requests slipping through simultaneously, the DB unique constraint is the final guard, and `DataIntegrityViolationException` recovery makes the experience transparent to callers.

### Resilience4j programmatic API

`@Retry` annotations do not integrate correctly with `suspend` functions. The programmatic `retry.executeSuspendFunction { }` is the correct approach and keeps retry configuration in a single place (`application.yml`).

### `ddl-auto: validate` + Flyway

All schema changes go through versioned SQL migrations. `validate` mode protects production from accidental drift.

---

## Configuration reference

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/ordersync` | JDBC connection URL |
| `DATABASE_USERNAME` | `ordersync` | DB user |
| `DATABASE_PASSWORD` | `ordersync` | DB password |
| `ERP_BASE_URL` | `http://localhost:9090` | ERP HTTP base URL |
| `ERP_TIMEOUT_SECONDS` | `30` | HTTP client connect/read timeout |

---

## Future improvements

| Improvement | Why it matters |
|-------------|---------------|
| **Outbox pattern** | Guarantees async processing survives app restart between DB commit and coroutine launch |
| **Kafka / SQS** | Replace in-process coroutine dispatch with a durable message broker |
| **Circuit Breaker** | Shed ERP load automatically during sustained outages |
| **JWT + Spring Security** | Authentication for all endpoints |
| **OpenTelemetry** | Distributed tracing across services |

---

## Documentation

- **[System Design Document](./docs/SDD.md)** — architecture, domain model, API contracts, idempotency, retry, acceptance criteria
- **[ADR-001: Coroutines over `@Async`](./docs/ADR-001-coroutines-over-async.md)** — why background processing uses structured concurrency instead of Spring's `@Async`
- **[ADR-002: `RestClient` over `WebClient`](./docs/ADR-002-restclient-over-webclient.md)** — why the ERP call stays synchronous instead of reactive
- **[ADR-003: Two-layer idempotency with race recovery](./docs/ADR-003-idempotency-race-recovery.md)** — why `createOrder` is intentionally non-transactional
- **[Swagger UI](http://localhost:8080/swagger-ui.html)** — interactive API explorer (requires running app)
- **[Portfolio context](./docs/portfolio-positioning.md)** — interview talking points, pitch, and freelancer positioning

---

## License

MIT © Renan G. Silveira
