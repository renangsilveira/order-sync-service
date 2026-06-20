# ADR-001: Kotlin Coroutines instead of `@Async` for background processing

**Status:** Accepted
**Date:** 2026-04-13

---

## Context

After persisting an order and returning `202 Accepted`, the service needs to dispatch ERP
synchronization in the background without blocking the HTTP response thread. Spring offers
`@Async` (backed by a `TaskExecutor` and a Java `Future`/`CompletableFuture`) as the conventional
way to do this. The codebase is written in Kotlin, which has first-class structured concurrency
support via coroutines.

Two options were considered:

1. **`@Async`** — annotate a method, let Spring proxy it onto a thread pool.
2. **Kotlin Coroutines** — launch a coroutine on an explicit `CoroutineScope`.

## Decision

Use Kotlin Coroutines, launched from a dedicated application-scoped `CoroutineScope`:

```kotlin
CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

`OrderService.createOrder` calls `orderProcessingService.launchProcessing(orderId)`, which
launches a coroutine on this scope. All blocking I/O inside that coroutine (DB calls, the ERP
HTTP call) runs inside `withContext(Dispatchers.IO)`.

## Rationale

- **`SupervisorJob` semantics.** A `SupervisorJob` ensures that an unhandled exception in one
  child coroutine does not cancel sibling coroutines or the parent scope. With `@Async`, a failure
  in one task has no such structural guarantee — it depends entirely on whether the caller
  inspects the returned `Future`, which `createOrder` does not do (it fires-and-forgets by design).
- **Idiomatic Kotlin.** Coroutines are the language-native concurrency primitive. Using `@Async`
  in a Kotlin codebase mixes two concurrency models (JVM thread-pool futures vs. structured
  concurrency) for no added benefit.
- **No reactive stack required.** The team considered and rejected a reactive (WebFlux) approach
  for the same reason described in ADR-002: a single background task per order does not justify
  the complexity of a fully reactive pipeline.
- **Explicit, testable scope.** The `CoroutineScope` is a Spring bean (see `AppConfig`), which
  makes it trivial to reason about its lifecycle and to substitute a `TestScope` in unit tests via
  `kotlinx-coroutines-test`.

## Consequences

- **Positive:** predictable cancellation semantics, idiomatic Kotlin, no risk of one failed order
  silently swallowing or cancelling unrelated background work.
- **Positive:** `MDCContext()` (see correlation ID propagation in the SDD) integrates naturally as
  a coroutine context element, which has no equivalent in the `@Async` model without manual
  `TaskDecorator` wiring.
- **Negative:** background work launched this way does **not** survive a process restart between
  the DB commit and the coroutine launch — if the JVM crashes in that narrow window, the order is
  persisted as `RECEIVED` but never processed. This is a known and accepted limitation, tracked in
  the README's "Future improvements" table under the **Outbox pattern** entry. A durable
  alternative (transactional outbox + poller, or a message broker) would close this gap at the
  cost of additional infrastructure.
- **Negative:** unlike `@Async`, there is no built-in Spring Boot Actuator metric for this custom
  scope's thread pool; `Dispatchers.IO` metrics are not currently exposed via Micrometer.
