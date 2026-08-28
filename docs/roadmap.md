# MemoryOS roadmap

This roadmap records delivery state at increment granularity. Linear is the execution tracker; this file is the repository-facing state and link map. Last reconciled: 2026-08-28.

## Delivered

| Increment | Outcome | Evidence |
| --- | --- | --- |
| MEM-5 | Controlled Spring Modulith foundation with separate API and worker deployables | [ADR 0001](decisions/0001-controlled-modular-monolith.md) |
| MEM-6 | Dedicated `memoryos` realm and public PKCE client on shared Keycloak | [Runtime runbook](runbooks/development-runtime.md) |
| MEM-7 | PostgreSQL-backed actors and exact external-identity resolution | [Design](increments/completed/mem-7-persist-actors-and-external-identity-bindings/design.md) · [Verification](increments/completed/mem-7-persist-actors-and-external-identity-bindings/verification.md) · [PR #4](https://github.com/kl3inIT/MemoryOS/pull/4) |
| MEM-8 | Deployment-configured singleton Organization bootstrap and initial-owner Authorization Code + PKCE browser session, with active-membership admission and live unprovisioned-user denial | [Design](increments/completed/mem-8-organization-workspace-browser-onboarding/design.md) · [Verification](increments/completed/mem-8-organization-workspace-browser-onboarding/verification.md) · [PR #6](https://github.com/kl3inIT/MemoryOS/pull/6) |
| MEM-12 | Production owner-to-member invitation lifecycle through local Keycloak, with copy/share recovery, fixed memberships, and atomic acceptance | [Design](increments/completed/mem-12-local-keycloak-member-invitation/design.md) · [Verification](increments/completed/mem-12-local-keycloak-member-invitation/verification.md) · [PR #32](https://github.com/kl3inIT/MemoryOS/pull/32) · [PR #37](https://github.com/kl3inIT/MemoryOS/pull/37) |
| MEM-13 | Production Vite/React web foundation and authenticated owner shell over the Spring-owned browser session | [Design](increments/completed/mem-13-production-web-foundation/design.md) · [Verification](increments/completed/mem-13-production-web-foundation/verification.md) · [PR #8](https://github.com/kl3inIT/MemoryOS/pull/8) |
| MEM-14 | Responsive authenticated `New Session` and administration shells aligned with the OrgMemory/Onyx interaction contract | [Design](increments/completed/mem-14-orgmemory-owner-shell/design.md) · [Verification](increments/completed/mem-14-orgmemory-owner-shell/verification.md) · [PR #11](https://github.com/kl3inIT/MemoryOS/pull/11) |
| MEM-15 | Bounded, filterable, sortable, URL-addressable Organization invitation administration with an accessible TanStack Table | [Design](increments/completed/mem-15-invitation-data-view/design.md) · [Verification](increments/completed/mem-15-invitation-data-view/verification.md) · [PR #29](https://github.com/kl3inIT/MemoryOS/pull/29) · [PR #30](https://github.com/kl3inIT/MemoryOS/pull/30) |
| MEM-16 | Spring Boot 4-native RFC 9457 contract for framework errors and expected capability failures | [Design](increments/completed/mem-16-api-problem-details/design.md) · [Verification](increments/completed/mem-16-api-problem-details/verification.md) · [PR #14](https://github.com/kl3inIT/MemoryOS/pull/14) |
| MEM-18 | Deterministic committed OpenAPI snapshot generated from the live Spring MVC browser API, with backend and Hey API drift gates | [Design](increments/completed/mem-18-backend-generated-openapi/design.md) · [Verification](increments/completed/mem-18-backend-generated-openapi/verification.md) · [PR #16](https://github.com/kl3inIT/MemoryOS/pull/16) |
| MEM-19 | Server-authored Organization invitation authority projection with capability-aware owner/member browser surfaces | [Design](increments/completed/mem-19-frontend-authority-projection/design.md) · [Verification](increments/completed/mem-19-frontend-authority-projection/verification.md) · [PR #32](https://github.com/kl3inIT/MemoryOS/pull/32) |
| MEM-20 | MemoryOS-owned PostgreSQL with isolated application/Keycloak databases, one shared Keycloak runtime, and separated Infisical development/staging environments | [Design](increments/completed/mem-20-shared-keycloak-postgres/design.md) · [Verification](increments/completed/mem-20-shared-keycloak-postgres/verification.md) · [ADR 0004](decisions/0004-memoryos-owned-shared-identity-runtime.md) · [PR #25](https://github.com/kl3inIT/MemoryOS/pull/25) · [PR #26](https://github.com/kl3inIT/MemoryOS/pull/26) |
| MEM-28 | Organization-only membership, invitation, and future resource ownership with the historical Workspace layer removed | [Design](increments/completed/mem-28-organization-only-ownership/design.md) · [Verification](increments/completed/mem-28-organization-only-ownership/verification.md) · [PR #32](https://github.com/kl3inIT/MemoryOS/pull/32) · [PR #33](https://github.com/kl3inIT/MemoryOS/pull/33) |

| MEM-29–33 | Frontend lifecycle hardening across client navigation, persistent authenticated layouts, browser-session query convergence, single-flight invitation creation, and CSP-compatible font assets | [Design](increments/completed/mem-29-33-frontend-lifecycle-hardening/design.md) · [Verification](increments/completed/mem-29-33-frontend-lifecycle-hardening/verification.md) · [PR #35](https://github.com/kl3inIT/MemoryOS/pull/35) |
| MEM-34 | Pre-provisioned local-Keycloak invitees with bounded activation email, exact verified-email acceptance, and recovery-link fallback | [Design](increments/completed/mem-34-keycloak-invited-user-activation/design.md) · [Verification](increments/completed/mem-34-keycloak-invited-user-activation/verification.md) · [PR #36](https://github.com/kl3inIT/MemoryOS/pull/36) |
| MEM-37 | Command-style invitation revocation and consumer-facing Identity/Invitations OpenAPI taxonomy | [Design](increments/completed/mem-37-command-style-invitation-revoke/design.md) · [Verification](increments/completed/mem-37-command-style-invitation-revoke/verification.md) · [PR #38](https://github.com/kl3inIT/MemoryOS/pull/38) |
| MEM-27 | Onyx/Opal-informed semantic interaction tokens, action prominence, shared control sizes, and enforced disabled/pending behavior across the production frontend | [Design](increments/completed/mem-27-frontend-interaction-contracts/design.md) · [Verification](increments/completed/mem-27-frontend-interaction-contracts/verification.md) · [PR #40](https://github.com/kl3inIT/MemoryOS/pull/40) |

## Active

| Increment | Outcome | Evidence |
| --- | --- | --- |
| [MEM-38](https://linear.app/memory-os/issue/MEM-38/standardize-destructive-confirmations-and-async-feedback) | Standardize accessible destructive confirmations, async pending behavior, and safe source mutation feedback before the Sources redesign | [Design](increments/active/mem-38-destructive-confirmations/design.md) · [Plan](increments/active/mem-38-destructive-confirmations/plan.md) · [Verification](increments/active/mem-38-destructive-confirmations/verification.md) |
| [MEM-35](https://linear.app/memory-os/issue/MEM-35/establish-organization-owned-file-connectors-and-document-boundary) | Establish the Onyx-aligned Organization-owned Connector/Credential/Pair/Document boundary through a production FILE source and persistence-backed indexing worker | [Design](increments/active/mem-35-file-connectors/design.md) · [Plan](increments/active/mem-35-file-connectors/plan.md) · [Verification](increments/active/mem-35-file-connectors/verification.md) |

## Candidate increments

These are sequencing signals, not commitments or approved designs.

1. [MEM-9](https://linear.app/memory-os/issue/MEM-9/authorize-organization-owned-google-drive-connections) — authorize Organization-owned Google Drive connections.
2. [MEM-10](https://linear.app/memory-os/issue/MEM-10/ingest-organization-scoped-google-docs-with-source-acls) — ingest Organization-scoped Google Docs with source ACL evidence.
3. [MEM-11](https://linear.app/memory-os/issue/MEM-11/answer-from-authorized-evidence-with-verifiable-citations) — answer from authorized evidence with verifiable citations.

Create or link an issue and add an active increment record before implementation begins.