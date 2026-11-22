# F3-02 — Owner decision register and Phase 3 independent-conformity handoff

**Date:** 2026-11-22. **Repository:** `dab960405/tickeringsystem`, permanent branch `main`.  
**Authorization:** The project owner explicitly authorized code/architecture/contract decisions needed for Phase 3 and approved those decisions in this conversation. This document records that authorization for traceability; it **does not claim a personally signed GitHub approval or an independent Tech Lead verdict**.  
**Formal Phase 3 status:** **OPEN — independent technical verdict required**. Owner disposition of the identified risks is recorded below. No Kafka/Mongo/notification Phase 5 functionality is authorized.

## A. Identification

- **Findings:** AUD-03, AUD-05/ADR-024, AUD-10, AUD-12/H78 (Phase 3 scope determination), prior BACK-01/04 and REV-DEV-01/02 regression evidence.
- **Affected components:** HTTP response contracts, transaction coordinators, logging/servlet filter, operation-cycle requirements and Phase 3 closure governance.
- **Historical severity:** Unchanged; this report does not infer a missing original H75–H84 classification.

## B. Root cause and expected behavior

- **AUD-05:** The existing monolith uses direct cross-module persistence to ensure cancellations and expirations have a *single atomic transaction* with an explicit row lock order. Hiding that coupling is a documentation/architecture breach; splitting it without proof would risk double release and inconsistent order state.
- **AUD-10:** CORS exposed `X-Correlation-Id`, but the servlet layer did not guarantee an inbound/outbound correlation contract or request-scoped MDC cleanup. Missing, untrusted or CRLF-bearing IDs must never be injected into logs unchanged.
- **AUD-03:** `201` responses expose `Location` identifiers for resources whose GET handlers may not exist. An identity URI is not an unconditional promise to expose private GET, but clients must not assume dereferenceability.
- **AUD-12:** Only initial operation cycles are created by production code. Further cycles and H78 are a Phase 4 contract decision; do not fabricate implementation evidence for a future cycle API.

## C. Owner-authorized design and implementation

| Decision | Scope | Owner disposition |
| --- | --- | --- |
| **ADR-024** | `EventCancellationService.cancel(UUID)` single physical PostgreSQL TX E → T → R → O; full rollback, no invented payment evidence, Redis post-commit | **APPROVED, bounded**; independent Tech Lead review pending |
| **ADR-026 / AUD-05** | `OrderExpirationProcessor.expire` cross-module coordination, and `OrderRepository`/`ReservationRepository` read-only unlocked candidate joins, always followed by locked revalidation | **APPROVED, bounded**; all other foreign persistence must be reviewed separately |
| **AUD-03** | Preserve existing `201 Location` and body, avoid undocumented GET of private resources | **ACCEPTED AS NONBLOCKING** for Phase 3; explicit client limitation |
| **AUD-10** | New `CorrelationIdFilter`, strict client-header allowlist, generated UUID fallback, MDC `try/finally` restoration, response header, allowed/exposed CORS header, log pattern and tests | **IMPLEMENTATION AUTHORISED**, subject to exact-SHA CI verification |
| **AUD-12/H78** | No Phase 3 cycle API change; return to Phase 4 accepted functional contract before Phase 4 closure | **NOT A PHASE 3 BLOCKER**; unresolved Phase 4 scope must not be waived |

**Files created/changed:** `ticketing-api/src/main/java/com/portfolio/ticketing/common/config/CorrelationIdFilter.java`, `auth/config/SecurityConfig.java`, `ticketing-api/src/main/resources/application.yml`, `ticketing-api/src/test/java/com/portfolio/ticketing/common/config/CorrelationIdFilterTest.java`, `common/exception/GlobalExceptionHandlerTest.java`; ADR-024, ADR-026, decision register and F3-01 dossier. No historical Flyway migration or public purchase API changed.

## D. Verifiable tests and evidence

