# MEM-59 — Tasco browser JIT admission

## Status

Implemented on `feat/mem-59-tasco-jit`; targeted tests and repository `clean check` passed. The actual Spring HTTP/OIDC callback was exercised with a signed synthetic provider and isolated PostgreSQL. Live Keycloak mapper replay, two-Keycloak simulator acceptance, and actual Tasco acceptance remain separate pending rollout gates. See [verification](verification.md).

## Goal and trust boundary

Admit a trusted Tasco-brokered browser identity into the single deployment-owned Tenant without manufacturing or consuming an invitation. Keycloak remains the only trusted OIDC issuer and credential/broker plane; MemoryOS PostgreSQL remains the authority for Actors, memberships, and Groups.

The browser callback reads `memoryos_identity_provider` directly from the validated ID token and accepts only a strict String that exactly matches a deployment allowlist entry. `memoryos.identity.jit.allowed-provider-aliases` is supplied by optional `MEMORYOS_JIT_ALLOWED_PROVIDER_ALIASES` and defaults to empty. Tasco requires explicit `tasco` opt-in; a provider alias, email domain, role, scope, request parameter, UserInfo claim, or upstream issuer alone never establishes trust.

The realm reconciliation adds a `User Session Note` protocol mapper on `memoryos-web`: Keycloak session note `identity_provider` becomes String ID-token claim `memoryos_identity_provider`. Access-token, UserInfo, introspection, and token-response emission are disabled. The existing mapper upsert handles creation, drift correction, and unchanged replay. This change neither creates nor edits upstream providers, other clients' mappers, broker linking flows, or user attributes.

## Admission order

1. Preserve admission of an already active member of the active configured Tenant.
2. Without active membership, a trusted ID-token provider selects the IAM JIT transaction. An inactive or otherwise ineligible membership is rejected, never reactivated or elevated.
3. Otherwise retain the existing eligible invitation path and its verified-email requirements. Missing/untrusted/malformed provider claims confer no JIT permission.
4. Denied admission produces `ACCESS_NOT_PROVISIONED` without a durable authenticated application session.

JIT uses the exact, case-sensitive Keycloak `(issuer, sub)` binding. It creates or reuses a `STANDARD` Actor, grants only active Tenant `MEMBER` and non-manager Basic membership, and never links by email. Email and `email_verified` remain profile observations, not a JIT gate. Two identities with the same email remain distinct unless an exact binding already establishes their Actor relationship.

## Transaction and concurrency

IAM application code owns one transaction; concrete IAM persistence owns row locks, SQL, and lifecycle writes. The transaction locks the configured `MEMORYOS_TENANT_ID` Tenant before the stable Actor, verifies the Tenant is active, resolves/creates the exact Actor binding, rejects existing inactive or incompatible membership, and provisions membership plus Basic atomically. Failed provisioning rolls back all new authority and binding state. The Tenant lock serializes concurrent first admission of the same identity before either can create a binding. Repeat/concurrent admission reuses one Actor and membership without privilege elevation or invitation changes.

Spring Security applies its existing session-fixation protection before the success handler. After successful admission, the callback records the latest profile against the exact binding and persists only `ActorId` in the application principal. Provider access, refresh, and raw ID-token state are discarded, never stored in Spring Session or profile persistence. Bearer authentication remains resolve-only and never invokes JIT, including tokens carrying the provider claim. Normal active-member login bypasses JIT; direct or concurrent admission replay preserves memberships/Groups but uses the existing exclusive Tenant lock, which advances the authorization revision on commit.

## Non-goals

- No fake invitation, invitation mutation, pending-approval workflow, email-based account linking, or provider-derived administrative grant.
- No dynamic provider configuration UI/API, multi-Tenant selection, new public endpoint, schema compatibility path, or provider-token session state.
- No upstream provider changes or claim that an existing Tasco integration is accepted by simulator evidence.
- No upstream/Keycloak revocation propagation, introspection, back-channel logout, or absolute browser-session lifetime; MEM-68 and MEM-69 remain separate.

## Verification and durable references

The [plan](plan.md) records pending gates. Canonical behavior belongs in the [identity contract](../../../specs/identity.md), [identity matrix](../../../tests/identity.md), [runtime runbook](../../../runbooks/development-runtime.md), and [architecture](../../../../ARCHITECTURE.md). Simulator verification must establish application and broker behavior; actual Tasco acceptance additionally requires the real approved provider and account flow. Neither substitutes for the other.
