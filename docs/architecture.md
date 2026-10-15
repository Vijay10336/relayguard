# Architecture decisions

## Durable inbox before processing
Acceptance persists one immutable identity before returning 202. The worker never relies on a browser session or a process-local queue. Provider retries can safely return the stored identity after a restart.

## Claim and side effect share a transaction
A conditional update changes a due RECEIVED/RETRY row to PROCESSING and increments attempts. Competing workers cannot both commit that update for the same current state. The resulting ledger insert, attempt record, and final state share the claim transaction. A crash or database failure rolls everything back; PROCESSING is not committed as a stranded intermediate state.

This approach is appropriate only because the demonstration's side effect is another write in the same database. A network payment request cannot participate in that atomicity. A real integration requires provider-side idempotency and reconciliation, often coordinated through an outbox.

## Duplicate handling
The database owns uniqueness, not a check-then-insert in application memory. A duplicate `(tenant,event_key)` returns its existing ID only when the body hash matches. An event key reused with a changed payload is rejected. H2's auto-commit insert behavior is relied on here; PostgreSQL would need a different conflict-handling strategy.

## Retry semantics
Injected downstream failures schedule retries after 2 and 4 seconds. The third failure enters DEAD_LETTER. The scheduler's two-second cadence is a polling interval, not a latency guarantee. Operator replay clears only the simulated outage, preserves prior attempts, and requeues the event. Delivered events cannot be replayed.

## Tradeoffs
- File-backed H2 minimizes setup for reviewers; it does not demonstrate a multi-node database deployment.
- Plain JDBC makes the conditional update and transaction boundary explicit.
- No broker means there is one less component to run. It also means there is no broker throughput or delivery guarantee being claimed.
- A simple static dashboard uses the same-origin API and no frontend build chain or CDN.
- There are no real payment-provider credentials and no real financial transactions.

## Meaningful next increments
1. Authenticated tenant context and operator permissions.
2. PostgreSQL schema and concurrency validation against Testcontainers.
3. Outbox dispatcher with a mock HTTP downstream and idempotency contract.
4. Timestamped signatures, key rotation and request limits at ingress.
5. Traces, health checks, lag alerts and configurable retention.

These are future work, not implemented features.
