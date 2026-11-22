# Ticketing project progress

**Updated:** 2026-11-21 (America/Bogota). **Permanent branch:** `main` only. Historical `dev` commits and CI references below are retained solely as provenance; the branch no longer exists.

## Current status and formal gates

**Phases 3 and 4: technically implemented but formally OPEN. Phase 5: BLOCKED, not implemented.** The independent Tech Lead verdict on ADR-024 and Phase 3 must precede formal Phase 3 closure. An explicit verdict on ADR-022/H84-01 and H75–H84, plus Phase 3 closure, must precede formal Phase 4 closure. Green CI does **not** constitute an architectural decision or owner authorization.

- [Phase 3 closure dossier](phase-3/F3-01-closure-review.md), [ADR-024](adr/ADR-024-event-cancellation-transactional-exception.md), [issue #5](https://github.com/dab960405/tickeringsystem/issues/5) — **pending independent verdict**.
- [Phase 4 closure dossier](phase-4/F4-05-closure-review.md), [ADR-022](adr/ADR-022-simulated-refund-evidence-contract.md), [issue #6](https://github.com/dab960405/tickeringsystem/issues/6) — **pending independent verdict and prior Phase 3 closure**.
- [Current remediation record](audit/MAIN-REMEDIATION-2026-11-21.md), [remaining decisions](audit/PENDING-DECISIONS.md), [architecture](architecture.md), [ERD Flyway V1–V16](erd.md), and [engineering evidence policy](engineering-decision-verification.md).
- [ADR-025](adr/ADR-025-english-only-project-policy.md) records the explicit owner's English-only repository policy; it **does not** approve other ADRs.
- Kafka, functional outbox writing/relay, Mongo notification storage and notification consumers remain Phase 5 work and **must not start** without separate authorization.

### Latest directly verified CI for recent changes

| Main SHA | GitHub Actions result | Scope |
| --- | --- | --- |
| `7220e2741e20a30f09c23788460707c311e6e346` | [CI #330 SUCCESS](https://github.com/dab960405/tickeringsystem/actions/runs/44982893655) | Backend, Angular, Python; English UI/ADR adjustments |
| `b275ea8ec190945db75f884884ab9427299021c3` | [CI #331 FAILURE](https://github.com/dab960405/tickeringsystem/actions/runs/44983063132) | **Expected RED regression**: JVM/PG clock skew in overdue reservation discovery |
| `f6ecd12d9bd4b6f92757892585c97e599470006b` | [CI #332 FAILURE](https://github.com/dab960405/tickeringsystem/actions/runs/44983220100) | Regression passed, but two Spotless format violations |
| `c7d21a452ca098557a468ff16a75fb31b8a14ae9` | [CI #333 SUCCESS](https://github.com/dab960405/tickeringsystem/actions/runs/44983475556) | PostgreSQL-authoritative expiry discovery, 5/5 service unit tests, Java/Angular/Python, JaCoCo/Spotless |
| `6f4e14935428693705e0236289bf5db1b933b73b` | [CI #334 SUCCESS](https://github.com/dab960405/tickeringsystem/actions/runs/44983929859) | English frontend guides, CLI, HTTP sample and historical phase matrix |
| `cf75faa0bddc0d7302bb59e8386501ceeea62bb3` | [CI #335 SUCCESS](https://github.com/dab960405/tickeringsystem/actions/runs/44984185620) | English current architecture, source-verification policy and local run instructions |

**Later document-only commits:** require their own completed, successful CI; do not infer a green conclusion from the historical rows above.

## H75–H84 closing matrix — historical implementation evidence

The individually assigned original severity document was not recovered. **Do not invent or reassign historical severities.** The entries below record mechanisms and evidence; final dispositions still require the independent Tech Lead verdict.

| Finding | Technical status at historical evidence SHA | Remaining gate |
| --- | --- | --- |
| H75 — global lock order | CI #292 audited explicit locks, implicit FK locks and JPA flush, with two corrections | Reaudit when locks, FKs or flush order change |
| H76 — T → R lock protocol | CI #292 covered creation, cancellation, confirmation, recovery, expiration and order creation | Record final reviewer acceptance |
| H77 — commercial units/generations | V16, stable commercial unit identity, generations, backfill, issuance/reissuance, integrity constraints; CI #278 | Preserve under new changes and obtain verdict |
| H78 — durable jobs, leases, fencing, recovery | V15 and RX/CLAIM/APPLY/PROJECT; CI #306 `OperationWorkerIT` 12/12 | Later **REV-DEV-01** exposed a same-job recovery defect; main corrected it in CI #324. Validate new evidence and obtain verdict |
| H79 — late payment recovery | CI #296 covered expiry, grace, concurrent expiration, `UNFULFILLABLE` and `REQUIRED` | Confirm newer cancellation/expiry fixes and close dossier |
| H80 — persistent identities and semantic idempotency | V14/V15 provider inbox, three identities, digests, append-only data and dispositions; CI #298/#306 | Tech Lead verdict on proposed ADR-022 |
| H81 — Redis completion | Event/ticket-type cache, reservation projections, login/reservation/order rate limits and outage behavior; CI #286 | Acceptance documentation |
| H82 — operation configuration | CI #296 tested configured RX/CLAIM/APPLY/PROJECT timeouts and defaults | Acceptance documentation |
| H83 — implicit lock consistency | CI #292 corrected EN → U reissuance and R → T FK ordering during order creation | Reaudit when locks/FKs/flush change |
| H84 — refunds and races | CI #302/#304/#306, CI #309 added a new signed refund event regression (36/36 `OrderPaymentFlowIT`, 170 integration tests) | ADR-022/H84-01 decision, plus latest durable projection recovery proof |

## Increment H80 — Flyway V14

- `provider_events`, `provider_operations` and `semantic_applications` have independent uniqueness constraints and append-only triggers.
- `ProviderEventInboxService` records receipt in an independent transaction and detects conflicting reuse of identities/payloads.
- `SemanticApplicationService` registers a semantic `(application_scope, operation_kind, idempotency_key)` identity with business effects in the same TX; a matching duplicate digest produces no additional effect.
- `PaymentWebhookService` relies on persisted event/operation identity, not only order status, when accepting signed callbacks.
- The historical local checkpoint ran a full green unit suite and `OrderPaymentFlowIT` 10/10 with PostgreSQL/Testcontainers, including repeated/different events, digest conflicts and append-only proof. This increment alone did **not** implement cycles, leases or worker recovery; H80's completion depended on H78/V15.

## Increment H78/H82 — Flyway V15

- V15 introduced numbered operation cycles, participations, jobs, executions, dispositions and projections.
- `OperationTransactionExecutor` applies validated `OperationProperties` limits and timeouts for RX/CLAIM/APPLY/PROJECT.
- `OperationJobService` implements J → XA claims, lease renewal/expiration, fencing, generation replacement, backoff and attempt exhaustion.
- `PaymentOperationService` coordinates semantic/business effects in one transaction; **projection recovery must reuse a committed APPLY outcome**, not apply again. That guarantee was later violated by REV-DEV-01 and subsequently repaired with a permanent regression.
- `OperationRecoveryWorker` handles configurable batches; synchronous webhook processing remains and superseded workers are rejected safely.
- The original V15 checkpoint in [F4-02](phase-4/F4-02-worker-processing.md) recorded `OperationWorkerIT` 9 and `OrderPaymentFlowIT` 16 tests in CI #278. Those are **historical counts**, not current totals.

## Increment H77 — Flyway V16

- V16 added `commercial_generations`, `commercial_units` and `commercial_unit_generations`, stable commercial-unit/generation associations on `tickets` and a backfill for existing issued tickets.
- `TicketService` issues the initial generation per commercial unit, preserves unit identity across reissuance and invalidates active-generation tickets after confirmed refund.
- DB constraints reject cross-order links, duplicate unit/generation ticket issuance and multiple active generations per order.
- `CommercialIssuanceIT` historically contained 14 scenarios for multi-unit purchases, idempotence, concurrent issuance/reissuance, refund races, constraints and migration from V15.
- **CI #278**, SHA `715af51fad107ede9e152e0ae7b63f151a806133`: `CommercialIssuanceIT` 14/14, `OperationWorkerIT` 9/9, `OrderPaymentFlowIT` 16/16, 140/140 integration and 388/388 `ticketing-api` unit tests, all successful; JaCoCo/Spotless passed.
- V16 implementation did **not** itself close F4: H75/H83 locks still required a global audit.

## Increment H81 — Phase 4 Redis, ADR-020

- Published-event detail and unfiltered popular first page cache use 60-second metadata TTL. Available stock is read from PostgreSQL for **every** catalog request.
- Event/ticket-type mutations invalidate cache after commit; venue mutation triggers a modular application event and downstream `AFTER_COMMIT` invalidation rather than a direct venue→event dependency.
- `ReservationRedisProjectionService` stores `ticketing:reservation:{id}` with ACTIVE status/expiration/remaining TTL after commit and removes it upon cancellation, expiry, confirmation, event cancellation or order expiration.
- Reservation projection/cache errors are fail-open relative to business writes; PostgreSQL remains authoritative.
- Reservation/order limits are 20/60s and 10/60s per user; login 5/60s. Redis counter increment and expiration are atomic. Rate limiting fails open during Redis outages.
- **CI #286**, SHA `65c6abf13af7eb8c1195f41c0e25f9d9e5cd2277`: 390 `ticketing-api` units, 147 integration tests, `EventCatalogCacheIT` 5/5, `RedisRateLimiterIT` 3/3 and `ReservationRedisProjectionIT` 5/5, successful Maven/JaCoCo/Spotless.
- H81 was technically implemented; F4 still required independent final acceptance.

## Global lock audit H75/H76/H83 — F4-03

- Audited `service` and `repository` paths with explicit PostgreSQL locks and implicit locks introduced by FKs and JPA flush.
- Found an implicit **EN → U** inversion on ticket reissuance: cancelling older tickets before saving the new associations could update ticket rows before acquiring FK `KEY SHARE` on commercial units. The correction persists U/new EN links **before** cancelling older tickets.
- Found an implicit **R → T** inversion when creating an order: `lockActiveForOrder` locked R before `order_items` insert could acquire an implicit T lock via `ticket_type_id` FK. The correction explicitly acquires E → T → R before inserting O/P/I.
- `TicketServiceTest` and `ReservationServiceTest` pin the corrected ordering; `OrderCreationIntegrityIT` uses real PostgreSQL to verify waiting on T before R.
- **CI #292**, SHA `24be7cbe670e7076d8e6999a9d708ef4cad09fe1`: 390 API unit and 148 integration tests; `OrderCreationIntegrityIT` 5/5, `CommercialIssuanceIT` 14/14, `EventGuardConcurrencyIT` 9/9, `OperationWorkerIT` 9/9 and both reservation concurrency suites 20/20; Spotless/JaCoCo/Maven successful.
- Technical disposition was valid for the audited schema/paths; **reopen** when a relevant FK, explicit lock or JPA flush order changes.

## Integrated payment and recovery audit H78/H79/H80/H82/H84 — F4-04

- **CI #295** on `88a8f717d4c3079d124166dff487167e373fec6d`: 390 units, 152 integrations, `OrderPaymentFlowIT` 20/20, `OperationWorkerIT` 10/10, clean Spotless, successful build.
- **CI #296** on `522a2dcf706122760ffee4fd46e8027e41e2723d`: 390 units, 153 integrations, `OperationWorkerIT` 11/11. PostgreSQL asserted configured `lock_timeout`/`statement_timeout` inside all RX/CLAIM/APPLY/PROJECT transactions.
- Historical evidence includes failed and pending refunds without invented confirmation, out-of-order provider evidence, interrupted worker recovery, semantic idempotency, fencing and late capture.
- [F4-04](phase-4/F4-04-integrated-payment-audit.md) documents H84-01: SHA `23893305` conflated operation uniqueness with commercial effect and could record `NO_OP` for a legitimate `PENDING → REFUNDED` change. Commit `4acb8240` introduced `PaymentEffectOutcome` and four integration tests; CI #298 passed 390 units and 157 integrations.
- **CI #302** then covered `FAILED → REFUNDED` and ten `PENDING/REFUNDED` races (390 units, 168 integrations; `OrderPaymentFlowIT` 35/35).
- **CI #304** added fencing against a late projection after direct refund recovery (390 units, 168 integrations; `OperationWorkerIT` 11/11).
- **CI #306**, SHA `b8f1037b9a6f923b70ab92ef0071423c0479b1c0`, verified interrupted `PENDING → REFUNDED` between APPLY and PROJECT (390 units, 169 integrations; `OperationWorkerIT` 12/12).
- **CI #309** later verified a distinct newly signed refund event after successful capture (`OrderPaymentFlowIT` 36/36, 170 integrations). Proposed ADR-022 still requires an explicit business contract verdict. **REV-DEV-01** later uncovered same-old-job replay despite these tests, and main corrected it; never treat the old CI as covering the later defect.

## One order per reservation — Flyway V11 / ADR-015

- `OrderService.create` checks whether a reservation already has an order.
- V11 adds **non-partial unique** `ux_orders_reservation_id` on `orders(reservation_id)`, including CANCELLED orders.
- A cancelled order does not make its original reservation reusable; subsequent purchases need another reservation.
- Database integrity conflicts become HTTP `409 Conflict`. See [ADR-015](adr/ADR-015-one-order-per-reservation.md).
- **CI #116** on `aabc02301deda4b983c5f3e56af00ed4ecd717ec` validated `OrderCreationIntegrityIT` four scenarios. A same-key race's 409 was inferred from SQL uniqueness in the CI log; the old test allowed 200 or 409 and did not record each HTTP result directly.

## Original Phase 4 Redis acceptance matrix

| Criterion | Evidence or implementation | Historical status |
| --- | --- | --- |
| Cache-aside metadata with configurable TTL | `TicketTypeCatalogCacheService`, `TICKET_TYPES_CACHE_TTL` default 5m; [ADR-013](adr/ADR-013-redis-catalog-cache.md) | Verified |
| No cached availability | `availableQuantity` reads PostgreSQL for every catalog request | Verified |
| Post-commit invalidation | `RedisCacheManager.transactionAware()`; eviction deferred to commit | Verified |
| Cache failures fall back to PostgreSQL | `CacheErrorHandler` catches relevant `DataAccessException` / `SerializationException` for cache operations | Verified |
| Cache hit/expiry and reload | `TicketTypeCatalogCacheIT` with real Redis and serialization/TTL | Verified |
| Login rate limit | Fixed window; fail-open during Redis failure; unit and integration tests | Verified |
| Refresh sessions fail closed | `should_failClosedForAuthenticationSessionsDuringRedisOutage` returns 503 | Verified |
| Access JWT remains locally verifiable | `should_acceptValidAccessTokenDuringRedisOutage` returns 200 | Verified |
| Failed eviction is TTL-bounded | `should_serveStaleCatalogUntilTtlAfterFailedInvalidation` | Verified |
| Lettuce timeouts and Redis isolation | `should_configureShortCommandTimeoutOnLettuceClient`, `should_useDedicatedRedisContainerInSpringContext`, 500 ms | Integration verified |
| Operating guide | [ADR-014](adr/ADR-014-redis-resilience-and-auth-degradation.md), [LOCAL_RUN](LOCAL_RUN.md), [flow.http](flow.http) | Published; subsequent local evidence has its own date/SHA |

### Historical quantitative evidence from CI #116

The [CI #116 test/coverage artifacts](https://github.com/dab960405/tickeringsystem/actions/runs/43046441594) correspond to `aabc02301deda4b983c5f3e56af00ed4ecd717ec` and completed successfully.

| Test suite | Executed | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Surefire, all Maven modules | 315 | 0 | 0 | 0 |
| Failsafe, API integration | 61 | 0 | 0 | 0 |
| **Total** | **376** | **0** | **0** | **0** |

Surefire by module: API 309, common-events 5 and notification shell 1. Historical JaCoCo counters from the API package nodes in XML:

| Packages | Covered lines | Missed lines | Line coverage | Covered branches | Branch coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| `service` | 501 | 21 | **95.98%** | 126/151 | 83.44% |
| `domain` | 291 | 5 | **98.31%** | 84/101 | 83.17% |

Percentages were calculated as `covered / (covered + missed)`. **Do not apply these old percentages to the current main SHA without new JaCoCo evidence.**

Historical Redis paused latencies: **1,014 ms** for catalog and **1,023 ms** for public event GET. They are observations, **not** production SLOs or latency guarantees.

## Historic CI event register

| Run | SHA | Result | Historical interpretation |
| --- | --- | --- | --- |
| [#70](https://github.com/dab960405/tickeringsystem/actions/runs/42746676925) | `3054acf604e01fd8b4d18b1c909741e560b10274` | Failure | Spotless catalog cache formatting |
| [#71](https://github.com/dab960405/tickeringsystem/actions/runs/42747004968) | `93092b9a7696e0b1d0cb31868e3a567d0c121a6d` | Success | Format corrected |
| [#105](https://github.com/dab960405/tickeringsystem/actions/runs/42952420841) | `cab03e43900550359567517ddbd9aa269fc115dc` | Success | Runner pinned to Ubuntu 24.04 |
| [#106](https://github.com/dab960405/tickeringsystem/actions/runs/42953061576) | `a53514ec76c690dd8e34976425ff6458652485b1` | Failure | `RedisOutageIT` Spotless error; tests passed |
| [#107](https://github.com/dab960405/tickeringsystem/actions/runs/42954580181) | `24d6a514f1f8a4d467b1c457d12a478e24f75c36` | Success | Prior Redis F4 evidence, superseded in scope by later runs |
| [#108](https://github.com/dab960405/tickeringsystem/actions/runs/42955123807) | `7d65089181309fa1a8c2ca641e85f24d4f3a150b` | Success | ADR-014/decisions |
| [#109](https://github.com/dab960405/tickeringsystem/actions/runs/42955586010) | `ac6e7d061c2115ff6485937dcb371bfb25e8e55b` | Success | Local guide and HTTP example |
| [#116](https://github.com/dab960405/tickeringsystem/actions/runs/43046441594) | `aabc02301deda4b983c5f3e56af00ed4ecd717ec` | Success | Detailed historical API test/coverage evidence |
| [#117](https://github.com/dab960405/tickeringsystem/actions/runs/43048762317) | `c26a8cf5fa4b618ddd88063c05696bbe9a7ca43a` | Success | Subsequent documentation |
| [#118](https://github.com/dab960405/tickeringsystem/actions/runs/43264556420) | `244e998c6888f1f29e84c5dd2b6a27f8d8895e60` | Success | ADR-015 and correction of ADR-011 |
| [#154](https://github.com/dab960405/tickeringsystem/actions/runs/43543996946) | `06490392e86866094ee9dddbff450f4e76e646ad` | Success | Phase 2 authorization and Spotless evidence |
| #278 | `715af51fad107ede9e152e0ae7b63f151a806133` | Historical claim | V16, 388 API units and 140 integrations; use actual GitHub run link from dossier |
| #292 | `24be7cbe670e7076d8e6999a9d708ef4cad09fe1` | Historical claim | Lock audit; use dossier for exact run URL |
| [#315](https://github.com/dab960405/tickeringsystem/actions/runs/43654824223) | `9d11d83344b88007e2b5d89de698bf62899817c0` | Success | Event-cancellation rollback |
| [#317](https://github.com/dab960405/tickeringsystem/actions/runs/43655953752) | `0e6f5d21ec1665cef19f5145cfcdf97a97097da1` | Success | F3/F4 audit dossiers and 390/171 test baseline |

**Note:** The original historical register records CI #278/#292 by number and source SHA without verified GitHub run URLs. Do not invent those URLs; consult the historical dossier or GitHub Actions before citing them as direct links.

## CI stabilization #269–#281

- **#269** failed because `UserRepositoryIT`'s `@DataJpaTest` slice did not import `JpaAuditingConfig`. Commit `7629e51de62509a9fb1bab3f64070967f479dbfd` added auditing config and explicit `createdAt`/`updatedAt` assertions. In **#270**, that test passed and 387 units/140 integrations succeeded; the build was red only from Spotless formatting in other increments.
- **#280**, SHA `82032baf563c84b0d85315bfd6fa955ee4412b84`, succeeded with 388 API units/140 integrations, Spotless and JaCoCo.
- #280 nevertheless showed a test-context lifecycle warning: a cached `RedisOutageIT` Spring context kept an expiration scheduler active after dedicated PostgreSQL stopped, causing asynchronous access to a closed Hikari pool even though the Maven build succeeded.
- Commit `cc72f1bc3de90ed58f66cfb6da4c75b522208130` isolated expiration schedulers in that outage context. **#281** passed with no late access to `HikariPool-5`, `too many clients already` or unexpected scheduled-task error.
- `spring.test.context.cache.maxSize=4` was preserved. The CI evidence did not reproduce the connection exhaustion seen at #268.

## Historical public catalog cache increment — `889a5c5`

Commit `889a5c5521c6f47f0604002c2d0425e31f534031` was integrated into the old `dev` branch by fast-forward from `5e9616458230c0ec5da49194854ff26c7afb7cf0`. It added 60-second published-event/first-unfiltered-page metadata cache, live PostgreSQL availability, after-commit invalidation, `ResilientRedisCacheWriter` and `EventCatalogCacheIT` scenarios for TTL/hits/invalidation/filtered queries. The original commit's CI #43532844492 was reported successful; a later merged-tree CI was still required at the time. The subsequent H81 solution added reservation projections, reservation/order rate limits and venue invalidation, culminating in CI #286 above.

## Phase 2 authorization audit and formal closure (2026-11-13)

**Historical formal status: CLOSED with explicit owner approval.** The owner's authorization followed comparison of specification criteria with [the authorization matrix](phase-2/authorization-matrix.md), [ADR-021](adr/ADR-021-phase-2-authorization-resource-concealment.md), JWT-based ownership tests and CI evidence.

- The matrix classifies HTTP endpoints by method, functional role, ownership and expected response.
- ADR-021 maps foreign-owned and nonexistent private resources to concealed 404 while retaining 403 for missing functional roles.
- `EventAuthorizationService` throws `ResourceNotFoundException` for an organizer accessing another organizer's event. ADMIN retains global access; nonexistent events still become 404 from the business service.
- `AuthenticationFlowIT` uses real JWTs for two separate organizers and for two buyers to prove foreign-event and foreign-reservation/order denial while legitimate owners retain access.

Historical individual commits:

| SHA | Original contribution |
| --- | --- |
| `4ba7117c594e315e775247abe6ba6b0b0a6ddad3` | Organizer JWT isolation integration test |
| `f72fbe1d86646d4c827442c83b1693cdce4375cc` | Ownership concealment unit test |
| `6890df100902b432fab06c5c251573a276782bbb` | Foreign organizer event concealment implementation |
| `40007149a5a53415f8e7aedd646f6278c96057b6` | Initial HTTP authorization matrix |
| `b281bebf8df5c79e5b04a5b035133b445fae0385` | ADR-021 |
| `de05bc43ca7b512ebf677f442794a0d8ad5916f5` | Decisions register |
| `3631e0407d1c92d43f5b23314b943a7a127fa9ec` | Two-buyer real-JWT order/reservation isolation |
| `57246b3722f323c37a4f8f9e8e203bb5c8223877` | Updated matrix |
| `87d92f5a0f9c9c034c1b7aa910eb86d5289c81c0` | ADR-021 evidence |
| `75f69668859be13c3a5f8a76b5ddb98e892a4377` | README/progress correction |

The original acceptance wording in specification `22 requires a role-test matrix and proof one buyer cannot act on another buyer's resources. It does **not** demand a single exhaustive method × role × state Cartesian test suite. That broader suite remains an authorized **non-blocking improvement**, not an invented closing criterion.

### CI #154 and Spotless stabilization, historical

[#154](https://github.com/dab960405/tickeringsystem/actions/runs/43543996946) completed `success` on `06490392e86866094ee9dddbff450f4e76e646ad` (old `dev`) after three separate format commits:
- `612ed9e2cba51ff2a392e96f01958256270be16a` formatted `AuthenticationFlowIT.java`.
- `460506a560470b4e96aba896046798db9f84f593` formatted `EventAuthorizationServiceTest.java`.
- `06490392e86866094ee9dddbff450f4e76e646ad` formatted `EventAuthorizationService.java`.

CI #154 ran **479 tests** (373 `ticketing-api`, 5 `common-events`, 1 `notification-service` Surefire = 379; API Failsafe = 100), zero failures/errors/skips; JaCoCo/Spotless and report uploads succeeded. Historical [run #43545520476](https://github.com/dab960405/tickeringsystem/actions/runs/43545520476) also validated HEAD `7146517ef80f3fe350a57d00e272f66b59ffc923` with successful Maven/coverage/Spotless and 100 API integration tests.

A later document-only HEAD `c25e0d323edf32e86b3590df0131d0fe87181b29` differed from the code SHA only in this progress file; its own CI was required before calling that newer HEAD verified. The Phase 2 closure approval remained an independent owner decision, not a consequence of CI.

## 2026-11-14 closure dossier preparation

The owner authorized **preparation and technical validation** for F3/F4, not unilateral architectural acceptance. [F3-01](phase-3/F3-01-closure-review.md), [ADR-024](adr/ADR-024-event-cancellation-transactional-exception.md), the ERD up to V16 and [F4-05](phase-4/F4-05-closure-review.md) were assembled.

The rollback regression was added in `4d381899`, SQL fixture repaired in `c18d9f2` and stock baseline assertion corrected in `9d11d833`. CI #313 failed on fixture SQL; #314 failed on an incorrect initial inventory expectation. [CI #315](https://github.com/dab960405/tickeringsystem/actions/runs/43654824223) succeeded with 390 units, 171 integrations and `EventGuardConcurrencyIT` 10/10. [CI #317](https://github.com/dab960405/tickeringsystem/actions/runs/43655953752) succeeded with 390 units, 171 integrations, `OrderPaymentFlowIT` 36/36, `OperationWorkerIT` 12/12, JaCoCo and Spotless.

The [double-check policy](engineering-decision-verification.md) was applied to ADR-024 (Spring Framework, PostgreSQL and Spring Modulith) and ADR-022 (PostgreSQL, Java HMAC and IETF RFC 9110), and cross-referenced in CI #318. **None of these developer recommendations constitutes ADR acceptance or a phase closure.**

## Technical debt and deferred scope

1. **ADR-022 / H84-01:** Immutable provider reference and distinct HMAC-signed event for early refund reevaluation; no automatic financial reconciliation. Pending Tech Lead verdict in issue #6. Later same-job recovery regression was implemented; verify final SHA.
2. **ADR-024 / AUD-05:** EventCancellationService's narrow transactional exception and additional cross-package accesses need explicit dispositions. Pending Tech Lead verdict in issue #5.
3. **AUD-03, AUD-10, AUD-12 and BACK-01:** HTTP 201 Location semantics, HTTP correlation/MDC, additional operation-cycle requirements and case-insensitive concurrent ticket-name uniqueness. See [pending decisions](audit/PENDING-DECISIONS.md).
4. **Known hardening:** Refresh tokens are not rotated on every refresh and webhooks have no timestamp-based replay expiry. These are documented risks of the SIMULATED portfolio, not claims of production payment security.
5. **Phase 5 remains blocked:** Functional outbox publishing, Kafka, notification consumers and MongoDB persistence require later explicit authorization.

## Local evidence, limitations and next actions

- PostgreSQL/Redis integration tests use Docker/Testcontainers in GitHub Actions. The actual 2026-11-15 local [34-check smoke walkthrough](LOCAL_RUN.md) exists and must be distinguished from older claims that no local testing had occurred.
- [flow.http](flow.http) is a manual sample, **not** run automatically in CI. The HMAC signature must be generated over the exact JSON bytes.
- [Historical adversarial report](ADVERSARIAL_TESTING.md) contains reproducible red observations from an earlier SHA. Later production regressions were committed; the historical report was intentionally not overwritten.
- Angular and Python are part of the **current** CI, in addition to Java. A passing workflow still does not establish a full production penetration test, accessibility audit or mathematical proof of concurrency.
- Before closing F3/F4, review exact main HEAD and complete green CI, the current acceptance matrices, all relevant regression test XML, and the Tech Lead's **explicit verdict** on ADR-024 and ADR-022 in that order. The owner must then authorize each phase's formal closure separately.
- **Do not implement Phase 5.** Its design may be reviewed and a readiness document prepared, but Kafka/outbox/Mongo functional implementation stays blocked.

## Reference commands (safe local checkout)

```bash
git status --short
git fetch origin main
git switch main
git pull --ff-only origin main
git rev-parse HEAD
./mvnw -B clean verify
(cd frontend && npm ci && npm test && npm run build && npm run format:check)
python3 -m unittest discover -s frontend -p 'test_*.py' -v
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

Review uncommitted local changes before switching/pulling. Do not delete Docker volumes or issue destructive database commands. **Only main is maintained; historical dev references are not workflow instructions.**
