# Architecture decision register (ADR)

**Format:** context, alternatives, decision and consequences. ADRs are numbered and are not silently deleted or rewritten. When a decision changes, a subsequent decision supersedes or qualifies it. The detailed ADRs are in [`docs/adr/`](adr/).

**Mandatory decision verification:** [official-source double-check policy](engineering-decision-verification.md), including version-matched primary documentation, an independent second source when applicable, inspection of real code and SHA-specific test evidence. External references validate mechanisms, **not Tech Lead or architectural approval**.

**Current branch:** `main` only. Former `dev` is deleted; ADR-005 below describes the historical branch strategy, **not a current instruction**. The owner explicitly authorized the English-only policy in [ADR-025](adr/ADR-025-english-only-project-policy.md); this supersedes the original language requirement, not business contracts. ADR-022/023 remain **PROPOSED**. ADR-024 is **owner-approved** with independent Tech Lead conformity review pending; ADR-026 records bounded related Phase 3 exceptions.

---

## ADR-001: Spring Boot 3.5.x instead of Spring Boot 4

- **Date:** 2026-11-05.
- **Context:** Spring Boot 4.1.x was stable when the decision was recorded. The specification requested 3.5.x unless all project dependencies supported Boot 4.
- **Decision:** Java 21 with **Spring Boot 3.5.16**, the then-current stable Boot 3.x line.
- **Consequences:** Compatibility with springdoc 2.8.x, Testcontainers 1.21.x and the available documentation. A future Boot 4 upgrade requires a separate verified decision.

## ADR-002: Pin development tool versions from Phase 0

- **Date:** 2026-11-05.
- **Context:** Verify current stable versions and explicitly pin them instead of using `latest`.
- **Decision:** Versions in the root POM and `docker-compose.yml` at that time:

| Tool | Version | Recorded rationale |
| --- | --- | --- |
| Maven Wrapper | Maven 3.9.16 | Wrapper 3.3.4, `only-script` |
| Spring Boot | 3.5.16 | ADR-001 |
| Testcontainers BOM | 1.21.4 | 2.0.x changed artifact names; Boot 3.5 supported 1.21.x |
| springdoc-openapi | 2.8.17 | 3.x targets Boot 4 |
| JaCoCo | 0.8.16 | Coverage enforcement |
| Spotless / google-java-format | 3.11.0 / 2.0.0 | Recorded compatibility requirement |
| Maven Enforcer | 3.7.0 | Java 21 and Maven >= 3.9 |
| PostgreSQL | `postgres:17.11-alpine` | Pinned tag |
| Redis | `redis:7.4.12-alpine` | Specification requires Redis 7 |
| Kafka | `apache/kafka:4.4.0` | KRaft-mode scaffold only until authorized Phase 5 |
| MongoDB | `mongo:7.0.45` | Specification requires MongoDB 7 |

- **Consequences:** Dependabot upgrades remain planned for Phase 7; later version upgrades must be tested and documented rather than inferred from this historical table.

## ADR-003: Package and Maven module names

- **Date:** 2026-11-05.
- **Decision:** `com.portfolio.ticketing` for the API, `com.portfolio.notification` for the future notification application and `com.portfolio.events` for shared event contracts. `EventEnvelope<T>` belongs to `common-events` to prevent duplicated contracts.
- **Consequences:** Both applications depend on `common-events`, not on each other.

## ADR-004: Configure minimum coverage from the beginning

- **Date:** 2026-11-05.
- **Context:** The required minimum 70% applies to `service` and `domain` packages.
- **Decision:** JaCoCo evaluates the combined unit+integration execution data by `*.service`/`*.domain` package, configured from Phase 0. Before those packages existed, no eligible classes were evaluated.
- **Consequences:** Untested business code can break the build from Phase 1 onward.

## ADR-005: Early CI workflow and the historical `dev` branch

