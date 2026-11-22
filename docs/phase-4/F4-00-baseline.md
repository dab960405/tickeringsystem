# F4-00 — Historical Phase 4 baseline report

- **Date:** 2026-11-13 (UTC).
- **Repository:** `dab960405/tickeringsystem`.
- **Authorized branch at the time:** `dev` (**historical; only `main` exists now**).
- **Initial verified remote HEAD:** `c6917e152c5440fbd346b1f21e1b98d065bca519`.
- **Original worktree state:** clean, no pending tracked file changes.
- **Audited scope:** that exact SHA; the document publication commit can be recovered from `git log -1 -- docs/phase-4/F4-00-baseline.md`.

**Historical baseline, not current findings.** Later Phase 4 increments implemented most gaps found below. For current status, consult [PROGRESS](../PROGRESS.md), [F4-05](F4-05-closure-review.md) and the pending decision register.

## 1. Objective

Establish a reproducible baseline before modifying code, compare the actual repository against the assigned F4-00–F4-07 work and identify gaps preventing Phase 4 closure.

## 2. Inspected sources

- Complete original technical specification, particularly ``3, 6–12, 15, 22 and 24.
- The integrated F4-00–F4-07 engineering assignment and authorization of bounded implementation.
- `README.md`, `docs/PROGRESS.md`, `docs/architecture.md`, `docs/decisions.md`, `docs/erd.md` and `docs/LOCAL_RUN.md`.
- ADR-009 in the decisions register and ADR-010–015 in `docs/adr/`.
- Root/API Maven POMs, Maven Wrapper and `.github/workflows/ci.yml`.
- Flyway V1–V11, with reservations, orders, tickets and payments scrutinized.
- `ReservationService`, `ReservationExpirationService`, `ReservationRepository`, `TicketTypeInventoryService`, `TicketTypeRepository`, `OrderService`, `OrderPaymentService`, `OrderCancellationService`, `TicketService` and associated domain entities.
- `AbstractIntegrationTest` and `ReservationConcurrencyIT`.
- At that time the history of `dev` contained neither strings `H-75` nor `ADR-016`; the complete 03-E designs cited by the assignment were not versioned in this repository.

## 3. Original Phase 4 acceptance criteria

