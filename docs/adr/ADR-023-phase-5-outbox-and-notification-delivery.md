# ADR-023 — Proposed transactional outbox and local idempotent consumption for Phase 5

- **Status:** PROPOSED — NOT ACCEPTED and NOT IMPLEMENTED.
- **Date:** 2026-11-14
- **Phase:** 5 **DESIGN ONLY**, execution BLOCKED.
- **Historical baseline:** `dev@73eb12d307af696533ba182574d7f9f54ab2ec42`.
- **Preconditions:** Formal Phase 3 and 4 closures, independent Phase 5 design acceptance and separate owner authorization.

## Context

The original specification asks for Transactional Outbox, Kafka KRaft, MongoDB notifications, manual acknowledgements, three delivery attempts, DLT, idempotency and local-first learning. Flyway V10 already created `outbox_events` as a **scaffold**. The aim is to demonstrate real delivery guarantees without enterprise or banking architecture.

## Options

1. **Direct Kafka publish inside the commercial transaction:** rejected; PostgreSQL commit and broker send can diverge.
2. **Simple outbox relay using `FOR UPDATE SKIP LOCKED`:** provisionally recommended for a local portfolio broker. One row per bounded transaction; send with ACK and timeout while holding a row lock; mark `published_at` after ACK and commit. Kafka may receive duplicate data if PostgreSQL rolls back after ACK.
3. **Outbox claim/lease with additive Flyway V17, publishing outside DB transaction:** more scalable; adds persistent states, recovery and fencing, requires approved migration and lock audit. Not justified without reproducing limitations of option 2.
4. **CDC/Debezium or distributed two-phase commit:** out of scope/overengineered.

## Proposed direction, not implementation permission

Reuse V10 unchanged. In a **future authorized Phase 5**:
- Write outbox evidence inside the same PostgreSQL transaction as the commercial transition.
- Relay at approximately 1-second polling cadence using SKIP LOCKED, avoid publishing an event while an older event for the same aggregate remains pending, sort by `created_at,id`, use `aggregateId` as Kafka key, send complete JSON and mark published only after broker ACK.
- Promise at-least-once delivery; Mongo consumer enforces UNIQUE `eventId`.
- Use Spring Kafka `@KafkaListener` with auto-commit disabled, acknowledge **after** durable Mongo persistence, configure `DefaultErrorHandler` for **initial delivery plus two retries** (three total attempts) and dead-letter topic `<topic>.DLT`, ensuring DLT confirmation before committing offsets.
- Keep notifications and payment simulation local. Internal read API uses an API key and must not expose that key to the browser.

**Cost:** Docker Compose local, no AWS or paid services.

## Risks requiring a design verdict

- The simple relay holds a PostgreSQL transaction during Kafka send (bounded to ~2 seconds per message), potentially consuming connection pool resources when Kafka is unavailable. Bound concurrency and test it before approval.
- Kafka/Mongo are not one transaction; duplicates are possible and must be harmless.
- Insertion `clock_timestamp()` plus UUID tie-break makes SELECT deterministic, **not** automatically ordered by commit across concurrent producers. Prove real ordering or consider an approved V17 sequencing design.
- DLT may require manual replay after long Mongo outages.
- EventCancelled fanout to organizer/buyers needs precise eligibility rules, bounded work and recipient approval.
- Define full evidence equality/digest for same eventId with contradictory payload, not merely matching type and recipient.
- Resolve the phrase "three retries" versus **three total deliveries** in the original requirement and draft design before implementation.

## Consequences and gate

This ADR changes **no service, migration or public API** during Phase 3/4 remediation. The proposed components and test matrix are in [F5-01](../phase-5/F5-01-technical-design.md) and [F5-02](../phase-5/F5-02-test-and-acceptance-plan.md).

**Approve separately before implementation:** outbox relay transaction strategy, recipient/fanout semantics, V1 event envelope, retry/DLT policy, read API security, cross-aggregate ordering criteria and correlation propagation. If testing invalidates short relay transactions or ordering, reopen the design.

## Technical references

- [PostgreSQL 17 SELECT FOR UPDATE SKIP LOCKED](https://www.postgresql.org/docs/17/sql-select.html).
- [Spring Kafka 3.3 — KafkaTemplate](https://docs.spring.io/spring-kafka/reference/3.3/kafka/sending-messages.html), [manual acknowledgement](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html), [error handling and DLT](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).
- [Spring Data MongoDB — indexes](https://docs.spring.io/spring-data/mongodb/reference/mongodb/mapping/mapping-index-management.html).
- [Transactional Outbox pattern](https://microservices.io/patterns/data/transactional-outbox.html) (design pattern, not an official standard).

**Phase 5 remains BLOCKED.** Do not implement writers, producers, consumers, retries or Mongo persistence under this ADR.