- Baseline [CI #349](https://github.com/dab960405/tickeringsystem/actions/runs/44987202435) SUCCESS, exactly `main@9d843c6d0bd78f8bf1fd5d56231c8ed96ba66917`: 404 `ticketing-api` unit, 6 other unit, 195 PostgreSQL/Redis Testcontainers IT, Angular 19, real Chrome routes, Python frontend 11 + automation 3; Spotless/JaCoCo enforced.
- **RED reproduced:** [CI #350](https://github.com/dab960405/tickeringsystem/actions/runs/44988342999) compiler fails on missing `CorrelationIdFilter` from four new unit tests.
- **Behavioral GREEN:** [CI #351](https://github.com/dab960405/tickeringsystem/actions/runs/44988418373): 410 `ticketing-api` unit tests (includes six new filter/HTTP cases), 195 integration tests and frontend/Python successful; build red solely because Spotless required one line wrap in `SecurityConfig`.
- **Format correction:** `ff18f1ef59f9d7b92aa607f7ddb04a199bbad1db` applies exactly the Java formatter's diff. [CI #352](https://github.com/dab960405/tickeringsystem/actions/runs/44988647465) **COMPLETED SUCCESS** on exactly `main@ff18f1ef59f9d7b92aa607f7ddb04a199bbad1db`. Java: 410 API unit + 6 other module unit tests, 195 PostgreSQL/Redis Testcontainers integrations, Spotless, JaCoCo, Maven BUILD SUCCESS; Angular: 19 tests, production bundle, real Chrome route smoke and Prettier; Python: 14 tests. Any subsequent documentation-only commit still requires a matching exact-SHA CI run.

Verification command on a Docker-capable development machine:

```bash
./mvnw -B clean verify
```

The test suite was executed on GitHub Actions for CI #352; no separate local Docker/Testcontainers execution is claimed in the developer's environment. Existing concurrency tests establish sampled scenarios, not an exhaustive proof of all possible schedules.

## E. Disposition and release gate

1. **Owner architecture and Phase 3 risk dispositions recorded:** approved, as detailed in ADR-024/ADR-026 and the [AUD register](../audit/PENDING-DECISIONS.md). This is an owner approval, not independent technical audit.
2. **Blocking independent verdict:** [Issue #5](https://github.com/dab960405/tickeringsystem/issues/5) must receive a substantive Tech Lead acceptance (or concrete required corrections) on the exact final SHA and full green CI, including architecture scope and HTTP regression evidence.
3. **Formal Phase 3 close:** record the Tech Lead verdict and resulting owner closure decision in F3-01. Until then keep Phase 3 **OPEN**. Do not imply Phase 4 closure or Phase 5 start from Phase 3 code being green.

## Primary sources read after repository inspection

- [Spring Framework 6.2 — REQUIRED propagation](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html) and [rollback semantics](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/rolling-back.html).
- [PostgreSQL 17 — locking/deadlocks](https://www.postgresql.org/docs/17/explicit-locking.html).
- [Spring Modulith 1.4 — module verification](https://docs.spring.io/spring-modulith/reference/1.4/verification.html).
- [RFC 9110 — `Location`](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.2).
- [Spring Framework 6.2 — `OncePerRequestFilter`](https://docs.spring.io/spring-framework/docs/6.2.12/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html); [Logback MDC](https://logback.qos.ch/manual/mdc.html); [OWASP logging and log injection](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html).

## Superseding owner delegation and formal closure procedure

Later on 2026-11-22, the project owner explicitly delegated all powers needed to close Phase 3. This is exercised through [ADR-027](../adr/ADR-027-phase3-delegated-owner-closure.md) and recorded in [F3-03](F3-03-delegated-owner-closure-2026-11-22.md). It is an **explicit owner-delegated governance exception**, not a Tech Lead verdict. Previous statements that F3 is OPEN represent the pre-delegation state. F4 and F5 gates are unchanged.
