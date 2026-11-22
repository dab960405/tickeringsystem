# ADR-013 — Redis cache for catalog metadata

- **Date:** 2026-11-07
- **Status:** ACCEPTED
- **Phase:** 4

## Context

Catalog queries repeatedly read relatively stable metadata, while ticket availability changes with each reservation and stock release. Caching complete responses could expose stale availability. Redis was already used by the API and its integration container.

## Alternatives

1. Cache complete catalog responses including availability.
2. Cache JPA entities.
3. Cache **only serializable metadata**, querying availability on every request.

## Decision

- Use cache-aside for metadata-only DTO records.
- Read `availableQuantity` from PostgreSQL for every request through a light projection.
- Configure TTL with `TICKET_TYPES_CACHE_TTL`, default five minutes.
- Invalidate the catalog after committed writes; the transaction-aware `RedisCacheManager` delays invalidation until commit.
- On Redis data-access failures (`DataAccessException`), log the event and continue without caching, falling back to PostgreSQL.

## Consequences

- Metadata reads are reduced without caching critical inventory.
- Catalog invalidation is currently broad/global because the write operation identifies a ticket type, not its event. This simplicity/correctness trade-off is accepted.
- Cache degradation must not conceal arbitrary programming bugs; only documented Redis/data access faults are handled.
- Serialization, expiration, invalidation and outages require executed integration tests before Phase 4 closure.
- **Later evolution:** ADR-020 implemented the additional public event cache/projection/rate-limits originally deferred. ADR-013 is not the complete Phase 4 scope on its own.
