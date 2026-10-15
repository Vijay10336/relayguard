# A three-minute client walkthrough

**Business problem:** “A payment provider retries a webhook after a timeout. Can your application accept that retry without writing the business result twice?”

1. Start RelayGuard and choose **Duplicate delivery**. Explain that two receipts map to one stored identity and one ledger entry.
2. Select **Transient outage**. Follow attempt 1 and 2 into RETRY, then attempt 3 into DELIVERED. Show that failed attempts have no ledger write.
3. Select **Poison event**. After three failures, show DEAD LETTER. Click the replay control, explaining that this demo clears an injected downstream fault rather than magically repairing production behavior.
4. Select **Tenant boundary**. The same event key in two tenants produces two independent event identities. Explain the difference between tenant-scoped idempotency and authenticated tenant authorization.
5. Open the integration tests. Point to the concurrent-claim test and transaction rollback test. Explain why the guarantee stops at the database boundary.

## Services this work illustrates
- Investigating duplicate booking/payment callbacks.
- Adding idempotency and safe retry behavior to existing Spring Boot services.
- Diagnosing partial failures and improving auditability.
- Writing focused concurrency and integration tests for a critical backend workflow.

## Honest project description
“An original portfolio simulator built with AI assistance and reviewed through automated tests. It illustrates reliability patterns I can discuss and adapt to a client's requirements. It is not employer code or a claim of production payment-provider certification.”
