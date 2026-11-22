# F4-01 — Operational decisions and compatibility

- **Date:** 2026-11-13.
- **Historical baseline SHA:** `c6917e152c5440fbd346b1f21e1b98d065bca519`.
- **F4-00 publication SHA:** `0a16001eebf934bfd466878aacf058d8cf6bc89b`.
- **Scope:** Original pre-implementation decisions for F4-02–F4-05; no Phase 5 implementation.
- **Provenance:** These were the recorded historical operational design choices. Current main fixes and pending ADR-022/024 still require their own evidence/approvals.

## 1. Recorded operational decisions

| Topic | Recorded decision |
| --- | --- |
| Maximum TX-APPLY timeout | Transaction 5s, PostgreSQL `lock_timeout=1s`, `statement_timeout=4s`. RX/CLAIM/PROJECT each 3s. No external calls within those transactions |
| Roles and privileges | Matrix below. Internal-only workers; evidence without public API; PostgreSQL append-only constraints/triggers |
| Evidence retention | Events, operations, semantic applications and dispositions: minimum 365 days; executions: 90 days; projections: participation lifetime. No automatic purge in Phase 4; purging would need an ADR and reconciliation test |
| Evidence access/protection | No public endpoint. Internal services; future ADMIN audit read only. Payloads without HMAC secrets/full PII; logs use technical IDs only |
| Provider/namespace | `SIMULATED`, merchant `default`, namespace `payments.v1`; configurable by environment |
| `operation_kind` | `PAYMENT_CAPTURE`, `PAYMENT_FAILURE`, `PAYMENT_REFUND`; reject other values |
| Semantic `idempotency_key` | Provider-issued `providerOperationId` scoped by `order:{orderId}`; immutable, 1–100 characters; retained with evidence |
| Worker parameters | Lease 30s, batch 50, max 3 attempts, backoff 5s, recovery interval 1s; bounded and configurable |
| H79 reservation recovery | Grace 15 minutes from expiry, event not yet started, exact payment evidence and enough stock to restore the full purchase. Otherwise `UNFULFILLABLE` and `REQUIRED`, **never** `REFUNDED` without signed evidence |
| Original Redis requirements | ADR-020 adds event caching, reservation keys and reservation/order rate limits |

## 2. Roles and permissions

| Operation | Public | USER | ORGANIZER | ADMIN | Internal worker |
| --- | --- | --- | --- | --- | --- |
| Register/login/refresh | Yes | — | — | — | No |
| Read published events/venues | Yes | Yes | Yes | Yes | No |
| Create/cancel own reservation | No | Yes | No | No | Expire/release only if state permits |
| Create/list/cancel own order | No | Yes | No | No | Signed-evidence processing |
| Read own tickets | No | Yes | No | No | Issue/reissue under paid order |
| Create event | No | No | Yes | Yes | No |
| Edit/publish/cancel event | No | No | Owner only | Yes | No |
| Manage ticket types | No | No | Own events only | Yes | No |
| Venue CRUD | Read | Read | Read | Write | No |
| Payment webhook | Valid HMAC and provider contract only | No | No | No | RX/CLAIM/APPLY/PROJECT |
| Operation evidence | No | No | No | Future read-only if exposed | Internal append-only writer |

At SQL level Flyway uses the migration/owner role. Runtime should only require SELECT/INSERT/UPDATE/DELETE over business tables; evidence tables reject UPDATE/DELETE via triggers even if development runtime shares owner credentials. Separating migration and runtime credentials remains deployment hardening, not a reason to provision cloud resources.

## 3. Original operational settings

| Property | Value | Rule |
| --- | ---: | --- |
| `ticketing.operation.tx-rx-timeout` | `3s` | No business application |
| `ticketing.operation.tx-claim-timeout` | `3s` | J → XA |
| `ticketing.operation.tx-apply-timeout` | `5s` | Maximum; rollback when exceeded |
| `ticketing.operation.tx-project-timeout` | `3s` | Cannot replay business |
| `ticketing.operation.lock-timeout` | `1s` | PostgreSQL SET LOCAL |
| `ticketing.operation.lease-duration` | `30s` | Greater than APPLY timeout |
| `ticketing.operation.batch-size` | `50` | Max 100 |
| `ticketing.operation.max-attempts` | `3` | Terminal FAILED with evidence after exhaustion |
| `ticketing.operation.retry-backoff` | `5s` | Revalidate all conditions on retry |
| `ticketing.reservation.expiration-batch-size` | `100` | Never greater than 100 |
| `ticketing.reservation.recovery-grace` | `15m` | From `expires_at` |
| `ticketing.cache.events.ttl` | `60s` | Metadata only |
| `ticketing.rate-limits.scopes.reservation` | `20/60s` | Per buyer |
| `ticketing.rate-limits.scopes.order` | `10/60s` | Per buyer |

## 4. ADR compatibility matrix

| ADR | Compatibility and supersession |
| --- | --- |
| ADR-009 | Keep no automatic restock on refund. ADR-019 supersedes immediate local refund with a request plus provider evidence |
| ADR-010 | Keep atomic conditional inventory UPDATE; ADR-016 adds T lock for composed operations |
| ADR-011 | Keep order creation idempotency; ADR-019 replaces status/reference-only webhook deduplication with three persisted identities |
| ADR-012 | Keep `FOR UPDATE SKIP LOCKED`; ADR-016 supersedes R→T with T→R and requires state/time revalidation after locks |
| ADR-013 | Inventory availability stays uncached; ADR-020 adds event **metadata** only |
| ADR-014 | Preserve fallback, fail-open rate limiting and fail-closed refresh; ADR-020 completes three original Redis deferrals |
| ADR-015 | One order per reservation including cancelled orders |
| ADR-016 | Global lock ordering source of truth |
| ADR-017 | Stable commercial unit/generation model compatible with price snapshots in order items |
| ADR-018 | Durable operation worker model; no Phase 5 messaging |
| ADR-019 | Provider identity/idempotency and H79 late recovery; financial evidence is required |
| ADR-020 | Original Redis scope completed without using Redis for business integrity |

## 5. Remaining decisions at the **time of F4-01**

No further technical decision was considered blocking the next historical implementation increments F4-02–F4-05. **That statement does not approve later ADR-022/024 or settle newer findings on current main.** Formal Phase 4 closure still required owner authorization. AWS, deployment, Kafka, notifications and external providers were blocked/out of scope.

## 6. Original F4-01 verdict

**OPERATIONAL DECISIONS DOCUMENTED AND COMPATIBLE FOR SUBSEQUENT IMPLEMENTATION.** Recorded decisions were *not* proof of working software; each required code, migrations and tests in later increments. This translated document remains historical evidence, not a new independent acceptance.
