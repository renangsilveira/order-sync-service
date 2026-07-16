# ADR-003: Two-layer idempotency with race-condition recovery

**Status:** Accepted
**Date:** 2026-04-13

---

## Context

`POST /api/v1/orders` must be safe to retry. Upstream callers (mobile apps, e-commerce frontends,
B2B integrations) retry on network timeouts, and a retried request must never create a duplicate
order in the ERP. The mechanism must also be correct under genuine concurrency — two requests with
the same `Idempotency-Key` arriving close enough together to both pass a naive existence check.

A single `SELECT`-before-`INSERT` check is not sufficient on its own: between the `SELECT` and the
`INSERT`, a second thread can run the same `SELECT`, find nothing, and also attempt an `INSERT`.

## Decision

Implement idempotency in three cooperating layers, all centered on a single `UNIQUE` constraint:

| Layer | Mechanism | Purpose |
|-------|-----------|---------|
| Pre-check | `SELECT` by `idempotency_key` | Fast path for replays; avoids an unnecessary write |
| DB constraint | `UNIQUE` on `idempotency_key` | Final guard against concurrent inserts |
| Race recovery | Catch `DataIntegrityViolationException`, re-read the row | Returns the winning row; caller never sees a 5xx |

Critically, `OrderService.createOrder` is **not** annotated `@Transactional`. The actual insert
goes through `orderRepository.saveAndFlush(order)`, which Spring Data's `SimpleJpaRepository`
already wraps in its own self-contained transaction.

## Rationale

- **Why not just the DB constraint?** It is necessary but not sufficient on its own: without the
  pre-check, every retry would attempt a full `INSERT` and rely on hitting the constraint
  violation as the *normal* path for replays, which is both slower and treats an expected case
  (replay) as an exceptional one.
- **Why not just the pre-check?** Two requests with the same key can both pass the `SELECT` before
  either commits an `INSERT`. Without the constraint, this produces two rows for the same logical
  order — exactly the bug idempotency exists to prevent.
- **Why remove `@Transactional` from `createOrder`?** This is the detail that makes recovery
  possible. If `createOrder` itself were transactional and `saveAndFlush` threw
  `DataIntegrityViolationException` inside that transaction, Spring would mark the transaction
  `rollback-only`. Any further read inside that same transaction would throw
  `UnexpectedRollbackException` instead of returning data — there would be no way to read the
  winning row to return it to the caller. By letting `saveAndFlush`'s own transaction fail and
  close on its own, the method resumes in a clean, non-transactional context where a fresh
  `SELECT` works normally.
- **Why is this safe under load?** Verified by `OrderIdempotencyConcurrencyIntegrationTest`: 8
  threads issue the same request with the same `Idempotency-Key` simultaneously against a real
  Testcontainers PostgreSQL instance. Exactly one `INSERT` succeeds; the other seven catch the
  constraint violation, recover via re-read, and return the winning record. No thread observes a
  5xx response.

## Consequences

- **Positive:** idempotency holds under genuine concurrency, not just sequential replays, and is
  verified by an integration test rather than assumed.
- **Positive:** the common case (replay after the original write committed) takes the cheap
  `SELECT`-only path; the more expensive recovery path only triggers in the narrow race window.
- **Negative:** the non-`@Transactional` method signature is a deliberate exception to the
  pattern used elsewhere in the codebase (e.g. `retryOrder` is `@Transactional`). This needs a
  comment at the call site — present in `OrderService.kt` — so a future contributor doesn't "fix"
  it by adding the annotation back and silently breaking race recovery.
- **Negative:** the caller is responsible for generating a stable, unique `Idempotency-Key` per
  logical order. The service has no way to detect a caller reusing a key for what should be a
  genuinely different order — this is documented as a caller contract, not enforced server-side.
