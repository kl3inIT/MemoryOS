# MemoryOS roadmap

This roadmap records delivery state at increment granularity. Linear is the execution tracker; this file is the repository-facing state and link map. Last reconciled: 2026-08-18.

## Delivered

| Increment | Outcome | Evidence |
| --- | --- | --- |
| MEM-5 | Controlled Spring Modulith foundation with separate API and worker deployables | [ADR 0001](decisions/0001-controlled-modular-monolith.md) |
| MEM-6 | Dedicated `memoryos` realm and PKCE client on shared Keycloak | [Runtime runbook](runbooks/development-runtime.md) |

## Active

| Increment | State | Repository record | External tracker |
| --- | --- | --- | --- |
| MEM-7 | In progress; pull request open | [Design](increments/active/mem-7-persist-actors-and-external-identity-bindings/design.md) · [Plan](increments/active/mem-7-persist-actors-and-external-identity-bindings/plan.md) | [Linear](https://linear.app/memory-os/issue/MEM-7/persist-actors-and-external-identity-bindings) · [PR #3](https://github.com/kl3inIT/MemoryOS/pull/3) |

## Candidate increments

These are sequencing signals, not commitments or approved designs.

1. Authorized runtime account linking with proof of possession, transaction boundaries, and audit evidence.
2. First actor-owned knowledge persistence and ingestion slice.
3. Authorization policy integration driven by a concrete knowledge operation.
4. Retrieval over persisted actor-owned knowledge.
5. Durable worker loop introduced with its first owned job type.

Create or link an issue and add an active increment record before implementation begins.
