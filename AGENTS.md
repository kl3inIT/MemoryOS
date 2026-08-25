# MemoryOS repository guide

The repository is the system of record. Chat, Linear, pull-request comments, and agent memory are inputs; durable project knowledge belongs in the documents below.

## Read in this order

1. [README.md](README.md) — build and entry points.
2. [ARCHITECTURE.md](ARCHITECTURE.md) — current implemented structure and runtime flows.
3. [docs/roadmap.md](docs/roadmap.md) — delivered, active, and candidate increments.
4. The relevant capability spec under `docs/specs/` and verification matrix under `docs/tests/`.
5. The active increment under `docs/increments/active/` when changing in-flight work.
6. Accepted decisions under `docs/decisions/` only when their rationale matters.

## Operating rules

- Keep this file a map, not an encyclopedia. Put each fact in one canonical document and link to it.
- Classify knowledge before writing: current implementation in `ARCHITECTURE.md` or `docs/specs/`; product intent in `docs/vision.md`; cross-cutting engineering policy in `docs/conventions.md` or `docs/guidelines/`; change-local reasoning in the active increment.
- Treat `core` as capability implementation, not a framework-free domain layer. Capability code may use Spring, `JdbcClient`, transactions, or JPA when they reduce real complexity; forbid dependency inversion violations and speculative layers, not framework use.
- Start non-trivial work with an increment directory containing `design.md` and `plan.md`. Update both as scope changes.
- Record an ADR only after the decision is accepted and implementation has started. ADRs are append-only; supersede them with a new ADR.
- After verification, consolidate durable facts into architecture/spec/test/guideline documents in the same change. Keep the increment under `active/` until the pull request merges; then move it to `completed/` and reconcile the roadmap.
- Never ship a temporary runtime mode, one-shot application profile, speculative endpoint, or unused abstraction to make an incomplete flow operable. Implement the real authorized runtime path, or keep the capability absent. See [ADR 0002](docs/decisions/0002-no-speculative-operational-surfaces.md).
- Test observable contracts at the narrowest useful boundary, then exercise the changed runtime surface. See [testing guidelines](docs/guidelines/testing.md).
- Use the checked-in Gradle wrapper. `clean check` is the repository-wide gate.

## Current active increments

- [MEM-12 — Production local-Keycloak member invitation](docs/increments/active/mem-12-local-keycloak-member-invitation/design.md)
- [MEM-15 — Organization invitation data view](docs/increments/active/mem-15-invitation-data-view/design.md)

Keep each increment's design, plan, verification evidence, and Linear scope aligned while implementation is in flight.

## Canonical references

- [Vision](docs/vision.md)
- [Architecture](ARCHITECTURE.md)
- [Roadmap](docs/roadmap.md)
- [Conventions](docs/conventions.md)
- [Operating model](docs/guidelines/operating-model.md)
- [Persistence policy](docs/guidelines/persistence.md)
- [Shared identity runtime decision](docs/decisions/0004-memoryos-owned-shared-identity-runtime.md)
- [Shared runtime migration runbook](docs/runbooks/shared-runtime-migration.md)
- [Identity contract](docs/specs/identity.md)
- [Identity verification matrix](docs/tests/identity.md)
- [Organization contract](docs/specs/organization.md)
- [Organization verification matrix](docs/tests/organization.md)
- [Invitation contract](docs/specs/invitation.md)
- [Invitation verification matrix](docs/tests/invitation.md)
