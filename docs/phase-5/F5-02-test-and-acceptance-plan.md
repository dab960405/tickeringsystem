# F5-02 — Proposed Phase 5 test and acceptance plan

- **Status:** **PROPOSED TEST SPECIFICATION — NOT EXECUTED. Phase 5 BLOCKED.**
- **Historical design baseline:** former `dev` @ `73eb12d307af696533ba182574d7f9f54ab2ec42`; CI #310 was green for that **historical** code/documentation. `main` is now the sole permanent branch.
- **Depends on:** proposed [F5-01](F5-01-technical-design.md) and [ADR-023](../adr/ADR-023-phase-5-outbox-and-notification-delivery.md).
- **Entry block:** Phases 3 and 4 lack formal closure, and no independent Phase 5 implementation authorization has been issued.

## Prerequisite 0 — local manual validation before writing messaging code

After independent Phase 5 authorization, use [LOCAL_RUN](../LOCAL_RUN.md) and [flow.http](../flow.http). The original proposed steps start all four infrastructure services with `docker compose --profile infra up -d`, check health and exercise a **simulated** payment. The development General ticket is USD 45.00; older `flow.http` text contained a misleading USD 80.00 example, **corrected in later docs**. Always use the **actual order total** and sign **exact outgoing bytes**. Record SHA, commands, redacted results and actual failures; never label a local smoke test green without executing it.

```bash
docker compose --profile infra up -d
docker compose ps
./mvnw -B clean verify
./mvnw -pl ticketing-api -am package -DskipTests
java -jar ticketing-api/target/ticketing-api-0.1.0-SNAPSHOT.jar
# In another terminal:
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/api/v1/events
```

**Those commands are future instructions, not evidence of execution for this proposal. Do not start Kafka/Mongo functionality during Phase 3/4 closure.**

## Proposed acceptance test matrix — NOT IMPLEMENTED

| ID | Test case | Required observable invariant | Proposed tool |
| --- | --- | --- | --- |
| F5-T01 | Emit `OrderConfirmed` after valid capture | Fulfilled order, issued tickets and exactly one outbox entry in the **same commit** | PostgreSQL Testcontainers |
| F5-T02 | Failure after outbox insert but before commit | Business operation and outbox rollback together | PostgreSQL Testcontainers |
| F5-T03 | Duplicate webhook or `NO_OP` | No new outbox or duplicated commercial effect | PostgreSQL IT |
| F5-T04 | Late `UNFULFILLABLE` capture | Must not emit a false `OrderConfirmed` | PostgreSQL IT |
| F5-T05 | Buyer/payment/event pending-order cancellation | One `OrderCancelled` for each real terminal transition | PostgreSQL IT |
| F5-T06 | Confirmed refund vs `PENDING`/`FAILED`/`NO_OP` | `OrderRefunded` only after actual valid provider refund evidence | PostgreSQL IT |
| F5-T07 | Reservation TTL / order expiration | One `ReservationExpired` per real terminal transition | PostgreSQL IT |
| F5-T08 | Event cancellation and repeated invocation | `EventCancelled` per eligible recipient with no duplicates | PostgreSQL IT |
| F5-T09 | Kafka unavailable while relaying | Business committed, `published_at IS NULL`, no event loss | PostgreSQL + Kafka IT |
| F5-T10 | Kafka broker recovers | Publish and mark timestamp **only after broker ACK** | PostgreSQL + Kafka IT |
| F5-T11 | Kafka ACK followed by simulated PG rollback | Kafka duplicate possible; only **one Mongo document** | Kafka + Mongo IT |
| F5-T12 | Two concurrent outbox relays | `SKIP LOCKED` prevents simultaneous work on same row | PostgreSQL + Kafka IT |
| F5-T13 | Two events for one aggregate | Enforce required per-aggregate emission order | PostgreSQL + Kafka IT |
| F5-T14 | Different aggregate events | Independent aggregates can publish concurrently | PostgreSQL + Kafka IT |
| F5-T15 | Valid V1 JSON envelope | Deserialize type, version, UUID, timestamp and recipient | JUnit/Jackson |
| F5-T16 | Unknown version or invalid payload | No notification saved; route to DLT | Kafka + Mongo IT |
| F5-T17 | Duplicate identical `eventId` | Unique Mongo index yields one notification | Mongo Testcontainers IT |
| F5-T18 | Same `eventId` with contradictory payload | Controlled error and DLT, **not** a valid duplicate | Kafka + Mongo IT |
| F5-T19 | Mongo unavailable | No success ACK; **two retries and DLT after the third total delivery** under current proposed detail; reconcile this with “three retries” wording before approval | Kafka + Mongo IT |
| F5-T20 | DLT unavailable | Do not acknowledge recovered or silently lose event | Kafka IT |
| F5-T21 | Missing/invalid internal API key | HTTP 401; no notification document leakage | MockMvc |
| F5-T22 | Valid API key querying notifications | Stable pagination, filter by userId, size ≤100 | Mongo IT |
| F5-T23 | Missing `NOTIFICATION_API_KEY` | Functional notification service must not start insecurely | Spring config test |
| F5-T24 | End-to-end buyer purchase | Kafka events become persisted Mongo notification; Awaitility | PG/Redis/Kafka/Mongo Testcontainers |
| F5-T25 | Whole regression suite | Existing Java tests retained, service/domain JaCoCo >=70%, Spotless | `./mvnw -B clean verify` |
| F5-T26 | Java 21 virtual threads | Effective listener executor/thread checked with correct ACK | Kafka IT |
| F5-T27 | Prior-test container isolation | Existing Phases 0–4 integration tests do not need Kafka/Mongo; future outbox writer uses the business test TX | Maven Failsafe/Spring context |

## Test strategy and operational requirements

Use Surefire `*Test` and Failsafe `*IT` naming conventions, do **not** disable preexisting tests and do not rewrite historical counts (original plan referenced 390 unit + 170 integration at its then-current SHA). Kafka/Mongo containers should be restricted to **new Phase 5 test suites**, not started for every earlier integration test. Use Spring `@ServiceConnection` where supported, with dynamic properties as needed.

Avoid `Thread.sleep` for asynchronous coordination; use Awaitility/CountDownLatch with bounded deadlines. Verify actual consumer ACK behavior, not merely a final Mongo count.

**Required report after future implementation:** immutable code SHA, commands, totals by suite, GitHub Actions run, failures, actual warnings, redacted local smoke evidence, Kafka/Mongo integration evidence, and formal reviewer disposition.

**Formal acceptance only after authorization:** T01–T27 passing or explicitly approved exceptions; documented complete local run; simulated notifications only; zero AWS/real gateway/real email; Tech Lead approval required. **This proposed test matrix alone does not close Phase 5.**

## Open design decisions — resolve before Phase 5 implementation

- Distinguish three total delivery attempts from three retries; the current proposal is inconsistent.
- Define Kafka ACK/PG transaction boundary, outbox ordering and relay claim semantics.
- Specify precise EventCancelled fanout/eligibility under bounded transactions.
- Compare full canonical payload evidence for conflicting duplicate `eventId`, not only type/recipient.
- Define HTTP correlation propagation and safe internal API key usage.

See [F5-READINESS](F5-READINESS.md), [F5-01](F5-01-technical-design.md) and proposed ADR-023. **Phase 5 implementation remains BLOCKED.**
