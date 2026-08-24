# MemoryOS roadmap

This roadmap records delivery state at increment granularity. Linear is the execution tracker; this file is the repository-facing state and link map. Last reconciled: 2026-08-24.

## Delivered

| Increment | Outcome | Evidence |
| --- | --- | --- |
| MEM-5 | Controlled Spring Modulith foundation with separate API and worker deployables | [ADR 0001](decisions/0001-controlled-modular-monolith.md) |
| MEM-6 | Dedicated `memoryos` realm and public PKCE client on shared Keycloak | [Runtime runbook](runbooks/development-runtime.md) |
| MEM-7 | PostgreSQL-backed actors and exact external-identity resolution | [Design](increments/completed/mem-7-persist-actors-and-external-identity-bindings/design.md) · [Verification](increments/completed/mem-7-persist-actors-and-external-identity-bindings/verification.md) · [PR #4](https://github.com/kl3inIT/MemoryOS/pull/4) |
| MEM-8 | Deployment-configured singleton Organization bootstrap and initial-owner Authorization Code + PKCE browser session, with active-membership admission and live unprovisioned-user denial | [Design](increments/completed/mem-8-organization-workspace-browser-onboarding/design.md) · [Verification](increments/completed/mem-8-organization-workspace-browser-onboarding/verification.md) · [PR #6](https://github.com/kl3inIT/MemoryOS/pull/6) |
| MEM-13 | Production Vite/React web foundation and authenticated owner shell over the Spring-owned browser session | [Design](increments/completed/mem-13-production-web-foundation/design.md) · [Verification](increments/completed/mem-13-production-web-foundation/verification.md) · [PR #8](https://github.com/kl3inIT/MemoryOS/pull/8) |
| MEM-14 | Responsive authenticated `New Session` and administration shells aligned with the OrgMemory/Onyx interaction contract | [Design](increments/completed/mem-14-orgmemory-owner-shell/design.md) · [Verification](increments/completed/mem-14-orgmemory-owner-shell/verification.md) · [PR #11](https://github.com/kl3inIT/MemoryOS/pull/11) |
| MEM-16 | Spring Boot 4-native RFC 9457 contract for framework errors and expected capability failures | [Design](increments/completed/mem-16-api-problem-details/design.md) · [Verification](increments/completed/mem-16-api-problem-details/verification.md) · [PR #14](https://github.com/kl3inIT/MemoryOS/pull/14) |
| MEM-18 | Deterministic committed OpenAPI snapshot generated from the live Spring MVC browser API, with backend and Hey API drift gates | [Design](increments/completed/mem-18-backend-generated-openapi/design.md) · [Verification](increments/completed/mem-18-backend-generated-openapi/verification.md) · [PR #16](https://github.com/kl3inIT/MemoryOS/pull/16) |
| MEM-20 | MemoryOS-owned PostgreSQL with isolated application/Keycloak databases, one shared Keycloak runtime, and separated Infisical development/staging environments | [Design](increments/completed/mem-20-shared-keycloak-postgres/design.md) · [Verification](increments/completed/mem-20-shared-keycloak-postgres/verification.md) · [ADR 0004](decisions/0004-memoryos-owned-shared-identity-runtime.md) · [PR #25](https://github.com/kl3inIT/MemoryOS/pull/25) · [PR #26](https://github.com/kl3inIT/MemoryOS/pull/26) |

## Active

| Increment | Outcome | Evidence |
| --- | --- | --- |
| [MEM-12](https://linear.app/memory-os/issue/MEM-12/onboard-one-member-by-local-keycloak-invitation) | Production owner-to-member invitation lifecycle through local Keycloak, with copy/share recovery, fixed memberships, and atomic acceptance | [Design](increments/active/mem-12-local-keycloak-member-invitation/design.md) · [Plan](increments/active/mem-12-local-keycloak-member-invitation/plan.md) · [Verification](increments/active/mem-12-local-keycloak-member-invitation/verification.md) |

## Candidate increments

These are sequencing signals, not commitments or approved designs.

1. [MEM-9](https://linear.app/memory-os/issue/MEM-9/authorize-workspace-owned-google-drive-connections) — authorize Workspace-owned Google Drive connections.
2. [MEM-10](https://linear.app/memory-os/issue/MEM-10/ingest-workspace-scoped-google-docs-with-source-acls) — ingest Workspace-scoped Google Docs with source ACL evidence.
3. [MEM-11](https://linear.app/memory-os/issue/MEM-11/answer-from-authorized-evidence-with-verifiable-citations) — answer from authorized evidence with verifiable citations.

Create or link an issue and add an active increment record before implementation begins.