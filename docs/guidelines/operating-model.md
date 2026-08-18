# Repository operating model

MemoryOS uses the repository as its durable system of record. The model is adapted from [glebfox/playbook — Repository as System of Record](https://github.com/glebfox/playbook/blob/main/harness/operating-model.md).

## Goals

- A fresh session can find current state without reconstructing chat history.
- Each fact has one canonical home.
- Readers start from a small map and disclose detail only as needed.
- Code, design intent, decisions, and verification move together.

## Document layout

| Location | Owns |
| --- | --- |
| `AGENTS.md` | Canonical thin navigation map and mandatory workflow rules |
| `CLAUDE.md` | Claude Code import of `AGENTS.md`; no duplicate rules |
| `README.md` | Public project entry points and basic commands |
| `ARCHITECTURE.md` | Current implemented system structure and runtime flows |
| `docs/vision.md` | Product outcomes and stable principles |
| `docs/roadmap.md` | Delivered, active, and candidate increments |
| `docs/conventions.md` | Cross-cutting engineering conventions |
| `docs/guidelines/` | Reusable operational and engineering policy |
| `docs/specs/` | Current capability behavior and invariants |
| `docs/tests/` | Requirement-to-verification matrices |
| `docs/decisions/` | Accepted, append-only architecture decisions |
| `docs/increments/active/<increment>/` | In-flight design, plan, and verification evidence |
| `docs/increments/completed/<increment>/` | Historical increment record after merge |
| `docs/runbooks/` | Repeatable operator/developer procedures |

## Source classification

Before adding text, choose its owner:

- **Implemented fact:** architecture or capability spec.
- **Desired outcome:** vision.
- **Reusable rule:** convention or guideline.
- **Accepted tradeoff:** ADR.
- **Current change detail:** active increment.
- **Repeatable command sequence:** runbook.
- **Task status:** roadmap summary plus external tracker link.

If the same statement would appear twice, keep the detailed version in the deeper source and replace the other copy with a link.

## Increment lifecycle

1. Create `docs/increments/active/<increment>/design.md` and `plan.md` before non-trivial implementation.
2. Define the user-visible or operator-visible outcome, boundaries, data lifecycle, failure behavior, and verification plan.
3. Implement a complete vertical slice. Do not make incomplete behavior operable through temporary production code.
4. Record commands and observed outcomes in `verification.md`; do not copy raw logs or secrets.
5. Update architecture, specs, test matrices, guidelines, and ADRs to reflect the verified implementation.
6. Merge the pull request.
7. Move the increment directory to `docs/increments/completed/` and reconcile `docs/roadmap.md`.

An abandoned increment remains historical only if its reasoning is reusable; otherwise delete the change-local record. Durable accepted decisions remain in ADRs.

## Decision lifecycle

Evaluate alternatives in the active increment's `design.md`. Create an ADR only after the decision is accepted and implementation has started. ADRs are append-only; a later ADR supersedes an earlier one and links both directions.

## Verification and consolidation

Verification must exercise the changed surface, not only compile it. The active increment owns temporary evidence while in flight. Before completion, consolidate stable contracts into `docs/specs/`, stable checks into `docs/tests/`, current shape into `ARCHITECTURE.md`, and reusable operations into `docs/runbooks/`.

The repository documents win over stale chat or tracker summaries. Current runtime and test evidence wins over stale repository prose; correct the prose in the same change.
