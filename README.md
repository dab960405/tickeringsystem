# Ticketing Platform

[![CI](https://github.com/dab960405/tickeringsystem/actions/workflows/ci.yml/badge.svg)](https://github.com/dab960405/tickeringsystem/actions/workflows/ci.yml)

Ticketing is a portfolio event-ticketing platform built on Java 21, Spring Boot 3.5, PostgreSQL 17 and Redis 7. It demonstrates modular domain design, role-based authentication, atomic inventory, time-limited reservations, idempotent orders, signed **simulated** payment callbacks, ticket issuance, durable operation recovery and controlled infrastructure degradation. An Angular 22 buyer interface and a Python localhost-only mock gateway provide a local demonstration.

> **Project status (2026-11-21):** Phases 0–2 were formally closed. Phases 3 and 4 are implemented substantially but remain **OPEN pending technical remediation, independent Tech Lead verdicts and formal owner acceptance**. ADR-024 (transactional event cancellation exception) and ADR-022 (SIMULATED refund evidence contract) are **PROPOSED, not approved**. Only `main` exists as the permanent branch. Phase 5 (Kafka/outbox/notifications/MongoDB) is **BLOCKED**. The owner explicitly requires English for all maintained project content; the migration is tracked under [ADR-025](docs/adr/ADR-025-english-only-project-policy.md).

## Documentation and review

- [Current engineering remediation report](docs/audit/MAIN-REMEDIATION-2026-11-21.md).
- [Project progress, verification history and open findings](docs/PROGRESS.md).
- [Architecture and module boundaries](docs/architecture.md), [Flyway-derived ERD](docs/erd.md), [decisions](docs/decisions.md) and [source-verification policy](docs/engineering-decision-verification.md).
- [Local run guide](docs/LOCAL_RUN.md), [HTTP example flow](docs/flow.http), [adversarial testing evidence](docs/ADVERSARIAL_TESTING.md).
- [Angular client](frontend/README.md) and [frontend verification](frontend/VERIFICATION.md).
- [Phase 3 dossier](docs/phase-3/F3-01-closure-review.md), [proposed ADR-024](docs/adr/ADR-024-event-cancellation-transactional-exception.md), and [Tech Lead issue #5](https://github.com/dab960405/tickeringsystem/issues/5).
- [Phase 4 dossier](docs/phase-4/F4-05-closure-review.md), [proposed ADR-022](docs/adr/ADR-022-simulated-refund-evidence-contract.md), and [Tech Lead issue #6](https://github.com/dab960405/tickeringsystem/issues/6).
- [Phase 5 planning only](docs/phase-5/F5-00-plan-and-local-validation.md). The design is not permission to implement it.

## Architecture

| Module | Responsibility | Status |
| --- | --- | --- |
| `ticketing-api` | Modular Spring Boot REST API: authentication, events, venues, inventory, reservations, orders, simulated payments, recoverable operations and tickets | Active |
| `common-events` | Shared, typed `EventEnvelope` contract | Scaffold for future phases |
| `notification-service` | Separate health-only service shell | **No Kafka consumer or MongoDB delivery yet** |
| `frontend` | Angular 22 standalone buyer SPA: catalog, registration/login, reservations, checkout, mock payments/refunds, orders and tickets | Local demo |
| `docs/` | Architecture, ADRs, reviews, migration-driven ERD and phase dossiers | Maintained |

The API is a modular monolith. PostgreSQL is the authority for stock, orders, payment evidence and tickets. Redis stores short-lived **catalog metadata**, reservation projections, refresh-token session state and rate limits, but is never the source of inventory truth. An operation pipeline separates RX, CLAIM, APPLY and PROJECT; leases and fencing constrain recovered workers. Payments and email delivery are **not real**.

## Technology and reproducibility

Java 21 · Maven Wrapper · Spring Boot 3.5.x · PostgreSQL 17 · Redis 7 · Flyway · Spring Security JWT · JUnit 5 · Testcontainers · Awaitility · JaCoCo · Spotless · Angular 22 · Node 24 · Python 3.10+ · Docker Compose.

The Compose file also includes pinned Kafka/MongoDB images as **unused Phase 5 scaffolding**. Their existence does not mean Kafka or notification persistence is implemented.

### Prepare a local environment

Use the supported local setup, which generates random secrets and preserves existing `.env` files:

```bash
bash scripts/local.sh init
bash scripts/local.sh doctor
bash scripts/local.sh infra
bash scripts/local.sh run
```

The API listens on localhost port 8080 by default. In a separate terminal:

```bash
cd frontend
npm ci
cd ..
bash scripts/local.sh ui
```

The browser demo listens on `http://localhost:5173`. The Python dev server binds to `127.0.0.1`, obtains order totals from the authenticated backend and signs simulated callbacks server-side. **Do not deploy it publicly**. See the [local operations guide](docs/LOCAL_RUN.md) for exact environment and smoke-test steps.

For Phase 3/4 work, start **only PostgreSQL and Redis**; Kafka and MongoDB must not be required.

### Quality commands

```bash
./mvnw -B clean verify
(cd frontend && npm ci && npm test && npm run build && npm run format:check)
python3 -m unittest discover -s frontend -p 'test_*.py' -v
python3 -m unittest discover -s scripts -p 'test_*.py' -v
docker compose --profile infra config --quiet
```

Maven `clean verify` runs Surefire unit tests, Failsafe integration tests with Testcontainers, Spotless and JaCoCo. PostgreSQL/Redis tests require a working Docker daemon. Angular and Python must be checked separately unless the GitHub Actions workflow includes them. Do not equate a Java-only green CI with full frontend or browser validation.

### Verified historical baseline

- [CI #322](https://github.com/dab960405/tickeringsystem/actions/runs/44974013851) on `main@236b31f46534f65c1249ff1f4cbd25586dc4938b`: **407 unit tests and 179 integration tests**, Spotless, JaCoCo, build success.
- [CI #324](https://github.com/dab960405/tickeringsystem/actions/runs/44981366848) on `main@11dab6d00d8298024a2d10aee4857a0de11f236d`: **407 unit tests and 181 integration tests**, including new operation-recovery and cancelled-reservation expiration regressions, all checks green.
- Older runs and coverage snapshots remain available in [PROGRESS](docs/PROGRESS.md). Historical results must retain their original SHA and scope.

## Core domain guarantees

- **Atomic stock:** PostgreSQL conditional updates prevent overselling. `ReservationConcurrencyIT` and `ReservationHttpConcurrencyIT` each repeat a 100-request/10-ticket scenario 20 times; a successful repeat yields 10 reservations and 90 conflicts.
- **Reservations:** an active hold has a TTL. Confirmation, cancellation and expiration obey inventory-disposition invariants, with one-time stock release.
- **Orders:** one order per reservation, idempotency-key conflicts, immutable captured price and clear pending/cancelled/expired/paid states.
- **Simulated payments:** raw-body HMAC validation, separate provider event and operation identities, append-only evidence, semantic digests, late captures without invented refunds, and explicit business-application outcomes.
- **Tickets:** commercial units and generations prevent duplicate issuance and support cancellation or replacement.
- **Concurrency:** ADR-016 governs global lock ordering, including implicit foreign-key locks and ORM flushes. PostgreSQL time is used after obtaining critical locks.
- **Redis degradation:** public catalog reads can fall back to PostgreSQL, while authentication operations dependent on Redis fail closed. JWT access tokens remain locally verifiable.
- **Security:** access control uses JWT roles and resource ownership, with unauthorised resources concealed according to ADR-021.

Finite integration tests verify their named scenarios, not every possible interleaving.

## Roadmap and formal acceptance

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Maven modules, base configuration and infrastructure scaffold | **CLOSED** |
| 1 | Entities, APIs, Flyway, domain and repositories | **CLOSED** |
| 2 | Authentication, JWT, permissions and owned-resource isolation | **CLOSED** (approved 2026-11-13) |
| 3 | Reservations, orders, payments and tickets | **OPEN** — fixes under verification; ADR-024 and independent decision pending |
| 4 | Redis, cache/rate limiting and audited H75–H84 recovery work | **OPEN** — ADR-022, H84-01 and final verdict pending |
| 5 | Kafka outbox, producer, consumer and Mongo notification persistence | **BLOCKED; not started** |
| 6 | Full Compose, observability and deployment-quality improvements | Not started |
| 7 | CI/CD evolution and cloud deployment options | Not started |
| 8 | Polish, documentation and optional improvements | Not started |

For every phase closure, the owner requires a traceable technical dossier, explicit Tech Lead verdict, the exact commit and matching CI, plus formal owner acceptance. A passing build does **not** approve a proposed ADR or close a phase. Phase 4 closure depends on prior Phase 3 closure. No Kafka producer, relay, consumer or Mongo notification functionality may be implemented under the current authorization.

## Known boundaries and technical debt

- ADR-024 proposes a narrowly scoped transactional exception for `EventCancellationService`; a broader cross-module-access inventory is still subject to design review.
- ADR-022 defines the proposed stable-reference and independently signed subsequent refund-event contract for the `SIMULATED` provider. There is no automatic financial reconciliation or real payment provider.
- Refresh tokens do not rotate on each refresh under the current contract.
- HMAC webhooks do not provide a separate timestamp/replay window; persistence identities and signed evidence are the current simulator controls.
- Other scoped findings and the owner-mandated English-language migration are tracked in the [remediation report](docs/audit/MAIN-REMEDIATION-2026-11-21.md).

This is an educational/portfolio system. Do not configure it for real money transfers or send real email based on its mock capabilities.
