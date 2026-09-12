# MEM-84 — Architecture and documentation synchronization

## Outcome

Keep one coherent documentation system for the implemented MemoryOS architecture, accepted decisions, capability contracts, verification evidence, active work, and operating procedures. Linear provides the concise team-facing reading set; repository documents remain classified by purpose and must not duplicate obsolete reference-product research.

## Scope

- Reconcile `README.md`, `ARCHITECTURE.md`, roadmap, conventions, guidelines, ADRs, specs, tests, runbooks, and increment lifecycle.
- Remove obsolete external-reference material and superseded comparison-only documents while retaining MemoryOS decisions and verification evidence.
- Repair links broken by moving delivered increments from `active/` to `completed/`.
- Keep an increment in exactly one lifecycle directory. MEM-79 remains active while its Linear issue is In Review.
- Add Mermaid views to the repository architecture document.
- Preserve application code, runtime configuration, and unrelated in-flight changes.

## Canonical ownership

| Knowledge | Canonical location |
| --- | --- |
| Implemented structure and runtime flow | `ARCHITECTURE.md` |
| Product direction | `docs/vision.md` |
| Delivered, active, candidate work | `docs/roadmap.md` |
| Cross-cutting engineering policy | `docs/conventions.md`, `docs/guidelines/` |
| Accepted decision and rationale | `docs/decisions/` |
| Durable capability behavior | `docs/specs/` |
| Verification coverage and evidence | `docs/tests/` |
| Change-local scope and reasoning | `docs/increments/active/` |
| Historical delivered evidence | `docs/increments/completed/` |
| Operator procedures | `docs/runbooks/` |

## Safety boundary

This audit does not change code, schemas, deployment state, Linear issue state, or acceptance claims. Historical evidence is retained unless it exists only to compare against the removed reference product or duplicates a canonical current document.
