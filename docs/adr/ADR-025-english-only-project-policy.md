# ADR-025 — English-only project language policy

- **Decision type:** Project governance and documentation policy; no public API or database contract is changed by this record.
- **Status:** Owner-authorized language policy; architectural ADRs remain subject to independent Tech Lead approval.
- **Effective date:** 2026-11-21.
- **Scope:** All actively maintained, project-controlled files in the repository.
- **Source of authority:** The project owner explicitly required that the whole Ticketing repository be in English and authorized the associated implementation.

## Context

The original functional specification requested Spanish for certain documentation sections. This later and explicit owner instruction **supersedes those language instructions only**. It does not supersede domain acceptance criteria, approved financial rules, lock ordering, API stability or phase governance.

The repository currently contains Spanish prose in Markdown files, Angular labels/routes, Python/Bash messages and test fixtures. An automated translation or wholesale replacement is not a substitute for verifying technical meaning and cross-file links.

## Decision

1. English is mandatory for active documentation, source comments, developer messages, diagnostics, tests, scripts, frontend text and accessibility metadata.
2. Keep external standards, citations, UUIDs, protocol constants, SQL enum values and historical Git commit identities unchanged.
3. Translate historical ADR text **faithfully**, preserving original decision dates, status, technical alternatives and supersession relationships. Never silently mark ADR-022, ADR-023 or ADR-024 accepted.
4. Translate the original Spanish specification into a canonical English reference while retaining provenance and the exact functional requirements.
5. Update application routes and local server fallback **together**, with regression tests and compatibility decisions for existing bookmarks. Do not rename public API identifiers or database columns merely to translate text.
6. Keep machine-readable evidence (JSON, timestamps, SHAs, logs) intact when translating descriptive fields; don't falsify or silently rewrite recorded results.
7. Enforce English-only content in CI **after** the migration is complete, using a documented, auditable rule and exceptions for external/historical data.

## Consequences

- This is a repository-wide change requiring review of documentation links, CLI output, UI behavior and tests; it must be tracked separately from business-critical bug fixes.
- A document remaining in Spanish is **migration work outstanding**, not permission to lower product-quality gates.
- All new work must be written in English immediately.
- This policy does not authorize implementation of Phase 5. Kafka, outbox relay, notification consumers and MongoDB notification storage remain blocked.

## Verification

A final language review must scan all tracked first-party textual files. A green Java CI does **not** establish English-language compliance. The reviewer must report the actual remaining files and exclusions rather than claim completion based on a subset.
