# Phase 3 / Phase 4 technical verification — current main follow-up

**Assessment:** 2026-11-22 UTC (2026-11-21 America/Bogota).  
**Source baseline:** `main@236b31f46534f65c1249ff1f4cbd25586dc4938b`.  
**Latest verified source/documentation baseline before this report:** `main@9e4d918f634fbee04117e55ac6341bc25bf6d4d0`.  
**GitHub Actions:** [CI #339, completed/success](https://github.com/dab960405/tickeringsystem/actions/runs/44985205847), with Java, Angular and Python jobs successful on that exact SHA.  
**Governance:** Developer evidence only. No independent Tech Lead acceptance, formal Phase 3/4 closure or Phase 5 authorization is implied. Only `main` is permanent. No changes to `sources/` or historical evidence are made here.

## A–E. Findings, fixes and verification

All paths below are relative to the repository root. For finding severities, retain the original reviewer proposal where a traceable source exists; a missing severity is **not inferred**. This report does not change any historical H75–H84 severity.

| ID / severity from available evidence | A. Component and B. root cause / prior vs expected behavior | C. Current solution and relevant files | D. Verification provenance | E. State |
| --- | --- | --- | --- | --- |
| REV-DEV-01 / **HIGH proposed** | Payment operations: a previously applied job was re-evaluated during PROJECT recovery, allowing old refund evidence to change a later `SUCCEEDED` payment to `REFUNDED`. Expected: no business replay for the same job; a different valid signed event can still apply. | `PaymentOperationService.java` persists/reuses `APPLIED` or `NO_OP` disposition for the same job, then projects without repeating commercial effects; no change to external event identity. `OperationWorkerIT.java` adds the durable regression. | Historical synthetic RED: [adversarial report](../ADVERSARIAL_TESTING.md). GREEN: [CI #324](https://github.com/dab960405/tickeringsystem/actions/runs/44981366848) and #339 (`OperationWorkerIT` 13/13). | **Corrected and CI-verified** for covered scenarios; formal acceptance pending. |
| REV-DEV-02 / **MEDIUM proposed** | Order expiration: a directly cancelled reservation excluded its associated `PENDING_PAYMENT` order from expiration selection. Expected: terminalize overdue order after grace without releasing inventory twice. | `OrderRepository.java` candidate query includes `CANCELLED`; `OrderExpirationProcessor.java` recognizes already-released inventory; `OrderPaymentFlowIT.java` tests repeat sweeps and late capture. | Historical synthetic RED: [adversarial report](../ADVERSARIAL_TESTING.md). GREEN: CI #324 and #339 (`OrderPaymentFlowIT` 37/37). | **Corrected and CI-verified** for covered scenarios. |
| AUD-02 residual / **MEDIUM** | Hibernate/JPA emitted rejected SQL values before the application exception handler could sanitize the 409 response log. Expected: no sensitive rejected field values in logs. | `ticketing-api/src/main/resources/application.yml` disables `org.hibernate.engine.jdbc.spi.SqlExceptionHelper` logging; `DataIntegrityLoggingIT.java` tests a real JPA uniqueness failure against captured root logs. | [CI #325](https://github.com/dab960405/tickeringsystem/actions/runs/44981594500), #339. Configuration limits ORM diagnostic detail; investigate other logger categories separately when changed. | **Corrected and CI-verified** within tested scope. |
| ADV-03 / **LOW proposed** | Jackson coerced `quantity:1.5` into an integer before bean validation. Expected: HTTP 400 on fractional integral input. | Strict Jackson coercion configuration and `ReservationControllerTest.java` regression added in source baseline after the audit. | CI #325 and #339. | **Corrected and CI-verified**. |
| BACK-02 / **severity not established in accessible review** | Unicode registration passwords can exceed BCrypt's 72 **UTF-8-byte** boundary despite passing character-length checks, potentially throwing HTTP 500. Expected: validation error 400, without changing existing passwords or hashes. | `RegisterRequest.java` validates UTF-8 byte count; `AuthControllerTest.java` exercises the boundary. | CI #325 and #339. | **Corrected and CI-verified** for registration. |
| ADV-04 / **MEDIUM proposed** | SPA's root `<base href="/">` conflicted with `base-uri 'none'`; nested-route reload resolved asset paths incorrectly. Expected: root assets and route load with compatible CSP. | `frontend/dev_server.py` allows same-origin `base-uri 'self'`; `frontend/test_dev_server.py` asserts headers and deep-link fallback; Angular routes updated with legacy redirects. | CI #326 and #339; Python local-gateway suite 11/11. **No post-fix real-browser navigation/reload was executed in this verification.** | **Code/test correction verified; browser acceptance pending**. |
| UI-NEW-01 / **severity not established in accessible review** | An older in-flight reservation could be attached to checkout after the buyer selected another event. Expected: event/reservation identity cannot be mixed across asynchronous responses. | `frontend/src/app/purchase/purchase.ts` blocks selecting a different event while busy or holding a pending purchase; `purchase.spec.ts` regression. | CI #326 and #339; Angular 15/15. Session/logout and late-response transitions still merit additional real-browser cases. | **Corrected and CI-verified** for the tested event-switch race; broader browser verification pending. |
| TOOL-NEW-01 / **severity not established in accessible review** | The smoke client followed redirects, risking bearer credential forwarding across origins. Expected: no redirect following or Authorization forwarding. | `scripts/local-smoke.py` installs a non-following redirect handler; `scripts/test_local_smoke.py` tests synthetic redirects. | CI #326 and #339; Python script suite 3/3. | **Corrected and CI-verified**. |
| BACK-04 / **severity not established in accessible review** | Candidate discovery used JVM time while locked expiration revalidation used PostgreSQL time. A skewed JVM could postpone picking expired rows. Expected: database-authoritative selection and under-lock revalidation. | `ReservationRepository.java` queries `clock_timestamp()` for candidates; `ReservationExpirationService.java` keeps locked DB-time revalidation. No historical migration changed. | RED [CI #331](https://github.com/dab960405/tickeringsystem/actions/runs/44983063132); formatting follow-up and GREEN [CI #333](https://github.com/dab960405/tickeringsystem/actions/runs/44983475556); #339. | **Corrected and CI-verified** within simulated clock-skew scenarios. |
| BACK-01 / **severity not established in accessible review** | `TicketTypeService.java` checks `existsByEventIdAndNameIgnoreCase` while `V4__create_ticket_types.sql` has case-sensitive `UNIQUE(event_id,name)`. `lockShared(eventId)` allows concurrent writers; both can pass the precheck for `VIP`/`vip`. Expected: at most one case-insensitive name per event. | **No production change.** Do not rewrite V4 or change the approved event-lock protocol without a decision. Implement a synthetic concurrent create/rename PostgreSQL Testcontainers regression after the lock/schema choice is authorized. | **Source-level race is established; actual concurrent DB reproduction has not been run in this verification.** Existing `TicketTypeRepositoryIT` checks only the sequential precheck. | **Pending reproduction and architectural/contract decision**. |
| AUD-03 / **MEDIUM** | HTTP 201 `Location` headers sometimes lack corresponding GET routes. RFC 9110 does not mandate an HTTP GET endpoint for every created resource URI. | No public API change. Require an explicit per-resource contract decision before adding GET routes or changing `Location`. | [Pending decisions](PENDING-DECISIONS.md); no new protocol acceptance test. | **Pending API-owner/Tech Lead decision**. |
| AUD-05 / **original severity unresolved** | Direct cross-module persistence exists in cancellation and other coordinators beyond proposed ADR-024. | Preserve atomic transaction and lock order. Audit `EventCancellationService`, `OrderExpirationProcessor`, `ProviderEventInboxService`, `PaymentOperationService`, `OperationJobService` and read-only discovery repositories. A narrow cancellation exception does not approve unrelated access. | [Access inventory](PENDING-DECISIONS.md), [ADR-024](../adr/ADR-024-event-cancellation-transactional-exception.md), existing rollback/concurrency CI. | **Pending architecture verdict**. |
| AUD-10 / **LOW–MEDIUM originally proposed** | CORS exposes `X-Correlation-Id` but does not establish an HTTP request filter/MDC lifecycle. | No unapproved public-header contract change. Recommend validated or generated correlation identifier, response echo and guaranteed MDC cleanup only after API-owner decision. Kafka propagation remains Phase 5. | [Pending decisions](PENDING-DECISIONS.md); HTTP request-isolation tests still required. | **Pending contract decision**. |
| AUD-12 / **severity to verify** | Code registers initial operation cycle only; historical SQL fixtures created a second cycle without establishing the business activation rule. | No invented additional-cycle API or worker. Recover original H78 requirement, decide whether initial-cycle-only behavior is accepted. | [ADR-018](../adr/ADR-018-operation-cycles-and-worker-recovery.md); no authorized additional-cycle acceptance scenario. | **Pending requirement/Tech Lead decision**. |
| ADR-024 / **PROPOSED** | Event cancellation coordinates E→T→R→O across modules in a single physical transaction. | Recommend **only** `EventCancellationService.cancel(UUID)` as a bounded architectural exception, subject to the wider AUD-05 inventory and Tech Lead acceptance. | [Phase 3 dossier](../phase-3/F3-01-closure-review.md), `EventGuardConcurrencyIT` with PostgreSQL fault rollback; [issue #5](https://github.com/dab960405/tickeringsystem/issues/5) remains open. | **Pending independent verdict**. |
| ADR-022 / H84-01 / **historical severity not reconstructed** | SIMULATED refund semantics distinguish an old job's PROJECT replay from a new, independently signed provider event. The latter may legitimately re-evaluate financial state; the former must never do so. | Proposed minimum contract retains immutable operation reference/digest and HTTP 409 on incompatible evidence; early refund is `NO_OP` without invented confirmed money movement; new signed provider event is needed for later reconciliation. | [Phase 4 dossier](../phase-4/F4-05-closure-review.md), `OperationWorkerIT` and `OrderPaymentFlowIT`, [issue #6](https://github.com/dab960405/tickeringsystem/issues/6) remains open. | **Technical regressions pass; contract and phase verdict pending**. |

## Source comparison and diff

- Original audited `main`: `236b31f46534f65c1249ff1f4cbd25586dc4938b`.
- Reviewed `main`: `9e4d918f634fbee04117e55ac6341bc25bf6d4d0`, **17 descendants** of the audit baseline; 16 descendants through `33aae5b` followed by an English translation-only commit covering F4-04, F5-01 and F5-02.
- Relevant source changes include `PaymentOperationService`, expiration selection, Hibernate logging configuration, DTO validation, Angular purchase state, CSP, smoke redirect handling and PostgreSQL-time expiration discovery. Existing migration files are unchanged.
- The active documentation language policy is [ADR-025](../adr/ADR-025-english-only-project-policy.md). The three identified untranslated Phase 4/5 dossiers were translated on the subsequently verified baseline; originals remain retrievable by historical Git SHA. This is **not** proof that every tracked first-party comment and string in the repository was scanned for language compliance.

## CI evidence, environmental boundaries and reproducibility

[CI #339](https://github.com/dab960405/tickeringsystem/actions/runs/44985205847) (same `9e4d918f...` SHA):
- `./mvnw -B clean verify`: `common-events` 5/5 units, `ticketing-api` 404/404 units and 182/182 integrations, `notification-service` 1/1 unit; **410 unit + 182 integration = 592 tests**, zero failures/errors/skips. JaCoCo, Spotless and Maven `BUILD SUCCESS`.
- Integration examples: `OrderPaymentFlowIT` 37/37, `OperationWorkerIT` 13/13, both 20-repetition reservation race suites. PostgreSQL/Redis Testcontainers were exercised by relevant integration tests.
- Angular job: locked `npm ci`, `npm test` **15/15**, production `npm run build` and `npm run format:check` passed.
- Python job: `python3 -m unittest discover -s frontend -p 'test_*.py' -v` **11/11** and `python3 -m unittest discover -s scripts -p 'test_*.py' -v` **3/3**.
- **Provenance:** These are GitHub Actions observations; this report did not execute a second local run. No Docker engine, running PostgreSQL/Redis cluster, browser E2E session or external payment provider was available in the reviewing environment. The local environment also could not DNS-resolve github.com for a Git clone. No destructive operation was performed on a persistent user database.
- A successful npm installation's vulnerability summary **does not substitute for a separately completed dependency security audit**. Do not report absent vulnerabilities from a failed or unperformed audit. No live browser refresh is claimed.

Reproducibility commands in a clone with Java 21, Docker, Node/npm and Python:

```bash
git rev-parse HEAD
./mvnw -B clean verify
cd frontend && npm ci && npm test && npm run build && npm run format:check
cd ..
python3 -m unittest discover -s frontend -p 'test_*.py' -v
python3 -m unittest discover -s scripts -p 'test_*.py' -v
```

For BACK-01 use only disposable Testcontainers PostgreSQL, never a user database. Reproduce competing `VIP`/`vip` creates and rename/create races with distinct transactions, a barrier after the case-insensitive precheck and assertions on committed rows. Record both transactions' outcomes, SQL state, lock waits and exact test SHA. **No observed concurrent DB outcome is claimed here.**

## Decisions needing explicit authorization

1. **BACK-01:** Prefer serializing ticket-type name create/update with an **exclusive E lock before any T lock**, if the Tech Lead accepts the concurrency impact and ADR-016 interpretation. Alternative: authorize a *new* additive `UNIQUE(event_id, lower(name))` index after a preflight duplicate audit, with database migration approval and conflict-to-409 regression. Never alter historical V4. Compare transactional throughput and deadlock behavior before selection. This recommendation is not an implementation authorization.
2. **AUD-03:** Confirm current `Location` semantics per resource or authorize backward-compatible GET contract changes.
3. **AUD-05 / ADR-024:** Approve narrowly specified coordination exceptions or authorize owner-module application interfaces without splitting transactions or inverting locks.
4. **AUD-10:** Approve a minimal HTTP correlation/MDC contract and tests before modifying public headers; Kafka metadata is later-phase scope.
5. **AUD-12:** Supply original H78 additional-cycle business criterion or formally accept initial-cycle-only scope.
6. **ADR-022/H84-01:** Accept/reject the minimal SIMULATED refund-evidence contract without guessing successful financial refunds.

## Closure matrix and required sequence

| Gate | Technical evidence | Formal status |
| --- | --- | --- |
| F3 commercial/inventory correctness | Testcontainers + core order/reservation/recovery regressions passed on CI #339 | **Technically tested; formal conformance verdict pending** |
| F3 architecture exception ADR-024 / AUD-05 | Fault rollback and ordered-lock evidence present | **Tech Lead decision pending** |
| **F3 formal closure** | Must follow independent conformance verdict | **OPEN — owner has not approved closure** |
| F4 Redis/cache/rate/outage and H75–H84 | Existing integration suite and historic dossiers, same final code SHA in CI #339 | **Technically tested; ADR-022/H84-01 decision and verdict pending** |
| **F4 formal closure** | Requires F3 formal closure first | **OPEN — owner has not approved closure** |
| F5 start permission | Requires both formal closures, separate design approval and authorization | **BLOCKED; no Phase 5 implementation** |

**Prepared, not sent — Phase 3 review request:**

> Please review `main@9e4d918f634fbee04117e55ac6341bc25bf6d4d0` and CI #339 against F3-01. Decide ADR-024 and AUD-05 boundaries, assign a disposition to unresolved contract items and issue the independent Phase 3 technical verdict. The project owner will separately authorize formal closure; this request grants no automatic acceptance.

**Prepared, not sent — Phase 4 review request:**

> After formal Phase 3 closure, review the same verified `main` baseline against F4-05 and H75–H84. Accept or reject ADR-022/H84-01 SIMULATED evidence rules, assess Redis acceptance and residual findings, and issue an independent Phase 4 technical verdict. The project owner will separately authorize formal closure.

**Verdict:** Current code remediation has extensive verified coverage, but **Phase 3 and Phase 4 are not formally closed**; BACK-01 reproduction/decision, AUD-03/05/10/12 and ADR-022/024 acceptance remain open. **Phase 5 is not ready for implementation or start authorization.**
