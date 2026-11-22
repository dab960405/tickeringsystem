# Phase 5 entry readiness — Ticketing

**Assessment date:** 2026-11-21 (America/Bogota).  
**Status:** **BLOCKED — NOT AUTHORIZED TO IMPLEMENT**.  
**Permanent working branch:** `main` only. The former `dev` branch is historical.  
**Last technically verified code+documentation baseline before this dossier:** `cf75faa0bddc0d7302bb59e8386501ceeea62bb3`, [CI #335](https://github.com/dab960405/tickeringsystem/actions/runs/44984185620) `SUCCESS`. Later documentation commits need their own matching green CI; always recapture HEAD at final verdict.

This document is **readiness assessment only**, not an accepted F5 architecture, deployment request, release approval, or authority to modify Kafka/Mongo/outbox behavior.

## Phase 0–4 implementation and test status

| Area | Evidence and remaining qualification |
| --- | --- |
| Core Java service | Spring Boot/Java 21 modular monolith and PostgreSQL 17/Flyway V1–V16, with 22 tables and domain transactions |
| Reservation/inventory | Conditional PostgreSQL stock update, E → T → R locks, exact-once stock release, 20×100-buyer race suites and database-authoritative TTL decisions |
| Orders and simulated payments | One order per reservation, API idempotency, signed webhook evidence, durable RX/CLAIM/APPLY/PROJECT, fencing, per-operation identity and ticket issuance |
| Financial recovery | REV-DEV-01 same-job business replay and REV-DEV-02 cancelled-hold pending order received source-level regressions and fixes in earlier main CI (#324 and later); verify all relevant final test XML |
| Secure input/logging | Integer JSON coercion, bcrypt Unicode length, Hibernate/JPA SQL detail leakage and token redirects were addressed with new regressions in earlier main commits; verify at final SHA |
| Angular frontend | Standalone Angular 22 buyer UI, safe same-origin CSP for deep routes, guarded in-flight purchase context and English canonical routes; formerly Spanish bookmark paths are compatibility redirects only |
| Redis | Catalog metadata cache, live PG inventory, TTL, after-commit invalidation, rate limits, reservation projection and differentiated Redis-outage behavior |
| CI quality | Java Maven verify/JaCoCo/Spotless, Angular Node/npm test/build/format and Python unittest jobs run on the same commit; prior CI #335 passed |
| Original Phase 2 | Historically formally accepted; current Phase 3 and Phase 4 have **not** been formally accepted |

## Blocking gates before **requesting** Phase 5 implementation approval

1. **Finish and verify English-only migration** for all current first-party project-controlled docs, scripts, comments, UI and messages under [ADR-025](../adr/ADR-025-english-only-project-policy.md). Historical Git metadata, immutable externally controlled identifiers and necessary compatibility route aliases remain exceptions; never falsify original evidence.
2. **BACK-01 technical verification:** PostgreSQL Testcontainers reproduced concurrent `VIP`/`vip` duplicates in [CI #342](https://github.com/dab960405/tickeringsystem/actions/runs/44986036343). Existing E exclusive locks now serialize ticket-type create/update; [CI #344](https://github.com/dab960405/tickeringsystem/actions/runs/44986504339) passed 9/9 new races, 191 integrations, Spotless and JaCoCo. The subsequent source adds create/rename coverage; confirm its final CI. A new normalized database index remains an unapproved alternative and historical V4 was not modified.
3. **Resolve the policy decisions** in [PENDING-DECISIONS.md](../audit/PENDING-DECISIONS.md): AUD-03 Location contracts, AUD-05 cross-module persistence boundaries, AUD-10 HTTP correlation, AUD-12 cycles beyond the initial one and BACK-01 if reproduction confirms it. Do not silently broaden ADR-024.
4. **Obtain explicit independent Tech Lead verdict for ADR-024 and F3-01**, then separate **owner formal closure authorization for Phase 3**. Prepare the request in English but **do not post issue comments** without express permission. [Issue #5](https://github.com/dab960405/tickeringsystem/issues/5).
5. **Obtain explicit independent Tech Lead verdict for ADR-022/H84-01 and H75–H84/F4-05**, after Phase 3 closure, then owner formal closure authorization for Phase 4. [Issue #6](https://github.com/dab960405/tickeringsystem/issues/6).
6. **Capture the final main SHA and full green CI for Java + Angular + Python**, with focused PostgreSQL/Redis Testcontainers regressions and no relaxed coverage, skipped required suites or stale statuses.
7. **Obtain separate Phase 5 design and start authorization.** Proposed [ADR-023](../adr/ADR-023-phase-5-outbox-and-notification-delivery.md), [F5-01](F5-01-technical-design.md) and [F5-02](F5-02-test-and-acceptance-plan.md) remain design only, not approved implementation.

## Scope and architectural questions to decide **after** F3/F4 acceptance

- Transactional outbox writers must share the corresponding business transaction. Do not substitute Redis after-commit cache invalidation for a PostgreSQL outbox write.
- Resolve relay locking, Kafka acknowledgement, bounded transactional time, per-aggregate ordering and concurrency. The proposed V10 `outbox_events` table is a scaffold, not a working relay.
- Define event payloads/digests, target recipients and EventCancelled fan-out, canonical duplicates vs conflicting `eventId` contents and retention.
- Resolve exactly three delivery attempts versus three retries, ack after persistence, Mongo uniqueness, retry/DLT and Kafka failure behavior.
- Define trusted HTTP correlation and downstream header/payload propagation; keep internal shared notification API keys out of browser bundles.
- Decide safe local validation with zero budget and no real payments or emails.

All such topics are **proposed future decisions**, not an authorization to add dependencies, migrations, workers or cloud resources now.

## Explicit Phase 5 stop conditions

Do **NOT** create functional Kafka producers, topics, relay publishers, consumers, retries/DLT, MongoDB notification persistence, notification endpoints, AWS/Terraform deployment or paid services as part of closing Phases 3/4. Existing `common-events`, `notification-service`, Docker Kafka/Mongo definitions and `outbox_events` migration are allowed as inert historical scaffolding.

## Evidence discipline

- A green CI proves exercised scenarios, not complete correctness or a formal Tech Lead verdict.
- Historical runs and local tests must retain original dates/SHAs; source changes require a new matching CI before a current success claim.
- Document unverified external security scans as inconclusive, not zero vulnerabilities.
- Neither this file nor any developer comment can turn `PROPOSED` ADR-022/023/024 into `ACCEPTED` or close an issue on behalf of the owner.

**Entry verdict: NOT READY / F5 BLOCKED until the listed gates are satisfied and the owner separately authorizes Phase 5.**

## Current remediation evidence supplement — 2026-11-22

- Previous technical defects REV-DEV-01/02, AUD-02 residual, ADV-03/04, BACK-02/04, UI-NEW-01 and TOOL-NEW-01 have permanent source-level fixes and CI-backed regressions. The new identity-sensitive Angular tests reproduce and correct late asynchronous purchase responses across sessions, and the browser CI step now exercises direct/reloaded nested routes in real Chrome.
- The full CI baseline prior to the latest browser addition is [CI #344](https://github.com/dab960405/tickeringsystem/actions/runs/44986504339): 410 Java unit, 191 integration, Angular 15, Python 11+3. Additional green runs must be associated with their own exact SHA; never treat the earlier run as evidence for a later changed tree.
- The repository follows owner-authorized [ADR-025 English-only policy](../adr/ADR-025-english-only-project-policy.md) for actively maintained text. Existing historical Spanish URL aliases and data identifiers are preserved for backwards compatibility; do not mistake translation for business-contract approval.
- **Remaining gates:** independently approve or explicitly reject AUD-03/05/10/12 contracts, ADR-024/022, then F3 technical conformance/owner formal closure, F4 conformance/owner formal closure, finally approve ADR-023 and explicitly authorize Phase 5 implementation. Do not implement Kafka relay, consumers or Mongo notifications before authorization.


**Exact post-remediation code verification:** [CI #348](https://github.com/dab960405/tickeringsystem/actions/runs/44986963218), `main@e318c46f0623ee2cdebaf2dbe8241b8b7ddf78d7`, completed **SUCCESS**: 410 Java unit tests, 195 PostgreSQL/Redis Testcontainers integrations (including 13/13 ticket-type name races), Maven `clean verify`, Spotless and JaCoCo; Angular 19/19 with a production build, Prettier and **real Google Chrome** direct/deep-route independent reload and legacy bookmark with JS/CSS HTTP 200; Python frontend 11/11 and scripts 3/3. This is GitHub Actions evidence, **not** a fresh local Docker or full paid-order browser E2E run. Any later documentation commit requires separate same-SHA CI verification; neither test success nor this report grants Tech Lead acceptance.

## Owner-authorized Phase 3 remediation update — 2026-11-22

The owner has approved bounded Phase 3 architectural and risk dispositions recorded in [F3-02](../phase-3/F3-02-owner-disposition-2026-11-22.md), [ADR-024](../adr/ADR-024-event-cancellation-transactional-exception.md) and [ADR-026](../adr/ADR-026-bounded-phase-3-cross-module-coordination.md). The new HTTP correlation filter and tests do not implement notification delivery. **Independent Tech Lead Phase 3 conformity and formal phase closure are still required**, followed by independent Phase 4 decisions (including ADR-022/H84-01) and formal closure before any separate Phase 5 authorization. The Phase 5 status remains **BLOCKED**. No Kafka, MongoDB or outbox delivery was added.
