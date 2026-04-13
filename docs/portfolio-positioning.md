# Portfolio Positioning — order-sync-service

---

## 1. One-line pitch

> Async order ingestion and ERP synchronisation microservice in Kotlin + Spring Boot, with idempotency, Resilience4j retry, and end-to-end Testcontainers/WireMock test coverage.

---

## 2. Short portfolio description

*(Use this on your GitHub profile README, LinkedIn, or CV)*

**order-sync-service** is a production-grade Kotlin/Spring Boot microservice that bridges customer-facing order systems to a downstream ERP. It accepts purchase orders via an idempotent HTTP API, persists them durably, and synchronises them to the ERP asynchronously using Kotlin Coroutines — retrying transient failures up to 3× with exponential backoff via Resilience4j. The service maintains a full audit trail of every state transition and integration attempt. Tested end-to-end with Testcontainers (real PostgreSQL + Flyway) and WireMock (simulated ERP), including a dedicated concurrency test that proves idempotency holds under 8 simultaneous requests with the same key.

---

## 3. Interview talking points

Use these as conversation anchors when asked to walk through the project.

### Why idempotency matters
> "Mobile clients and upstream systems retry timed-out requests. Without idempotency, a network blip causes duplicate orders in the ERP — which requires manual cleanup and creates billing errors. We enforce it at two levels: a SELECT-before-INSERT as a fast path, plus a DB UNIQUE constraint as the final guard. If two threads slip through the check simultaneously, only one INSERT wins; the other catches `DataIntegrityViolationException` and returns the winning thread's result. The caller never sees a 5xx."

### How the race condition was handled
> "The key insight is transaction scope. If `createOrder` were `@Transactional`, the `DataIntegrityViolationException` would mark the transaction as rollback-only, making it impossible to do a recovery read in the same context. By removing the outer `@Transactional` from `createOrder`, the inner `saveAndFlush` runs in its own self-contained transaction. If it throws, we're back in a clean non-transactional scope and can run a fresh SELECT to return the existing order."

### How the retry strategy works
> "We use Resilience4j with `executeSuspendFunction` — the coroutine-aware wrapper from `resilience4j-kotlin`. The ERP client throws `ErpTransientException` for 5xx/network errors (Resilience4j retries) and `ErpPermanentException` for 4xx (Resilience4j ignores, no retry). Every attempt — success or failure — is saved to `integration_attempts` with its status code and error message, giving operations a complete picture without having to dig through logs."

### Why MDC propagation matters in async flows
> "The `correlationId` is set on the HTTP thread by a servlet filter. But order processing runs on a different Coroutines IO thread. Without explicit propagation, async logs would have no `correlationId` — making it impossible to trace a problem back to the originating request. We pass `MDCContext()` as the coroutine context element when launching the background job. This captures the MDC snapshot and restores it on whatever thread the coroutine runs on."

### Why Testcontainers + WireMock instead of mocking everything
> "Mocking the database means you're testing the service in isolation from the very thing most likely to break: real SQL, real FK constraints, real Flyway migrations. Testcontainers gives you a real PostgreSQL in a Docker container managed by the test lifecycle — zero manual setup. WireMock lets us test the retry behavior against a real HTTP server that returns 503 on the first two requests and 200 on the third, exactly as production would behave. Together they let us catch bugs that unit tests would miss, like a missing migration column or a response deserialization mismatch."

### Why `RestClient` instead of `WebClient`
> "We run ERP calls inside `withContext(Dispatchers.IO)`, so the thread is already dedicated to blocking I/O. There's no benefit to reactive HTTP in this context — it would add WebFlux, Reactor, and the cognitive overhead of a reactive pipeline for a simple synchronous call. `RestClient` (Spring 6) is clean, synchronous, and fits perfectly."

### Architecture decision: no `@Async`
> "`@Async` relies on a fixed thread pool and doesn't give you structured concurrency or lifecycle management. We use a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` bean instead — `SupervisorJob` means one failed coroutine doesn't cancel others, and we can tie the scope lifecycle to the Spring application context. It's also much easier to test: inject a `TestScope` and control execution timing precisely."

---

## 4. Freelancer positioning

*(Use this when pitching backend/integration work to clients)*

---

### For API development projects

This project demonstrates I can build production-ready REST APIs with:
- Clean request/response contracts with full validation
- Idempotent endpoints — critical for payment and order APIs where clients retry
- Structured error responses with field-level validation details
- Interactive Swagger documentation out of the box

### For integration projects

The ERP integration pattern here is reusable for any HTTP-based system (ERPs, payment gateways, logistics providers, CRMs):
- Async decoupling so the caller is never blocked by the downstream system
- Retry with exponential backoff for transient failures
- Permanent error detection to avoid useless retries
- Full audit trail for every call — essential when disputes arise with third parties

### For reliability/backend robustness projects

- Race condition handling under concurrent load (concurrency test included)
- Graceful degradation: orders are never lost even if the ERP is down
- Manual reprocessing endpoint for operations teams
- Observability built in: correlation IDs, structured logs, Actuator metrics

### For clients who care about quality

- Real integration tests with actual PostgreSQL and a real HTTP server (not mocks)
- CI pipeline that runs on every push without requiring external infrastructure
- Flyway migrations — no ad-hoc schema changes, full history in version control
- Clean layered architecture: no business logic in controllers, no DB queries in services

---

## 5. GitHub repository metadata suggestions

### Repository name

`order-sync-service` — keep it. It's descriptive, specific, and professional.

### One-line description (GitHub "About" field)

> Kotlin + Spring Boot microservice: idempotent order ingestion with async ERP sync, Resilience4j retry, and end-to-end Testcontainers/WireMock tests.

### Topics / tags (add these in GitHub repository settings)

```
kotlin
spring-boot
spring-boot-3
postgresql
coroutines
resilience4j
testcontainers
wiremock
flyway
rest-api
microservices
jpa
docker
clean-architecture
idempotency
```

### Social preview suggestion

If you add a social preview image, include the following text elements:
- Project name: `order-sync-service`
- Stack icons: Kotlin, Spring Boot, PostgreSQL
- Tagline: *"Async · Idempotent · Resilient"*