- **Date:** 2026-11-05.
- **Context:** The specification places workflows in Phase 7, but independent Docker-capable GitHub verification was useful earlier where the assisted environment could not run Testcontainers.
- **Original decision:** Create `.github/workflows/ci.yml` with `build-test` (Temurin 21, Maven cache, `permissions: contents: read`, concurrency and Surefire/Failsafe/JaCoCo artifacts). Add `docker-build` in Phase 6 when Dockerfiles exist and deployment in Phase 7. At that time development used `dev`, with `main` receiving verified changes; actions were pinned by maintained major tags.
- **Consequences:** GitHub validated pushes/PRs early; Phase 7 retained its deployment work.
- **Subsequent owner decision:** `dev` was retired; **`main` is now the only permanent branch**. Do not recreate `dev`. The CI workflow now also includes Angular and Python jobs. Update any stale operating instructions while retaining this historical ADR entry.

## ADR-006: JWT through Spring Security OAuth2 Resource Server with HS256

- **Date:** 2026-11-05.
- **Context:** Phase 2 requires JWT issuance and validation; alternatives included `jjwt` and built-in Spring Security Nimbus support.
- **Decision:** `spring-boot-starter-oauth2-resource-server` with `NimbusJwtEncoder`/`NimbusJwtDecoder`, HS256 and Base64-decoded `JWT_SECRET` of at least 256 bits, validated at startup. Claims include `sub` (user UUID), `role`, `iat`, `exp`, `type` (access/refresh); refresh adds `jti`. The access decoder rejects tokens whose `type` is not `access`.
- **Consequences:** No extra JWT dependency or custom signature filter; built-in bearer authentication validates signing and expiration. Owner-approved at the time.

## ADR-007: Introduce Redis in Phase 2 for refresh tokens and login limits

