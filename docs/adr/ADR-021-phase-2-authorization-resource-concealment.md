# ADR-021 — Concealing resources owned by other users in Phase 2 authorization

- **Date:** 2026-11-13
- **Status:** ACCEPTED
- **Phase:** 2

## Context

An ORGANIZER can manage their own events/ticket types while ADMIN can manage these resources globally. Reservation and order operations are scoped to the authenticated user. The earlier policy returned 404 for nonexistent resources but 403 for existing resources belonging to another organizer, potentially disclosing draft/private resource existence.

The security specification requires ownership checks without leaking resources the requester cannot inspect. Resource ownership must derive from the validated JWT identity, **not** from an arbitrary client-provided user ID.

## Alternatives considered

1. Return **403** for every existing foreign resource: reveals that the resource exists.
2. Return **404** for foreign or nonexistent private resources: hides existence; preserve 403 for authenticated users lacking the required functional role.
3. Enforce authorization only in controllers: easier to miss in internal service calls and new entry points.

## Decision

Use HTTP **404** for both nonexistent private resources and existing resources belonging to another user/organizer on the same protected operation.

- ORGANIZER may manage only own events and attached ticket types.
- ADMIN retains global management access.
- ORGANIZER attempting to manage another organizer's event receives the same 404 as a nonexistent ID.
- A user lacking the permitted role still receives 403 by role enforcement. Ownership concealment never replaces role checks.
- Reservation/order queries combine resource ID with the user ID derived from the JWT; foreign/missing records return 404.
- Ownership authorization stays at service level through `PreAuthorize` and reusable authorization services.

## Consequences

- Existence of a private event/draft is not revealed to unauthorized requesters.
- Clients cannot distinguish nonexistent IDs from foreign IDs on ownership-protected routes.
- 403 remains a distinct role denial signal.
- Tests verify identical HTTP status for foreign/nonexistent resources and successful access by the true owner.

## Evidence and limitations

- `EventAuthorizationService` handles event ownership; `TicketTypeAuthorizationService` delegates through the parent event.
- `OrderService` uses `findByIdAndUserId`; `ReservationService` reads by ID plus user ID.
- `AuthenticationFlowIT` exercises actual JWTs for two organizers and two buyers. A foreign organizer cannot edit a draft, the owner can, and a foreign buyer cannot read/cancel an order or cancel another user's reservation.
- Historical tests became acceptance evidence only after their matching CI completed; see the [Phase 2 authorization matrix](../phase-2/authorization-matrix.md).
- The Phase 2 formal acceptance date (2026-11-13) is documented in the existing project progress record and must not be confused with later Phase 3/4 approvals.
