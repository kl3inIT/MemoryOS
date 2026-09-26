# MemoryOS repository guide

The repository is the system of record. Chat, Linear, pull-request comments and agent memory are inputs; durable project knowledge belongs in the documents linked below. This file is a router: each fact lives in one canonical document.

## Where to look

- Build, run and entry points: [README.md](README.md).
- What exists now (deployables, capability modules, runtime flows, deployment): [ARCHITECTURE.md](ARCHITECTURE.md).
- A capability's contract: `docs/specs/<capability>.md`; its verification matrix: `docs/tests/<capability>.md`.
- Delivered, active and candidate work, with every active increment and its scope: [docs/roadmap.md](docs/roadmap.md#active).
- In-flight change: its increment under `docs/increments/active/<name>/` (`design.md`, `plan.md`, optional `verification.md`). Delivered increments are under `docs/increments/completed/`.
- Engineering rules for all code: [docs/conventions.md](docs/conventions.md); topic guidelines under [docs/guidelines/](docs/guidelines/) (persistence, testing, observability, design tokens, operating model).
- Why a decision was made: accepted ADRs under [docs/decisions/](docs/decisions/).
- Product intent: [docs/vision.md](docs/vision.md). Operating procedures: [docs/runbooks/](docs/runbooks/).

## Area guides

- Frontend work in `web/`: follow [web/AGENTS.md](web/AGENTS.md).
- Backend work in `core/`, `api/`, `worker/` or `sources/`: follow [docs/guidelines/backend.md](docs/guidelines/backend.md).

## Operating rules

- Classify knowledge before writing it: current implementation in `ARCHITECTURE.md` or `docs/specs/`; product intent in `docs/vision.md`; cross-cutting engineering policy in `docs/conventions.md` or `docs/guidelines/`; change-local reasoning in the active increment; planned work only in the roadmap or an increment, never as a current fact.
- Reuse before building: inspect project and library components, hooks and framework behavior first ([component and library reuse](docs/conventions.md#component-and-library-reuse), [reference-based design and scope control](docs/conventions.md#reference-based-design-and-scope-control)).
- `core` is capability implementation, not a framework-free domain layer: Spring, `JdbcClient`, transactions and JPA are fine where they reduce real complexity. Forbid dependency inversion violations and speculative layers, not framework use.
- Keep `core` limited to implemented capabilities; never predeclare empty capability or provider packages. The module list and boundaries are in [ARCHITECTURE.md](ARCHITECTURE.md#code-and-capability-boundaries).
- Start non-trivial work with an increment directory containing `design.md` and `plan.md`, and update both as scope changes. Keep design, plan, verification evidence and Linear scope aligned while work is in flight.
- Preserve accepted contracts and scope while delivering production quality from the start; scope control never removes necessary hardening. Do not turn comparative research or speculative improvements into requirements.
- Never ship a temporary runtime mode, one-shot profile, speculative endpoint or unused abstraction to make an incomplete flow operable ([ADR 0002](docs/decisions/0002-no-speculative-operational-surfaces.md)).
- Provider endpoint review preserves the [accepted internal HTTP and trusted model-manager policy](docs/specs/chat-models.md#credentials-and-provider-extension).
- Record an ADR only after the decision is accepted and implementation has started. ADRs are append-only; supersede them with a new ADR.
- After verification, consolidate durable facts into architecture, spec, test and guideline documents in the same change. Keep the increment under `active/` until its pull request merges and its stated acceptance is done; then move it to `completed/`, fix links to it and reconcile the roadmap.
- Test observable contracts at the narrowest useful boundary, then exercise the changed runtime surface ([testing guideline](docs/guidelines/testing.md)).
- Use the checked-in Gradle wrapper; `clean check` is the repository-wide backend gate and `pnpm --dir web check` the web gate.
