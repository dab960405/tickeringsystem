# F4-03 — Cross-cutting PostgreSQL lock audit

- **Date:** 2026-11-14.
- **Scope:** H75, H76 and H83, under ADR-016.
- **Historically verified SHA:** `24be7cbe670e7076d8e6999a9d708ef4cad09fe1`, CI #292.
- **Historical status:** Lock audit completed for the then-current schema and flows; **Phase 4 still formally OPEN**.

## Methodology

The review inspected `ticketing-api` service and repository methods acquiring explicit PostgreSQL locks or performing writes that can introduce **implicit foreign-key locks**. It included `FOR SHARE`, `FOR UPDATE`, `SKIP LOCKED`, conditional updates, FK inserts and JPA flush.

The canonical [ADR-016](../adr/ADR-016-global-lock-order.md) lock sequence is:

`E → D → PE → J → XA → V → N → CR → C → DS → T → R → O → P → I → U → EN → IS → K`

Unlocked discovery queries that only obtain candidate IDs do not count as acquiring existing-row locks. When decisions depend on mutable data, the application rechecks it **after** obtaining required locks. An INSERT of a new row is not itself a row lock on an existing row, but FK checks can lock its parent with `KEY SHARE`; these are part of the audit.

## Historically verified flow sequences

| Flow | Relevant lock sequence | Historical result |
| --- | --- | --- |
| Event editing/publishing | E | Compatible |
| Ticket-type create/edit | E → T | Compatible, subject to separately tracked BACK-01 case-insensitive name race |
| Reservation creation | E → T → R (INSERT) | Compatible |
| Reservation cancellation | T → R | Compatible |
| Reservation confirmation/recovery | E → T → R | Compatible |
| Reservation expiration | Sorted E → sorted T → sorted R / `SKIP LOCKED` | Compatible |
| Order creation | E → T → R, then new O/P/I rows | Compatible after H83-02 |
| Pending-order cancellation | E → T → R → O | Compatible |
| Event cancellation | E → T → R → O | Compatible |
| Order expiration | E → T → R → O | Compatible |
| Capture/payment failure | E → T → R → O → P → I → U → EN | Compatible |
| Recoverable application | E → J → XA → T → R → O → P → I → U → EN → IS | Compatible |
| Worker claim/retry | J → XA | Compatible |
| Operation projection | J → XA → K | Compatible |
| Confirmed refund | O → P → EN | Compatible |
| Initial ticket issuance | O → I → U → EN | Compatible |
| Ticket reissuance | O → U → EN | Compatible after H83-01 |

`DS`/`CR`/`C` and inbound identities are created as append-only evidence in their relevant transactions. The original audit did not find an existing-row lock inversion after the two fixes; later J/XA updates in APPLY/PROJECT refer to rows already locked at transaction start. Any future change needs a new review.

## H83-01 — Reissuance could acquire EN → U

### Root cause

`TicketService.reissueForOrder` marked tickets in the older generation as cancelled in memory, then called `generationRepository.saveAndFlush(current)`. Hibernate could flush UPDATEs to older `tickets` (EN) first. New generation association inserts then validate FK against `commercial_units`, potentially acquiring implicit locks on U afterward.

The effective order **EN → U** would invert ADR-016.

### Implemented correction, historical commits

- `92fc29957f968bb26486c230eab07720058297db` — `fix(tickets): preserve global lock order during reissue`.
- `798537ba0d7f0b2d153a3d521e735dd91317d3e8` — Spotless follow-up.

The former generation is marked `SUPERSEDED` and flushed before inserting its successor, but **old ticket cancellation occurs only after** persisting the new U/generation associations and successor tickets (EN). All steps remain in one PostgreSQL transaction, so no intermediate state becomes committed/visible.

`TicketServiceTest` asserts the ordering: generation transition → commercial-unit links → new tickets → old-ticket cancellation. `CommercialIssuanceIT` contains 14 issuance, reissuance, concurrency and refund-race tests.

## H83-02 — Order creation could acquire R → T through FK

### Root cause

`ReservationService.lockActiveForOrder` locked E → R, skipping T because order creation does not directly mutate inventory. But V7 defines `order_items.ticket_type_id REFERENCES ticket_types(id)`. During order/item flush, PostgreSQL could acquire a `KEY SHARE` parent lock on T.

Therefore the path could already hold R and **later acquire T**, opposite to the global T → R protocol used by cancellation/expiration.

### Implemented correction, historical commits

- `ea4a061eec20cf1fd02eb305163d9d0c14937c36` — `fix(orders): lock ticket type before reservation`.
- `7a7b75a50b1e2c125c44d4bdfe15fc1bad81ede3` — Mockito fixture correction.
- `24be7cbe670e7076d8e6999a9d708ef4cad09fe1` — Spotless and successfully validated baseline.

`lockActiveForOrder` now obtains **E → T → R**. T is not taken to change stock, but to ensure the FK check on `order_items` does not acquire it after R.

`ReservationServiceTest` verifies E → T → R with Mockito `InOrder`. `OrderCreationIntegrityIT.should_lockTicketTypeBeforeReservation_when_orderCreationCompetesWithRelease` uses real PostgreSQL: holds T locked, proves order creation waits at T and asserts the blocking transaction can still acquire R, ruling out a premature R lock.

## Historical CI evidence

**CI #292** at SHA `24be7cbe670e7076d8e6999a9d708ef4cad09fe1`:

- API Surefire **390**, zero failures/errors/skips.
- API Failsafe **148**, zero failures/errors/skips.
- `OrderCreationIntegrityIT` 5/5.
- `CommercialIssuanceIT` 14/14.
- `EventGuardConcurrencyIT` 9/9.
- `OperationWorkerIT` 9/9.
- `ReservationConcurrencyIT` 20/20.
- `ReservationHttpConcurrencyIT` 20/20.
- Spotless: 220 clean API Java files.
- JaCoCo checks satisfied; Maven `BUILD SUCCESS`.

## Audit disposition and reopening criteria

- **H75:** historical technical check passed for that SHA. No additional explicit existing-row or FK-parent lock inversion was found in the then-current flows.
- **H76:** T before R was verified across the relevant inventory/reservation coordinators; lifecycle time decisions used PostgreSQL time after locks where applicable.
- **H83:** passed for the historical schema after H83-01 and H83-02 were corrected.

**Reopen** when a new migration adds FKs in the lock chain, a service adds `FOR UPDATE`/`FOR SHARE`, or a JPA flush changes the order of writes relative to parent locks.

The cross-module coupling of `EventCancellationService` is a **separate architecture question**: its E → T → R → O order conforms to ADR-016, but its direct neighbor-repository/SQL access is only **proposed** for exemption in [ADR-024](../adr/ADR-024-event-cancellation-transactional-exception.md). Other AUD-05 cross-module dependencies need their own dispositions.

**This dossier does not formally close Phase 4 or authorize Phase 5.**
