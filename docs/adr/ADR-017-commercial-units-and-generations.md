# ADR-017 — Stable commercial units and ticket issuance generations

- **Status:** ACCEPTED
- **Date:** 2026-11-13
- **Phase:** 4
- **Addresses:** H77

## Context

`OrderItem.quantity` is an aggregate count; it does not identify each purchased ticket unit. A list of tickets keyed by `order_item_id` alone cannot prevent all concurrent duplicate issuance or support replacement while retaining stable purchased-unit identity.

## Decision: four persistent concepts

1. **Commercial generation** (`commercial_generations`): belongs to an order, has increasing `generation_number` and state `ACTIVE`, `SUPERSEDED` or `CANCELLED`. At most one generation is active for an order.
2. **Commercial unit** (`commercial_units`): belongs to an order and order item. `unit_number` is unique within the item. The unit UUID remains stable across ticket replacement.
3. **Unit generation** (`commercial_unit_generations`): associates a unit with a generation from the **same order**; unique `(unit_id, generation_id)` prevents duplicate linking.
4. **Ticket** (`tickets`): references unit, generation, order and order item. At most one ticket exists for a given `(commercial_unit_id, generation_id)` **including cancelled tickets**. Replacement requires a new generation.

Composite foreign keys include `order_id` and, when required, `order_item_id`. PostgreSQL prevents a unit from joining another order's generation or a ticket from referencing an item other than its unit's item. Java validation alone is insufficient.

## Initial issuance

A confirmed payment creates generation 1, one unit per item quantity, an association for each unit and one `VALID` ticket per unit. The business application commits atomically. Repeated issuance returns the active generation without creating duplicates. Unique constraints are the last defense under races.

## Replacement

The original narrative order was:

1. Lock order and order items according to ADR-016.
2. Cancel previous tickets and mark old generation SUPERSEDED.
3. Create next generation and link the same units.
4. Issue new UUID ticket codes.

**Historical correction:** [F4-03](../phase-4/F4-03-lock-audit.md) and [ADR-016](ADR-016-global-lock-order.md) later determined that Hibernate flush/implicit FK locks require the **new U generation relationships before old EN cancellation is materialized**. The implemented U-before-EN acquisition order takes precedence over the sequence above. Preserve history; do **not** refactor back to the old EN → U order.

A `USED` ticket cannot be reissued or cancelled through this workflow. Only `VALID → USED` and `VALID → CANCELLED` are allowed; USED and CANCELLED are terminal.

## Migration and backfill

The additive migration introduced the model and backfilled existing tickets: generation 1 per order containing tickets, units per purchased quantity, associations and deterministic item mapping. New NOT NULL constraints were enabled only **after** backfill. Do not edit previously applied Flyway migrations.

## Consequences

- Purchased unit identity survives ticket replacements; historical generations remain auditable.
- Database uniqueness/composite FKs prevent duplicates and mismatched order/item/generation references.
- Reissuance is an **internal service capability** at this stage; no public administrator API or invented workflow is authorized.
