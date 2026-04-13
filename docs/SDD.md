# System Design Document — order-sync-service

**Version:** 1.1  
**Author:** Renan G. Silveira  
**Last Updated:** 2026-04-13  
**Status:** Active

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Business Context](#2-business-context)
3. [Goals](#3-goals)
4. [Scope](#4-scope)
5. [Functional Requirements](#5-functional-requirements)
6. [Non-Functional Requirements](#6-non-functional-requirements)
7. [Architecture Overview](#7-architecture-overview)
8. [Domain Model](#8-domain-model)
9. [Data Model](#9-data-model)
10. [API Contracts](#10-api-contracts)
11. [Processing Flow](#11-processing-flow)
12. [Idempotency Strategy](#12-idempotency-strategy)
13. [Retry Strategy](#13-retry-strategy)
14. [Error Handling](#14-error-handling)
15. [Observability](#15-observability)
16. [Security Considerations](#16-security-considerations)
17. [Testing Strategy](#17-testing-strategy)
18. [Local Development Setup](#18-local-development-setup)
19. [Acceptance Criteria](#19-acceptance-criteria)
20. [Future Improvements](#20-future-improvements)

---

## 1. Project Overview

`order-sync-service` is a backend microservice responsible for receiving purchase orders from upstream systems (mobile apps, e-commerce frontends, B2B partners) and reliably synchronizing them to a downstream ERP (Enterprise Resource Planning) system.

The service acts as a **reliable bridge**: it decouples the order intake concern from the ERP integration concern, guaranteeing exactly-once semantics via idempotency keys, handling transient ERP failures through an exponential-backoff retry mechanism, and maintaining a full audit trail of every processing attempt.

**Technology stack:** Kotlin 2.1 · Spring Boot 3.4 · PostgreSQL 16 · Gradle Kotlin DSL · JDK 21 · Resilience4j · Kotlin Coroutines

---

## 2. Business Context

In many organizations, customer-facing order systems and internal ERP systems evolve on different timelines and are owned by different teams. Direct coupling between these layers creates fragility:

- If the ERP is briefly unavailable, orders are lost or must be manually re-entered.
- Duplicate requests from mobile clients (retries on network timeout) create duplicate ERP records.
- There is no shared audit trail when things go wrong.

`order-sync-service` solves this by introducing an **async, idempotent intermediary layer** that:

1. Accepts orders immediately and acknowledges receipt (`202 Accepted`).
2. Processes orders asynchronously, retrying transient ERP failures.
3. Persists every processing attempt for observability and manual intervention.
4. Exposes an HTTP endpoint to manually reprocess failed orders.

This pattern is common in real-world distributed systems: it improves resilience, decouples SLAs, and provides operational visibility.

---

## 3. Goals

| ID | Goal |
|----|------|
| G-01 | Accept orders from any upstream system via a well-defined HTTP API. |
| G-02 | Guarantee exactly-once order creation via `Idempotency-Key` header. |
| G-03 | Process orders asynchronously so that upstream callers are not blocked by ERP latency. |
| G-04 | Retry ERP integration on transient failures with exponential backoff. |
| G-05 | Maintain a complete, immutable audit trail of every processing attempt. |
| G-06 | Allow operations teams to manually reprocess failed orders. |
| G-07 | Expose structured health, metrics, and readiness endpoints. |
| G-08 | Provide interactive API documentation via Swagger UI. |

---

## 4. Scope

### In Scope

- Order intake via `POST /api/v1/orders`
- Idempotency enforcement
- Async order processing with ERP integration
- Retry with exponential backoff for transient ERP failures
- Full audit trail (processing events + integration attempts)
- Order query by internal ID and external order ID
- Manual reprocessing of failed orders
- Docker Compose local stack with simulated ERP (WireMock)
- Actuator health/metrics endpoints
- OpenAPI/Swagger documentation

### Out of Scope

- Authentication and authorization (JWT, OAuth2) — see [Future Improvements](#20-future-improvements)
- Order cancellation or modification after creation
- Payment processing
- Inventory management
- Rate limiting
- Message broker / event streaming (Kafka/SQS) — see [Future Improvements](#20-future-improvements)

---

## 5. Functional Requirements

| ID | Requirement |
|----|-------------|
| FR-01 | The system MUST accept `POST /api/v1/orders` with a valid JSON payload. |
| FR-02 | The system MUST require the `Idempotency-Key` header on order creation. |
| FR-03 | If an `Idempotency-Key` already exists, the system MUST return the existing order without creating a duplicate. |
| FR-04 | The system MUST validate the request payload and return `400 Bad Request` for invalid input. |
| FR-05 | The system MUST calculate `totalAmount` as the sum of `quantity × unitPrice` for all items. |
| FR-06 | The system MUST persist the order, its items, and an initial `ORDER_RECEIVED` event atomically. |
| FR-07 | The system MUST return `202 Accepted` with the order ID and status immediately after persistence. |
| FR-08 | The system MUST trigger async processing without blocking the HTTP response. |
| FR-09 | The system MUST update the order status to `PROCESSING` before calling the ERP. |
| FR-10 | The system MUST call the ERP HTTP endpoint and persist the attempt result. |
| FR-11 | The system MUST retry on transient ERP failures (HTTP 5xx, network errors) up to 3 times with exponential backoff. |
| FR-12 | The system MUST set status to `SYNCED` on ERP success. |
| FR-13 | The system MUST set status to `FAILED` after exhausting all retry attempts. |
| FR-14 | The system MUST expose `GET /api/v1/orders/{orderId}` returning full order details with events. |
| FR-15 | The system MUST expose `GET /api/v1/orders?externalOrderId=...` for external reference lookup. |
| FR-16 | The system MUST expose `POST /api/v1/orders/{orderId}/retry` to manually trigger reprocessing. |
| FR-17 | Manual retry MUST only be allowed for orders in `FAILED` status. |
| FR-18 | The system MUST expose `/actuator/health` for readiness checks. |

---

## 6. Non-Functional Requirements

| Category | Requirement |
|----------|-------------|
| **Performance** | Order creation endpoint should respond within 200ms under normal DB load (async processing excluded from latency budget). |
| **Reliability** | The service should not lose an order once `202 Accepted` is returned. All state transitions must be durable. |
| **Idempotency** | Identical `Idempotency-Key` values MUST never produce more than one order record, regardless of concurrency. |
| **Observability** | Every order state transition must produce a structured log entry with `orderId` and `correlationId`. |
| **Maintainability** | Code must follow a clear layered architecture. No business logic in controllers. No DB queries in service layer. |
| **Portability** | The service must run with a single `docker-compose up`. No external dependency outside of PostgreSQL and the ERP HTTP endpoint. |
| **Testability** | Unit and integration tests must cover all critical paths. Integration tests must use real PostgreSQL via Testcontainers. |

---

## 7. Architecture Overview

### Layer Diagram

```
┌──────────────────────────────────────────────────────────┐
│                    HTTP Clients                          │
│        (mobile apps, frontends, B2B partners)            │
└─────────────────────┬────────────────────────────────────┘
                      │ HTTP
┌─────────────────────▼────────────────────────────────────┐
│               Controller Layer                           │
│           (OrderController)                              │
│  - Request validation                                    │
│  - Idempotency-Key extraction                            │
│  - Response mapping                                      │
└─────────────────────┬────────────────────────────────────┘
                      │
┌─────────────────────▼────────────────────────────────────┐
│                Service Layer                             │
│    (OrderService, OrderProcessingService)                │
│  - Business rules                                        │
│  - Status transitions                                    │
│  - Async processing dispatch                             │
│  - Retry orchestration                                   │
└──────────┬──────────────────────────┬────────────────────┘
           │                          │
┌──────────▼──────────┐   ┌──────────▼────────────────────┐
│  Repository Layer   │   │       ERP Client               │
│  (Spring Data JPA)  │   │   (ErpIntegrationClient)       │
│                     │   │  - HTTP via RestClient         │
│  - OrderRepository  │   │  - Resilience4j @Retry        │
│  - OrderItemRepo    │   │  - Transient/perm error model  │
│  - EventRepository  │   └──────────────────┬────────────┘
│  - AttemptRepo      │                      │ HTTP
└──────────┬──────────┘   ┌──────────────────▼────────────┐
           │              │    External ERP System         │
┌──────────▼──────────┐   │   (WireMock in dev/test)       │
│    PostgreSQL        │   └───────────────────────────────┘
│  - orders            │
│  - order_items       │
│  - processing_events │
│  - integration_      │
│    attempts          │
└─────────────────────┘
```

### Package Structure

```
com.renan.ordersync/
├── config/               # Spring beans: CoroutineScope, RestClient, Resilience4j
├── controller/           # @RestController classes
├── dto/
│   ├── request/          # Inbound payload POJOs
│   └── response/         # Outbound response POJOs
├── domain/
│   ├── entity/           # @Entity JPA classes
│   └── enums/            # OrderStatus, ProcessingEventType
├── repository/           # @Repository Spring Data interfaces
├── service/              # Business logic
├── client/               # ERP HTTP client
├── mapper/               # Entity ↔ DTO conversion
└── exception/            # Exception hierarchy + GlobalExceptionHandler
```

---

## 8. Domain Model

```
Order (1) ──────────────── (N) OrderItem
  │
  │ (1) ─────────────────── (N) ProcessingEvent
  │
  └── (1) ──────────────── (N) IntegrationAttempt
```

### Order

Central aggregate root. Represents a purchase order to be synced to the ERP.

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | PK, generated |
| `externalOrderId` | String | Caller's reference, used for lookup |
| `sourceSystem` | String | Identifies the upstream system |
| `customerName` | String | Full name |
| `customerEmail` | String | Valid email |
| `currency` | String | ISO 4217 (e.g. BRL, USD) |
| `totalAmount` | BigDecimal | Calculated from items |
| `status` | OrderStatus | State machine (see below) |
| `idempotencyKey` | String | Unique constraint |
| `createdAt` | OffsetDateTime | |
| `updatedAt` | OffsetDateTime | |

### OrderItem

Line items of an Order.

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | PK |
| `orderId` | UUID | FK → orders.id |
| `sku` | String | Product SKU |
| `name` | String | Product name at time of order |
| `quantity` | Int | Must be ≥ 1 |
| `unitPrice` | BigDecimal | Price per unit |
| `totalPrice` | BigDecimal | `quantity × unitPrice` |

### ProcessingEvent

Append-only audit log of state transitions.

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | PK |
| `orderId` | UUID | FK → orders.id |
| `eventType` | ProcessingEventType | |
| `message` | String | Human-readable description |
| `attemptNumber` | Int | 0 for non-retry events |
| `createdAt` | OffsetDateTime | |

### IntegrationAttempt

Records each individual HTTP call to the ERP.

| Field | Type | Notes |
|-------|------|-------|
| `id` | UUID | PK |
| `orderId` | UUID | FK → orders.id |
| `attemptNumber` | Int | Starts at 1 |
| `requestPayload` | String | JSON sent to ERP |
| `responseStatusCode` | Int? | HTTP status received |
| `responseBody` | String? | Body received or null |
| `errorMessage` | String? | Exception message on network error |
| `success` | Boolean | |
| `createdAt` | OffsetDateTime | |

### Order State Machine

```
                  ┌─────────────┐
    POST /orders  │             │
  ──────────────► │  RECEIVED   │
                  │             │
                  └──────┬──────┘
                         │ async processing starts
                  ┌──────▼──────┐
                  │             │
                  │ PROCESSING  │
                  │             │
                  └──────┬──────┘
                    ┌────┴────┐
                    │         │
             success│         │failure (retries exhausted)
                    │         │
             ┌──────▼──┐  ┌───▼──────┐
             │         │  │          │
             │ SYNCED  │  │  FAILED  │◄── POST /retry re-enters PROCESSING
             │         │  │          │
             └─────────┘  └──────────┘
```

---

## 9. Data Model

### Table: `orders`

```sql
CREATE TABLE orders (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    external_order_id VARCHAR(255) NOT NULL,
    source_system     VARCHAR(100) NOT NULL,
    customer_name     VARCHAR(255) NOT NULL,
    customer_email    VARCHAR(255) NOT NULL,
    currency          CHAR(3)      NOT NULL,
    total_amount      NUMERIC(19,4) NOT NULL,
    status            VARCHAR(50)  NOT NULL,
    idempotency_key   VARCHAR(255) NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key)
);
```

### Table: `order_items`

```sql
CREATE TABLE order_items (
    id          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id    UUID          NOT NULL REFERENCES orders(id),
    sku         VARCHAR(100)  NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    quantity    INT           NOT NULL CHECK (quantity > 0),
    unit_price  NUMERIC(19,4) NOT NULL,
    total_price NUMERIC(19,4) NOT NULL
);
```

### Table: `processing_events`

```sql
CREATE TABLE processing_events (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id       UUID         NOT NULL REFERENCES orders(id),
    event_type     VARCHAR(100) NOT NULL,
    message        TEXT,
    attempt_number INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### Table: `integration_attempts`

```sql
CREATE TABLE integration_attempts (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id             UUID         NOT NULL REFERENCES orders(id),
    attempt_number       INT          NOT NULL,
    request_payload      TEXT         NOT NULL,
    response_status_code INT,
    response_body        TEXT,
    error_message        TEXT,
    success              BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### Indexes

```sql
CREATE INDEX idx_orders_external_order_id ON orders(external_order_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_order_items_order_id ON order_items(order_id);
CREATE INDEX idx_processing_events_order_id ON processing_events(order_id);
CREATE INDEX idx_integration_attempts_order_id ON integration_attempts(order_id);
```

---

## 10. API Contracts

### POST /api/v1/orders

Creates a new order and triggers async ERP synchronization.

**Headers:**

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes | `application/json` |
| `Idempotency-Key` | Yes | UUID or any unique string (max 255 chars). Same key = same order returned. |

**Request Body:**

```json
{
  "externalOrderId": "ORD-2024-00123",
  "sourceSystem": "ecommerce-web",
  "customer": {
    "name": "João Silva",
    "email": "joao.silva@example.com"
  },
  "currency": "BRL",
  "items": [
    {
      "sku": "PROD-001",
      "name": "Notebook Dell Inspiron",
      "quantity": 1,
      "unitPrice": 3499.99
    },
    {
      "sku": "PROD-002",
      "name": "Mouse Logitech MX Master",
      "quantity": 2,
      "unitPrice": 349.90
    }
  ]
}
```

**Response — 202 Accepted (new order OR idempotent replay):**

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "externalOrderId": "ORD-2024-00123",
  "status": "RECEIVED",
  "message": "Order received and queued for processing"
}
```

Both a fresh creation and an idempotent replay return `202 Accepted`. The caller can distinguish them by checking whether the `orderId` matches a previously received value.

**Response — 400 Bad Request:**

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed: items must not be empty",
  "path": "/api/v1/orders"
}
```

---

### GET /api/v1/orders/{orderId}

Returns full order details including items and processing history.

**Response — 200 OK:**

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "externalOrderId": "ORD-2024-00123",
  "sourceSystem": "ecommerce-web",
  "customer": { "name": "João Silva", "email": "joao.silva@example.com" },
  "currency": "BRL",
  "totalAmount": 4199.79,
  "status": "SYNCED",
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:30:05Z",
  "items": [
    {
      "itemId": "...",
      "sku": "PROD-001",
      "name": "Notebook Dell Inspiron",
      "quantity": 1,
      "unitPrice": 3499.99,
      "totalPrice": 3499.99
    }
  ],
  "events": [
    {
      "eventType": "ORDER_RECEIVED",
      "message": "Order received and persisted successfully",
      "attemptNumber": 0,
      "createdAt": "2024-01-15T10:30:00Z"
    },
    {
      "eventType": "PROCESSING_STARTED",
      "message": "Async processing initiated",
      "attemptNumber": 0,
      "createdAt": "2024-01-15T10:30:00Z"
    },
    {
      "eventType": "ERP_SYNC_SUCCEEDED",
      "message": "ERP responded 200 OK on attempt 1",
      "attemptNumber": 1,
      "createdAt": "2024-01-15T10:30:05Z"
    }
  ]
}
```

---

### GET /api/v1/orders?externalOrderId={externalOrderId}

Looks up an order by the caller's own reference ID.

**Response — 200 OK:** Same schema as above.  
**Response — 404 Not Found** if no order matches.

---

### POST /api/v1/orders/{orderId}/retry

Manually triggers reprocessing for a `FAILED` order.

**Response — 202 Accepted:**

```json
{
  "orderId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "FAILED",
  "message": "Retry triggered. Order queued for reprocessing."
}
```

> Note: `status` reflects the order's state at the moment the retry is accepted (`FAILED`). The transition to `PROCESSING` and beyond happens asynchronously after the response is returned.

**Response — 422 Unprocessable Entity** if order is not in `FAILED` status:

```json
{
  "timestamp": "2024-01-15T10:30:00",
  "status": 422,
  "error": "Business Rule Violation",
  "message": "Order [550e8400-...] cannot be retried because its status is [SYNCED]. Only FAILED orders can be manually retried.",
  "path": "/api/v1/orders/550e8400-.../retry"
}
```

---

### GET /actuator/health

Standard Spring Boot Actuator health endpoint.

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "diskSpace": { "status": "UP" },
    "ping": { "status": "UP" }
  }
}
```

---

## 11. Processing Flow

### Order Creation Flow

```
Client                   OrderController          OrderService           DB
  │                           │                       │                   │
  │  POST /api/v1/orders      │                       │                   │
  │  Idempotency-Key: abc123  │                       │                   │
  ├──────────────────────────►│                       │                   │
  │                           │  createOrder(req,key) │                   │
  │                           ├──────────────────────►│                   │
  │                           │                       │  findByIdempKey   │
  │                           │                       ├──────────────────►│
  │                           │                       │◄──────────────────┤
  │                           │                       │  (null = new)     │
  │                           │                       │                   │
  │                           │                       │  INSERT order     │
  │                           │                       ├──────────────────►│
  │                           │                       │  INSERT items     │
  │                           │                       ├──────────────────►│
  │                           │                       │  INSERT event     │
  │                           │                       │  ORDER_RECEIVED   │
  │                           │                       ├──────────────────►│
  │                           │                       │  COMMIT           │
  │                           │                       ├──────────────────►│
  │                           │                       │                   │
  │                           │                       │  launch coroutine │
  │                           │                       │  (non-blocking)   │
  │                           │                       │───────────────────┐
  │  202 Accepted             │                       │                   │
  │◄──────────────────────────┤◄──────────────────────│                   │
  │                           │                       │                   │
```

### Async Processing Flow

```
Coroutine                  OrderService            ErpClient             ERP
  │                           │                       │                   │
  │  processOrder(orderId)    │                       │                   │
  ├──────────────────────────►│                       │                   │
  │                           │  status → PROCESSING  │                   │
  │                           │  event: PROCESSING_   │                   │
  │                           │  STARTED              │                   │
  │                           │                       │                   │
  │                           │  syncWithErp(order)   │                   │
  │                           ├──────────────────────►│                   │
  │                           │                       │  POST /erp/orders │
  │                           │                       ├──────────────────►│
  │                           │                       │  200 OK           │
  │                           │                       │◄──────────────────│
  │                           │                       │                   │
  │                           │  (success result)     │                   │
  │                           │◄──────────────────────│                   │
  │                           │  status → SYNCED      │                   │
  │                           │  event: ERP_SYNC_OK   │                   │
  │◄──────────────────────────│                       │                   │
```

### Retry Flow (transient failure)

```
Coroutine          Resilience4j         ErpClient            ERP
  │                    │                    │                  │
  │  attempt 1         │                    │                  │
  ├───────────────────►│  syncWithErp()     │                  │
  │                    ├───────────────────►│  POST /erp/orders│
  │                    │                    ├─────────────────►│
  │                    │                    │  503 Service     │
  │                    │                    │  Unavailable     │
  │                    │                    │◄─────────────────│
  │                    │  ErpTransientEx    │                  │
  │                    │◄───────────────────│                  │
  │                    │  wait 1s           │                  │
  │                    │  attempt 2         │                  │
  │                    ├───────────────────►│  POST /erp/orders│
  │                    │                    ├─────────────────►│
  │                    │                    │  200 OK          │
  │                    │                    │◄─────────────────│
  │   success          │◄───────────────────│                  │
  │◄───────────────────│                    │                  │
```

---

## 12. Idempotency Strategy

### Mechanism

Each order creation request must include an `Idempotency-Key` HTTP header. This key is stored in the `orders.idempotency_key` column, which has a `UNIQUE` constraint at the database level.

### Implementation

1. Before inserting, the service queries `OrderRepository.findByIdempotencyKey(key)`.
2. If an existing order is found, it is returned immediately without any side effects.
3. If not found, the order is inserted within a transaction.
4. The database `UNIQUE` constraint acts as the final safety net against race conditions (concurrent requests with the same key will result in one succeeding and one receiving a `DataIntegrityViolationException`, which is caught and handled by returning the existing order).

### Concurrency Safety

```
Thread A (key=abc123)           Thread B (key=abc123)
        │                               │
        │ SELECT → not found            │ SELECT → not found
        │                               │
        │ INSERT order → success        │ INSERT order → UNIQUE VIOLATION
        │                               │
        │ 202 Accepted                  │ catch → SELECT → found
        │                               │ 200 OK (same order)
```

This pattern ensures at-most-once creation even under concurrent requests.

### Key Guidelines for Callers

- The key should be a UUID v4 generated client-side before the request.
- The same key must be re-used when retrying a request that timed out.
- Keys are scoped globally (not per customer or per session).

---

## 13. Retry Strategy

### Tool: Resilience4j Retry

Resilience4j is configured via Spring Boot auto-configuration. The retry instance for ERP calls is named `erp-client`.

### Configuration

```yaml
resilience4j:
  retry:
    instances:
      erp-client:
        max-attempts: 3
        wait-duration: 1s
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2.0
        retry-exceptions:
          - com.renan.ordersync.exception.ErpTransientException
        ignore-exceptions:
          - com.renan.ordersync.exception.ErpPermanentException
```

### Retry Schedule

| Attempt | Delay before attempt |
|---------|----------------------|
| 1       | 0ms (immediate)      |
| 2       | 1 000ms              |
| 3       | 2 000ms              |
| 4+      | Does not occur — exception propagated |

Total maximum additional wait: ~3 seconds.

### Failure Classification

| HTTP Status | Exception | Retry? |
|-------------|-----------|--------|
| 5xx (500, 502, 503, 504) | `ErpTransientException` | Yes |
| Network timeout, connection refused | `ErpTransientException` | Yes |
| 4xx (400, 422) | `ErpPermanentException` | No |
| 2xx | — | No (success) |

### Post-Retry Behavior

- Each attempt is recorded in `integration_attempts` with `success=false`.
- After all attempts fail, the order is marked `FAILED` and a `ERP_SYNC_FAILED` event is recorded.
- The order remains queryable and can be manually retried via `POST /api/v1/orders/{orderId}/retry`.

---

## 14. Error Handling

### Global Exception Handler

A `@RestControllerAdvice` class handles all exceptions and maps them to a standardized error response:

```json
{
  "timestamp": "2024-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed: customerEmail must be a valid email address",
  "path": "/api/v1/orders"
}
```

### Exception Hierarchy

```
RuntimeException
├── OrderNotFoundException           → 404 Not Found
├── BusinessRuleViolationException   → 422 Unprocessable Entity
│   (used for: retry on non-FAILED orders, and other rule violations)
├── MissingIdempotencyKeyException   → 400 Bad Request
├── ErpTransientException            → triggers Resilience4j retry
└── ErpPermanentException            → immediate failure, no retry
```

### Validation Errors

Spring's `@Valid` annotation triggers `MethodArgumentNotValidException`. The handler extracts field errors and returns a descriptive message.

### Missing Header

`MissingRequestHeaderException` for absent `Idempotency-Key` returns `400 Bad Request` with a clear message.

---

## 15. Observability

### Structured Logging

Every log entry for order operations includes:

- `correlationId` — extracted from `X-Correlation-Id` header (or generated as UUID if absent) via MDC key `correlationId`
- `orderId` — present in all service-layer logs
- Log level: INFO for state transitions, WARN for retries, ERROR for final failures

**Log pattern** (from `application.yml`):

```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{correlationId}] %-5level %logger{36} - %msg%n
```

**Example log output:**

```
2024-01-15 10:30:00.123 [http-nio-8080-exec-1] [abc-123] INFO  c.r.ordersync.service.OrderService - Order created orderId=[550e8400] externalId=[ORD-2024-00123]
2024-01-15 10:30:00.145 [DefaultDispatcher-worker-1] [abc-123] INFO  c.r.ordersync.service.OrderProcessingService - Order [550e8400] → PROCESSING
2024-01-15 10:30:01.230 [DefaultDispatcher-worker-1] [abc-123] WARN  c.r.ordersync.service.OrderProcessingService - ERP transient failure orderId=[550e8400] attempt=[1]: ...
2024-01-15 10:30:03.250 [DefaultDispatcher-worker-1] [abc-123] INFO  c.r.ordersync.service.OrderProcessingService - ERP sync succeeded orderId=[550e8400] attempt=[2]
```

**Async MDC propagation:** The `correlationId` is propagated from the HTTP thread into background coroutines via `MDCContext()` (from `kotlinx-coroutines-slf4j`). This ensures that async log lines (running on `Dispatchers.IO` threads) carry the same `correlationId` as the originating HTTP request.

### Actuator Endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Liveness + readiness (DB check included) |
| `GET /actuator/info` | App name, version |
| `GET /actuator/metrics` | JVM, HTTP, connection pool metrics |
| `GET /actuator/loggers` | Runtime log level management |

### Key Metrics (via Actuator + Micrometer)

- `http.server.requests` — latency and count per endpoint
- `hikaricp.connections.active` — DB connection pool usage
- `resilience4j.retry.calls` — retry success/failure rates

---

## 16. Security Considerations

### Current Implementation

- Input validation via Jakarta Bean Validation (`@Valid`, `@NotBlank`, `@Email`, `@Min`).
- Parameterized queries via Spring Data JPA (no SQL injection risk).
- Non-root Docker user in production image.
- No sensitive data (passwords, tokens) logged.
- Database credentials supplied via environment variables (not hardcoded).

### Not Implemented (Future)

| Concern | Recommended Approach |
|---------|---------------------|
| Authentication | JWT / OAuth2 via Spring Security |
| Authorization | Role-based access for retry endpoint |
| Rate limiting | Spring Cloud Gateway or Bucket4j |
| TLS | Terminate at load balancer / ingress |
| Secret management | HashiCorp Vault or AWS Secrets Manager |
| Audit log for write operations | Already covered by `ProcessingEvent` |

---

## 17. Testing Strategy

### Unit Tests

Target: service and client layer logic, isolated from infrastructure.

| Class | Focus |
|-------|-------|
| `OrderMapperTest` | Total calculation, item wiring, event ordering in responses |
| `OrderServiceTest` | Idempotency hit, missing header, race condition recovery via `DataIntegrityViolationException`, retry gate by status |
| `OrderProcessingServiceTest` | Status transitions, attempt persistence, transient/permanent exception handling |
| `ErpIntegrationClientTest` | 2xx success, 4xx → `ErpPermanentException`, 5xx → `ErpTransientException`, network failure |

**Tools:** JUnit 5, MockK

### Integration Tests

Target: full HTTP stack with real PostgreSQL and Flyway schema.

| Class | Focus |
|-------|-------|
| `OrderControllerIntegrationTest` | All endpoints, payload validation, business rules via real HTTP |
| `OrderProcessingFlowIntegrationTest` | Full async flow with WireMock ERP (success, retry, permanent failure, exhaustion) |
| `OrderIdempotencyConcurrencyIntegrationTest` | 8 concurrent identical requests → exactly 1 DB record, 0 errors |

**Tools:** `@SpringBootTest`, Testcontainers (PostgreSQL 16), WireMock standalone server, Awaitility

All integration tests use a shared `IntegrationTestBase` with a singleton Testcontainers PostgreSQL container (reused across test classes via `withReuse(true)`) and the `test` Spring profile (fast retry — 50ms wait instead of 1s).

### Test Coverage Goals

| Layer | Target Coverage |
|-------|----------------|
| Service layer | ≥ 90% |
| Controller layer | ≥ 80% (via integration tests) |
| Client layer | ≥ 85% |
| Repository layer | Covered by integration tests |

---

## 18. Local Development Setup

### Prerequisites

- Docker Desktop (or Docker Engine + Compose)
- JDK 21 (for running outside Docker)

### Running Infrastructure Only

```bash
docker-compose up
```

This starts:
- PostgreSQL on `localhost:5432`
- WireMock ERP mock on `localhost:9090`

### Running the Application

```bash
# Against local infrastructure
./gradlew bootRun

# Or full stack via Docker
docker-compose --profile app up --build
```

### Running Tests

```bash
# All tests (Testcontainers will spin up its own PostgreSQL)
./gradlew test

# Unit tests only (no Docker required)
./gradlew test --tests "com.renan.ordersync.mapper.*" \
               --tests "com.renan.ordersync.service.*" \
               --tests "com.renan.ordersync.client.*"

# Integration tests only (requires Docker)
./gradlew test --tests "com.renan.ordersync.integration.*"
```

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/ordersync` | JDBC URL |
| `DATABASE_USERNAME` | `ordersync` | DB user |
| `DATABASE_PASSWORD` | `ordersync` | DB password |
| `ERP_BASE_URL` | `http://localhost:9090` | ERP endpoint base URL |
| `ERP_TIMEOUT_SECONDS` | `30` | HTTP timeout |

### API Documentation

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`
- Actuator: `http://localhost:8080/actuator/health`

---

## 19. Acceptance Criteria

### AC-01: Order Creation

```
Given a valid payload and a new Idempotency-Key
When POST /api/v1/orders is called
Then the response is 202 Accepted
And the order is persisted with status RECEIVED
And an ORDER_RECEIVED event is persisted
And async processing is triggered
```

### AC-02: Idempotency Replay

```
Given an Idempotency-Key that was already used
When POST /api/v1/orders is called with the same key
Then the response is 200 OK
And the original order is returned
And no new order record is created
```

### AC-03: Concurrent Idempotency

```
Given an Idempotency-Key not yet used
When two concurrent POST /api/v1/orders requests are made with the same key
Then exactly one order is created
And both responses return the same order
```

### AC-04: ERP Sync Success

```
Given a RECEIVED order
When async processing runs and ERP returns 200
Then the order status changes to SYNCED
And an ERP_SYNC_SUCCEEDED event is persisted
And one IntegrationAttempt with success=true is persisted
```

### AC-05: ERP Transient Failure with Recovery

```
Given a RECEIVED order
When ERP returns 503 on the first two attempts and 200 on the third
Then the order status changes to SYNCED
And three IntegrationAttempts are persisted (2 failure, 1 success)
```

### AC-06: ERP Permanent Failure

```
Given a RECEIVED order
When all retry attempts are exhausted
Then the order status changes to FAILED
And an ERP_SYNC_FAILED event is persisted
```

### AC-07: Manual Retry

```
Given a FAILED order
When POST /api/v1/orders/{orderId}/retry is called
Then the order enters PROCESSING status
And async processing is re-triggered
And a MANUAL_RETRY_REQUESTED event is persisted
```

### AC-08: Invalid Retry

```
Given an order in SYNCED status
When POST /api/v1/orders/{orderId}/retry is called
Then the response is 409 Conflict
And no state change occurs
```

### AC-09: Missing Idempotency-Key

```
Given a valid payload
When POST /api/v1/orders is called without Idempotency-Key header
Then the response is 400 Bad Request
And the message indicates the missing header
```

---

## 20. Future Improvements

| Priority | Improvement | Rationale |
|----------|-------------|-----------|
| High | **JWT authentication + Spring Security** | Expose service to external systems safely |
| High | **Async via message broker (Kafka/SQS)** | Decouple processing from application lifecycle; survive restarts mid-processing |
| High | **Dead Letter Queue for FAILED orders** | Operational visibility without manual API calls |
| Medium | **Circuit Breaker (Resilience4j)** | Prevent cascading failures when ERP is degraded |
| Medium | **Outbox Pattern** | Guarantee that async processing is triggered even if the app crashes immediately after DB commit |
| Medium | **Rate limiting per source system** | Prevent abuse from a single upstream caller |
| Low | **Distributed tracing (OpenTelemetry)** | Trace requests across services with Jaeger or Tempo |
| Low | **Prometheus + Grafana dashboard** | Pre-built dashboards for SLA monitoring |
| Low | **Order cancellation support** | Business requirement likely to appear in v2 |
| Low | **Pagination on list endpoints** | Required once order volume grows |
