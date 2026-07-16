# ADR-002: Synchronous `RestClient` instead of reactive `WebClient` for ERP calls

**Status:** Accepted
**Date:** 2026-04-13

---

## Context

`ErpIntegrationClient` makes a single outbound HTTP call per processing attempt to the downstream
ERP system. Spring offers two first-class HTTP client APIs:

1. **`WebClient`** (Spring WebFlux) — non-blocking, reactive, returns `Mono`/`Flux`.
2. **`RestClient`** (Spring 6, `spring-boot-starter-web`) — synchronous, fluent, blocking.

Because order processing already runs inside a Kotlin coroutine dispatched on
`Dispatchers.IO` (see ADR-001), the ERP call is never made on a thread that also needs to stay free
for other request handling — it is already isolated to an I/O-dedicated dispatcher.

## Decision

Use `RestClient`, called synchronously from inside `withContext(Dispatchers.IO) { ... }`.

## Rationale

- **No benefit from reactive I/O here.** `WebClient`'s value proposition is non-blocking I/O on a
  small, shared event-loop thread pool, which matters when a service handles many concurrent
  in-flight HTTP calls efficiently. This service's ERP call volume is bounded by order volume and
  already runs on `Dispatchers.IO`, which is designed and sized for blocking work. Reactive I/O
  would not reduce thread usage in any way that matters at this scale.
- **No WebFlux dependency.** Pulling in `spring-boot-starter-webflux` solely for outbound HTTP
  calls — while the inbound side stays on Spring MVC (`spring-boot-starter-web`) — would mix two
  web stacks in the same application for no functional gain, and would add Reactor's operator
  chain and backpressure semantics to a single call site that doesn't need them.
- **Simpler integration with Resilience4j.** `resilience4j-kotlin`'s `executeSuspendFunction` wraps
  a suspend lambda directly. A synchronous `RestClient` call inside that lambda is a direct
  function call; bridging a `Mono`-returning `WebClient` call into the same suspend function would
  require an extra `awaitSingle()` conversion step for no added benefit.
- **Readability.** The retry, exception classification (`ErpTransientException` /
  `ErpPermanentException`), and attempt-persistence logic in `OrderProcessingService` reads as
  ordinary sequential code, which is easier to reason about than an equivalent reactive chain.

## Consequences

- **Positive:** one HTTP client model in the codebase (Spring MVC + `RestClient`), no mixed
  reactive/imperative paradigms.
- **Positive:** straightforward unit testing — `ErpIntegrationClientTest` mocks the `RestClient`
  fluent chain directly with MockK, without needing `StepVerifier` or reactive test utilities.
- **Negative:** if ERP call volume grows to a point where thousands of concurrent outbound calls
  are needed, `Dispatchers.IO`'s thread pool (bounded, though elastic) could become a bottleneck
  before a reactive event-loop model would. This is not a concern at current or projected scale,
  but would be the trigger to revisit this decision.
