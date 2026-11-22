# F5-00 — Proposed Phase 5 plan and local validation

- **Status:** **DOCUMENTATION-ONLY PROPOSAL. Phase 5 has NOT started and is BLOCKED.**
- **Proposed design:** [F5-01](F5-01-technical-design.md), [F5-02](F5-02-test-and-acceptance-plan.md) and [ADR-023](../adr/ADR-023-phase-5-outbox-and-notification-delivery.md); independent Tech Lead review is pending.
- **Entry requirement:** explicit formal Phase 3 and Phase 4 closures and a separate owner/Tech Lead authorization to implement Phase 5.
- **Original intent:** first validate the current application locally; **after authorization**, implement transactional outbox, Kafka and simulated notification persistence in MongoDB.

**Historical note:** the original text described the first manual local run as future work. Later evidence in [LOCAL_RUN](../LOCAL_RUN.md) documents a 2026-11-15 PostgreSQL/Redis API smoke walkthrough (34 HTTP checks). Preserve that historical evidence; new Phase 5 validation must still prove its own exact SHA and scope. This document grants **no permission to start messaging work**.

## Proposed work order — only after Phase 5 authorization

### 0. Verify the current platform locally

Before editing messaging, use [LOCAL_RUN](../LOCAL_RUN.md) with Java 21, Docker Compose v2 and Maven Wrapper. Do not use F4 closure work as a pretext to start Kafka/Mongo business processing.

1. Prepare trusted `.env` from `.env.example`, generate independent JWT/HMAC secrets and export process variables; do not commit secrets.
2. Start required PostgreSQL/Redis services and verify health. The original F5 plan additionally requested `docker compose --profile infra up -d` to run all four services (PostgreSQL, Redis, Kafka, MongoDB); do this **only after Phase 5 authorization**, because current 0–4 validation requires PostgreSQL/Redis alone.
3. Run `./mvnw -B clean verify` with Docker/Testcontainers available; record exact result and warnings.
4. Start the API using `./mvnw -pl ticketing-api -am package -DskipTests` followed by `java -jar ticketing-api/target/ticketing-api-0.1.0-SNAPSHOT.jar`.
5. Check `http://localhost:8080/actuator/health`, `/swagger-ui.html` and the public catalog seeded with explicit `dev` profile.
6. Walk through registration/login → reservation → order → **simulated** HMAC payment webhook → ticket issuance using [flow.http](../flow.http). Derive amount/reference from the real order; test failure and an idempotent retry.
7. Keep verifiable local evidence, record concrete defects, and correct them before proceeding to the next authorized increment.

### 1. Event publishing through transactional outbox — NOT IMPLEMENTED

The proposed architecture reuses existing `outbox_events`, inserting business events in the **same PostgreSQL transactions** as their corresponding state changes. A future scheduled relay would deliver to Kafka and record publication state. Design retry/idempotency explicitly and do not claim end-to-end exactly-once delivery.

### 2. Kafka and notification service — NOT IMPLEMENTED

The original design proposes three topics, `ticketing.orders`, `ticketing.reservations` and `ticketing.events`, each with three partitions and development replication factor 1. A future `notification-service` consumer would use manual acknowledgements, a bounded retry policy and DLT. MongoDB uniqueness should deduplicate processed event IDs. Email remains **simulated**, as log/document evidence, never real outbound messages.

**Pending design decision:** original text says “three retries,” while other proposed documents say three total attempts; decide unambiguously before implementation.

### 3. Future full functional integration — NOT EXECUTED

After implementation is separately authorized, verify registration → order → simulated capture → outbox → Kafka → consumer → Mongo notification through Testcontainers and Awaitility. Include duplicate delivery, outages and retries. Record exact SHA and green CI and run the complete local Compose journey before requesting **Phase 5** acceptance.

## Scope and exclusions

- Future Phase 5 covers messaging, local evidence and delivery tests **only after authorization**. Multi-stage Dockerfiles, full Compose and Grafana belong primarily to Phase 6.
- No real payments, real external email, paid cloud services or AWS deployment.
- Current permanent branch is **`main` only**; the historical statement that `main` was not to be touched described an earlier branching policy. Do not recreate `dev`.
- No cloud resource may be created without explicit owner authorization and a documented zero-cost assessment.

## Original official references

- Original Ticketing technical specification, ``12, 13, 15, 16 and 22.
- [Docker Compose profiles](https://docs.docker.com/compose/how-tos/profiles/).
- [Spring Kafka 3.3 message listener containers and manual acknowledgements](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html) — **historical versioned source; verify actual version before implementation**.
- [PostgreSQL 17 ON CONFLICT](https://www.postgresql.org/docs/17/sql-insert.html) for persistent idempotent identity writes.

**Phase 5 entry verdict: BLOCKED until Phases 3/4 have formal closure and a new explicit start authorization.** See [F5-READINESS](F5-READINESS.md).
