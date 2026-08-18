# MemoryOS roadmap

This roadmap records delivery state at increment granularity. Linear is the execution tracker; this file is the repository-facing state and link map. Last reconciled: 2026-08-18.

## Delivered

| Increment | Outcome | Evidence |
| --- | --- | --- |
| MEM-5 | Controlled Spring Modulith foundation with separate API and worker deployables | [ADR 0001](decisions/0001-controlled-modular-monolith.md) |
| MEM-6 | Dedicated `memoryos` realm and PKCE client on shared Keycloak | [Runtime runbook](runbooks/development-runtime.md) |
| MEM-7 | PostgreSQL-backed actors and exact external-identity resolution | [Design](increments/completed/mem-7-persist-actors-and-external-identity-bindings/design.md) · [Verification](increments/completed/mem-7-persist-actors-and-external-identity-bindings/verification.md) · [PR #4](https://github.com/kl3inIT/MemoryOS/pull/4) |

## Active

No active increments.

## Candidate increments

These are sequencing signals, not commitments or approved designs.

1. Authorized runtime account linking with proof of possession, transaction boundaries, and audit evidence.
2. First actor-owned knowledge persistence and ingestion slice.
3. Authorization policy integration driven by a concrete knowledge operation.
4. Retrieval over persisted actor-owned knowledge.
5. Durable worker loop introduced with its first owned job type.

Create or link an issue and add an active increment record before implementation begins.