- **Date:** 2026-11-05.
- **Context:** Specification `9 requires hashed refresh `jti` revocation and Redis login rate limiting, although wider Redis functionality was scheduled for Phase 4.
- **Decision:** Add `spring-boot-starter-data-redis` in Phase 2. Store `auth:refresh:<sha256(jti)>` with refresh token TTL, remove on logout. Fixed-window login limit uses `INCR` on `rate:login:<ip>:<email>` with initial `EXPIRE` and HTTP 429 on exceedance.
- **Consequences:** Integration tests also run Redis via Testcontainers. Outage degradation is handled in Phase 4. Owner-approved at the time.

## ADR-008: Manual record DTO mapping

- **Date:** 2026-11-05.
- **Context:** Choose between MapStruct and explicit mapping.
- **Decision:** Use static `from(...)` methods on response records, continuing the Phase 1 pattern.
- **Consequences:** No additional annotation processor; explicit mappings are covered by controller tests.

## ADR-009: Order cancellation and refunds

- **Date:** 2026-11-06.
- **Original context:** Specification allows cancelling `PENDING_PAYMENT` and refunding `PAID` before event start, but provided neither a `CANCELLED` payment state nor an explicit restock rule for refunds.
- **Original decision:** Cancel a pending order by releasing its active reservation/stock, leaving payment `PENDING` because no payment cancellation state exists. The terminal order status prevents later successful confirmation. Originally, refunding a paid order transitioned payment to `REFUNDED` and tickets to `CANCELLED` without automatically restocking; reservation remains `CONFIRMED` as history.
- **Consequences:** A future restock-on-refund policy needs a new explicit business rule and ADR.
- **Supersession in Phase 4:** **Accepted ADR-019 replaced immediate local refund confirmation.** Cancelling a paid order merely requests a refund. Payment/order become `REFUNDED` only after valid `PAYMENT_REFUND` provider evidence. The no-automatic-restock decision is preserved.

## ADR-010: Atomic conditional inventory UPDATE

- **Date:** 2026-11-06; **Phase:** 3.
- **Decision:** Decrement stock with a single `UPDATE ... WHERE available_quantity >= :quantity`. PostgreSQL serializes row updates; affected rows determine success. This avoids a speculative pre-read and retries around a highly contended optimistic counter.
- **Detail:** [ADR-010](adr/ADR-010-atomic-inventory-update.md).

## ADR-011: Order and webhook idempotency

- **Date:** 2026-11-06; **Phase:** 3.
- **Decision:** Unique order key scoped to buyer and reservation; replaying the same key/reservation returns the existing order, while reusing the key with another reservation is a conflict. Webhooks validate HMAC and apply conditional idempotent transitions using payment status and provider reference.
- **Historical limitation, since superseded:** The original ADR described a missing persistent provider event inbox. **Flyway V14/V15 and accepted ADR-019 subsequently implemented persistent provider event, operation and semantic identities.** Do not describe the inbox as currently missing.
- **Detail:** [ADR-011](adr/ADR-011-order-and-webhook-idempotency.md).

## ADR-012: Reservation expiration with FOR UPDATE SKIP LOCKED

- **Date:** 2026-11-06; **Phase:** 3.
- **Decision:** Workers claim bounded overdue reservation batches without waiting on already locked rows. Conditional state transition and inventory release execute atomically in one PostgreSQL transaction.
- **Detail:** [ADR-012](adr/ADR-012-reservation-expiration-skip-locked.md).

## ADR-013: Redis metadata-only catalog cache

- **Date:** 2026-11-07; **Phase:** 4.
- **Context:** Catalog requests repeatedly read relatively stable metadata, while availability changes with every hold and release. Caching complete response bodies could expose stale stock.
- **Alternatives:** Cache entire responses, cache JPA entities or cache serializable metadata DTOs while reading live inventory.
- **Decision:** Cache-aside over metadata DTO records only. `availableQuantity` always comes from PostgreSQL through a lightweight projection. Configurable ticket-type TTL (`TICKET_TYPES_CACHE_TTL`, default 5 minutes). Catalog writes evict entries; `RedisCacheManager` uses transaction awareness to defer eviction until commit. Redis `DataAccessException` cache failures log and fall back to PostgreSQL.
- **Consequences:** Fewer metadata reads without caching critical stock. Broad catalog cache invalidation was accepted for simplicity because the update service receives ticket-type ID rather than event ID. CI must cover serialization, TTL, invalidation and Redis outage.

## ADR-014: Redis outage resilience and authentication degradation

- **Date:** 2026-11-10; **Phase:** 4.
- **Decision:** Risk-dependent behavior: catalog cache falls back to PostgreSQL; failed invalidation leaves metadata bounded by TTL; login rate limiting fails open; refresh-token sessions fail closed with HTTP 503; access JWT validation remains local without Redis.
- **Limitations:** Stale catalog metadata may persist until TTL after failed eviction; rate limiting is temporarily bypassed while Redis is unavailable, but refresh-dependent actions must not be accepted.
- **Historical evidence:** CI #107 on `24d6a514f1f8a4d467b1c457d12a478e24f75c36`: 313 unit and 57 integration tests, zero failures/errors. Observed suspended-Redis latency was approximately 1,014 ms for catalog and 1,023 ms for public event GET.
- **Detail:** [ADR-014](adr/ADR-014-redis-resilience-and-auth-degradation.md).

## ADR-015: At most one order per reservation

- **Date:** 2026-11-12; **Phase:** 3.
- **Decision:** Combine `OrderService.create` guard with the non-partial unique V11 index `ux_orders_reservation_id` on `orders(reservation_id)`. PostgreSQL enforces it for cancelled orders too; integrity conflicts map to HTTP 409.
- **Consequences:** One reservation can create zero or one order. A cancelled order's reservation cannot be reused; create a new reservation for another purchase.
- **Detail:** [ADR-015](adr/ADR-015-one-order-per-reservation.md).

## ADR-016: Global lock order and T → R protocol

- **Date:** 2026-11-13; **Phase:** 4.
- **Decision:** All participating writes follow the documented global lock order. Inventory and reservations acquire T before R, recheck mutable state after locks and use PostgreSQL `clock_timestamp()` for expiry decisions.
- **Detail:** [ADR-016](adr/ADR-016-global-lock-order.md).

## ADR-017: Stable commercial units and issuance generations

- **Date:** 2026-11-13; **Phase:** 4.
- **Decision:** Each unit has a stable UUID; commercial generations and associations connect units/orders/tickets with composite FKs and unique unit/generation constraints. Replacement requires a new generation.
- **Subsequent implementation nuance:** The older narrative replacement sequence was adjusted in F4-03 to preserve implicit FK lock order (create new unit/generation association before operations that could lock older tickets). Do not restore obsolete steps merely by reading the historical ADR prose.
- **Detail:** [ADR-017](adr/ADR-017-commercial-units-and-generations.md).

## ADR-018: Operation cycles, leases, fencing and projections

- **Date:** 2026-11-13; **Phase:** 4.
- **Decision:** Separate RX, CLAIM, APPLY and PROJECT transactions for provider intake, technical work, commercial application and projection. Effective jobs, leases and fencing tokens prevent stale-worker writes. A previously committed APPLY must not run the business callback during projection recovery.
- **Subsequent correction:** REV-DEV-01 found the original code violated that recovery condition. Main introduced a durable result reuse fix and permanent regression. The old invariant remains the accepted requirement.
- **Detail:** [ADR-018](adr/ADR-018-operation-cycles-and-worker-recovery.md).

## ADR-019: Provider identity, semantic idempotency and late capture recovery

- **Date:** 2026-11-13; **Phase:** 4.
- **Decision:** Provider event, provider operation and semantic application use distinct identities and unique persistence constraints. H79 permits bounded late recovery and requires real provider refund evidence before `REFUNDED`. A new independently signed event can affect the same provider operation under the proposed minimal refund contract, but an already-applied job must not be replayed as new business evidence.
- **Detail:** [ADR-019](adr/ADR-019-provider-identity-and-late-payment-recovery.md).

## ADR-020: Complete Phase 4 Redis scope

- **Date:** 2026-11-13; **Phase:** 4.
- **Decision:** Add public-event metadata cache with 60-second TTL, expiring reservation projection keys and reservation/order rate limiting. PostgreSQL remains authoritative for stock/business state.
- **Detail:** [ADR-020](adr/ADR-020-phase-4-redis-completion.md).

## Decisions pending an explicit verdict

- **H84-01 — SIMULATED provider evidence contract:** [ADR-022](adr/ADR-022-simulated-refund-evidence-contract.md) proposes immutable digest/HTTP 409 and a **new signed provider event** to reevaluate a previously early refund, without automatic financial reconciliation. Historical CI #302/#306 validated state classification and transitions; later main regressions correct same-job APPLY/PROJECT recovery. **PROPOSED, not accepted.**
- **Event cancellation cross-module coordinator:** [ADR-024](adr/ADR-024-event-cancellation-transactional-exception.md) approves a narrow owner-authorized exception preserving E → T → R → O, rollback and after-commit Redis projection. Historical CI #315/#317 verified rollback and 390 API units/171 integrations. **Approved by project owner on 2026-11-22** for this coordinator only; independent Tech Lead conformity review still pending. AUD-05's other Phase 3 boundaries are addressed by ADR-026, and Phase 4 boundaries remain separate.
- **AWS region:** a region would be selected during the authorized AWS path and would require explicit owner confirmation before provisioning. **No cloud resources are authorized under Phases 0–4.**
- **AUD-03, AUD-05, AUD-10, AUD-12 and BACK-01:** see the [superseding Phase 3 owner dispositions and current audit register](audit/PENDING-DECISIONS.md); Phase 4-specific H78/H84 remain open.

## ADR-021: Conceal foreign-owned resources under Phase 2 authorization

- **Date:** 2026-11-13; **Phase:** 2.
- **Decision:** Foreign and nonexistent private resources both return HTTP 404 for owner-scoped operations; HTTP 403 remains for authenticated users lacking a functional role. ADMIN retains global management, while ORGANIZER can manage only their events/ticket types.
- **Detail:** [ADR-021](adr/ADR-021-phase-2-authorization-resource-concealment.md).
- **Matrix:** [Phase 2 authorization matrix](phase-2/authorization-matrix.md).

## ADR-022: Minimal simulated refund evidence contract — PROPOSED

- **Date:** 2026-11-14; **Phase:** 4.
- **Status:** Proposed for Tech Lead review. It is neither an accepted architecture decision nor formal phase closure.
- **Proposal:** Stable reference per provider operation, `NO_OP` until newly signed eligible refund evidence arrives after capture, with no expansion into a real financial provider.
- **Detail:** [ADR-022](adr/ADR-022-simulated-refund-evidence-contract.md).

## ADR-023: Outbox and idempotent notification delivery — PROPOSED

- **Date:** 2026-11-14; **Phase:** 5 **design only**.
- **Status:** Proposed to Tech Lead; **NOT accepted or implemented**.
- **Alternatives:** Direct publication (rejected), bounded `SKIP LOCKED` relay over V10 (proposed), a claim/lease with additive migration (conditional on evidence), CDC/2PC (outside scope).
- **Detail:** [ADR-023](adr/ADR-023-phase-5-outbox-and-notification-delivery.md); [F5-01](phase-5/F5-01-technical-design.md) and [F5-02](phase-5/F5-02-test-and-acceptance-plan.md).

## ADR-024: Limited event-cancellation transactional exception — OWNER APPROVED

- **Date:** 2026-11-14; **Phase:** 3.
- **Status:** Owner-approved on 2026-11-22 with strictly limited scope. The independent Tech Lead verdict and formal Phase 3 closure are **not** implied.
- **Alternatives:** Refactor through module-owned application services or a narrow deliberate exception. The latter is recommended for current atomicity/lock order, subject to the broader AUD-05 assessment.
- **Detail:** [ADR-024](adr/ADR-024-event-cancellation-transactional-exception.md); [F3-01](phase-3/F3-01-closure-review.md).

## ADR-025: English-only repository policy — OWNER AUTHORIZED

- **Date:** 2026-11-21; **Scope:** current first-party project content.
- **Status:** Owner-authorized language policy, **not** an override of ADR approval gates. Translate active content and current historical reports faithfully; preserve Git history and machine contracts. Historical Spanish routes are accepted as temporary redirect compatibility aliases, not canonical UI routes.
- **Detail:** [ADR-025](adr/ADR-025-english-only-project-policy.md).


## ADR-026: Bounded Phase 3 cross-module coordination — OWNER APPROVED

- **Date:** 2026-11-22; **Phase:** 3.
- **Status:** Owner-approved decision with pending independent Tech Lead conformity review; does not grant a blanket persistence exception.
- **Decision:** Preserve existing E → T → R → O transaction semantics and narrowly allow `OrderExpirationProcessor` to coordinate reservation and order state, plus bounded unlocked expiration candidate joins. ADR-024 alone covers `EventCancellationService.cancel(UUID)`; remaining Phase 4 operational joins are not approved by this ADR.
- **Technical basis:** Spring Framework 6.2 transaction propagation, PostgreSQL 17 explicit locks, Spring Modulith 1.4 modular boundaries and current Testcontainers evidence.
- **Detail:** [ADR-026](adr/ADR-026-bounded-phase-3-cross-module-coordination.md); [Phase 3 owner disposition](phase-3/F3-02-owner-disposition-2026-11-22.md).