Original specification `22 defines **Redis cache with invalidation, reservation TTL and rate limiting**; acceptance requires a Redis-outage degradation test and demonstrable cache hits. Specification ``9–11 additionally require:

1. Cache published `GET /events/{id}` and popular catalog lists, default 60-second TTL, and invalidate when editing/publishing/cancelling.
2. `reservation:{id}` cache/projection keys with reservation-equivalent TTL.
3. Login and reservation/order creation rate limiting.
4. Redis never as the business source of truth; PostgreSQL remains authoritative during degradation.

The later F4 assignment expanded acceptance evidence with H75–H80: global lock order, stable commercial units/ticket generations, operation cycles/jobs/recovery, late reservation recovery and three persistent idempotency identities. Phase 5 remained blocked.

## 4. Initial compilation, tests, coverage and CI

### Local verification at the original SHA

- Toolchain outside repository: Temurin `21.0.13+9` LTS.
- Command: `JAVA_HOME=/tmp/jdk21 PATH=/tmp/jdk21/bin:$PATH ./mvnw -B test`.
- Result: `BUILD SUCCESS`; 46 Surefire reports, **315 tests, zero failures/errors/skips**.
- Docker was unavailable in that assisted environment; Failsafe/Testcontainers were **not** run locally. The original report did not claim otherwise.

### Independent GitHub Actions verification

- [Run #43283365129](https://github.com/dab960405/tickeringsystem/actions/runs/43283365129), SHA `c6917e152c5440fbd346b1f21e1b98d065bca519`.
- `Build and test` was `completed/success`; `./mvnw -B clean verify` passed.
- Downloaded report XML: Surefire **315/315**, Failsafe **61/61**, total **376**, zero failures/errors/skips.
- API package-scoped JaCoCo counters:

| Packages | Covered/total lines | Line coverage | Covered/total branches | Branch coverage |
| --- | ---: | ---: | ---: | ---: |
| `service` | 501 / 522 | **95.98%** | 126 / 151 | 83.44% |
| `domain` | 291 / 296 | **98.31%** | 84 / 101 | 83.17% |

These are **historical** coverage numbers for that exact SHA, not current main percentages.

## 5. Gaps identified in the original baseline

| Finding | Original state | Missing behavior at that SHA |
| --- | --- | --- |
| H75 | Conditional stock UPDATE and a 100-buyers/10-units test repeated 20 times existed | Scenario covered, but not all writes or the consolidated global locking protocol |
| H76 | Payment confirmation/release and expiration acquired or consulted R before mutating T | `R → T` was incompatible with the later consolidated `T → R`; ADR-016 did not yet exist |
| H77 | `OrderItem.quantity` and tickets linked only to `order_item_id` | No stable commercial units, generations, unit-to-ticket links or per-unit/generation uniqueness |
| H78 | Reservation expiration scheduler with `SKIP LOCKED` only | No cycles, participations, durable jobs, worker executions/dispositions, fenced leases or projections |
| H79 | Late successful capture attempted stock recovery; failure marked payment/order `REFUNDED` | No separation of financial capture evidence, outstanding refund obligation and confirmed provider refund |
| H80 | Webhook based on `orderId`/`status`/`providerReference` with status-based deduplication | Missing event identity, provider operation identity, global semantic application identity, digests and persistent constraints |
| Original Redis scope | Ticket-type metadata cache, login limits and refresh tokens | ADR-014 explicitly deferred event cache, reservation projection and order/reservation rate limiting |

**These were baseline gaps, not claims that the current main branch still lacks V14–V16 or Redis features.**

## 6. Risks and source conflicts recorded then

1. **Unavailable 03-E sources:** The assignment summarized settled decisions, but full source documents were absent from Git history. The review used the actual supplied assignment as input and required each implementable decision to be recorded. It did not claim to read unavailable originals.
2. **ADR-014 deferrals:** Three deferrals conflicted with original Phase 4 acceptance and had to be implemented or remained blocking. ADR-020 later completed them.
3. **H79 vs ADR-009/011:** Immediate refund transition could falsely claim financial refund without independent provider evidence. The subsequent model separates `SUCCEEDED`, refund `REQUIRED` and signed `REFUNDED`.
4. **Coverage:** New business packages would be subject to the per-package 70% JaCoCo threshold; each code increment needed tests.
5. **No local Docker:** PostgreSQL constraints/concurrency needed independent CI Testcontainers with exact SHA.
6. **Out of scope:** No Kafka publisher, notification consumer, deployment, AWS or external gateway.

The original audit found no reason to stop all work: H79's financial evidence issue was a **blocking implementation requirement** and had to be solved before closure.

## 7. Historical proposed implementation sequence

1. **F4-01:** ADR-016 and policy for global `T → R`, commercial units/generations, workers, identity, recovery, roles, evidence retention, timeouts and compatibility.
2. **F4-02:** Inventory locking before reservations, database `clock_timestamp()` after locks, batches of at most 100, concurrency and rollback tests.
3. **F4-03:** Commercial units and ticket generations with composite FK/partial uniqueness, idempotent issuance and controlled replacement.
4. **F4-04:** Cycles, participations, effective jobs, executions/leases/fencing, dispositions and projections; separate RX/CLAIM/APPLY/PROJECT.
5. **F4-05:** Provider-event inbox, provider operations and global semantic identity with immutable digest, plus H79 rules without an unevidenced refund.
6. **F4-06:** Original Redis scope, fresh/evolutionary Flyway tests, regression/concurrency/security/coverage/CI.
7. **F4-07:** Acceptance/H75–H80 matrices, residual risks and reviewer verdict; formal closure only with owner approval.

The later repository uses some F4-02/F4-03 numbers for different delivery milestones; **use filenames and SHAs**, not numbering alone, to match work.

## 8. Original F4-00 verdict

**BASELINE VERIFIED; IMPLEMENTATION NOT YET READY FOR CLOSURE at the audited SHA.** The existing build passed, but H76–H80 and three Redis deferrals were open. The F4-00 analysis modified no code, created no branch, did not touch `main`, deployed nothing and did not start Phase 5.
