# Invitation capability contract

## Purpose

The invitation capability owns the production lifecycle that authorizes one verified external identity to receive fixed membership in the existing Organization. It is a top-level closed Spring Modulith capability, not an Organization subpackage.

Its allowed dependency direction is:

```text
invitation -> identity
invitation -> organization
```

Identity and organization never depend on invitation. Invitation persistence owns only invitation tables and never writes identity bindings or Organization membership tables directly.

## Owner lifecycle

An active Organization `OWNER` may:

- create one invitation for one normalized email;
- list bounded invitation lifecycle metadata with optional status/email filtering and allowlisted sorting;
- rotate a pending invitation, returning a replacement plaintext secret once;
- revoke a pending invitation.

Organization supplies durable owner authority through a narrow public port. Clients cannot choose an Organization or role grant. Acceptance always grants Organization `MEMBER`.

## Secret contract

A new or rotated invitation uses 32 bytes from `SecureRandom`, encoded as an unpadded URL-safe value. The API returns the plaintext only in that create/rotate response. PostgreSQL stores only its SHA-256 digest.

Plaintext secrets are absent from list responses, logs, JDBC sessions, exceptions, and lifecycle rows. Rotation atomically replaces the digest and invalidates every previous link. Revocation makes the current digest unavailable.

## Lifecycle and persistence

Stored states are `PENDING`, `ACCEPTED`, `EXPIRED`, and `REVOKED`. Expiry is settled from durable `expires_at` without a background runtime mode. A database constraint permits at most one pending invitation for one normalized email in one Organization while preserving settled lifecycle evidence.

The invitation row records Organization scope, normalized email, secret digest, creator, expiry, and accepted or revoked lifecycle facts. Foreign keys keep lifecycle rows inside one Organization and reference stable actors for creator/consumer evidence.

## Invitation history query

The owner list is Organization-scoped by durable authority; clients never submit an Organization selector. `GET /api/invitations` accepts optional lifecycle status and normalized-email filters, an allowlisted invitation sort, a zero-based page, and a bounded page size. Defaults are newest-first, page `0`, and size `20`; the maximum size is `100`.

The response contains invitation items plus page, size, total-item, and total-page metadata. Count and selection use the same filter after pending expiry is settled in the same transaction. Every order appends invitation ID as a deterministic tie-breaker. Invalid negative/bounded input returns the RFC 9457 validation contract; a valid page beyond the result is empty with unchanged totals.

Invitation administration deliberately uses offset pagination because operators need filters, totals, and numbered pages over bounded lifecycle history. Future high-churn append feeds such as audit use cursor pagination under their own contract.

## HTTP lifecycle commands

Rotation and revocation are durable state transitions, not resource deletion:

```text
POST /api/invitations/{invitationId}/rotate
POST /api/invitations/{invitationId}/revoke
```

Both preserve the invitation history resource. Revocation returns `204 No Content`; the obsolete DELETE-shaped route does not exist. OpenAPI groups invitation operations under the stable consumer-facing `Invitations` tag and current identity under `Identity`, never generated controller class names.

## Intake continuation

`GET /invite/{secret}` hashes and reads the matching invitation. Missing, expired, revoked, consumed, or superseded secrets return the not-available flow. A valid intake stores only redacted continuation state in the JDBC-backed browser session and redirects to the invitation landing surface. Intake and current-continuation resolution do not lock the row; acceptance revalidates availability under the lifecycle lock before any authority write.

The continuation contains only invitation ID, Organization ID, and expiry. It never contains the plaintext secret or a parallel invitation nonce; Spring Security owns OAuth2 state and OIDC nonce correlation. Intake and current-continuation responses use `Cache-Control: no-store`; intake also uses `Referrer-Policy: no-referrer`. Failed intake removes any prior continuation from the browser session.

`GET /invite/activate` is the fixed post-Keycloak-action entry point. It stores only a non-correlating activation-flow marker, removes any stale capability-link continuation, applies no-store/no-referrer headers, and immediately starts `/oauth2/authorization/memoryos`.

## Acceptance transaction

An identity may be provisioned from either a valid capability-link continuation or the activation-email callback. The latter accepts only an unbound identity or bound Actor without active Organization authority when all of these hold:

- exact configured issuer and nonblank subject;
- verified email exactly matching one normalized pending invitation email;
- exactly one pending unexpired invitation match;
- active Organization;
- invitation still pending and unexpired under a row lock;
- no conflicting existing membership or identity ownership.

One transaction calls the Identity-owned mandatory binding-and-lock port, calls the Organization-owned mandatory membership port, and conditionally accepts the Invitation-owned row. Actor binding, Organization `MEMBER`, and invitation consumption commit or roll back together. The Invitation row lock rejects replay of one invitation; the stable Actor row lock serializes different invitations that target the same external identity. Capability-link acceptance uses the redacted continuation; activation-email acceptance uses owner-approved pending state plus exact provider-verified mailbox ownership and sends no invitation correlation through Keycloak.

After commit, the browser flow rotates the session ID and persists only the existing `ActorId` application principal. Provider access tokens, refresh tokens, raw ID tokens, and authorized-client state are discarded. Every failed partial flow invalidates its session.

## Failure contract

Invitation exposes expected REST failures through capability-prefixed codes: `INVITATION_NOT_OWNER`, `INVITATION_INVALID_EMAIL`, `INVITATION_CONFLICT`, `INVITATION_NOT_AVAILABLE`, `INVITATION_EMAIL_NOT_VERIFIED`, `INVITATION_EMAIL_MISMATCH`, and `INVITATION_IDENTITY_CONFLICT`. Each code carries one semantic failure category and safe English fallback message. The API renders these as RFC 9457 Problem Details and never exposes the diagnostic `InvitationException` message.

Browser intake and OAuth flows continue to catch `InvitationException` directly and translate selected reasons to redirect recovery states.

## Product surface

The owner uses `Admin Panel` → `Invitations` at `/admin/invitations` to create, filter, sort, page, rotate, and revoke invitation lifecycle records. The browser shows and mounts this surface only when `/api/identity/me` projects `INVITATIONS_MANAGE`; role names are not behavior gates. A member deep link preserves its URL, renders access denied, and issues no invitation request. This projection is presentation only: create/list/rotate/revoke still resolve active durable Organization-owner membership on every request and return `INVITATION_NOT_OWNER` otherwise. TanStack Router search parameters are the canonical owner view state; the generated query key includes status, email, sort, page, and size.

Invitation creation is one semantic single-flight operation. For a new local-Keycloak recipient it synchronously provisions the exact provider account and Keycloak sends a bounded activation email. For an exact existing verified account, the recipient accepts on normal login without forced password reset. The create response reports the observable delivery outcome and always returns the one-time copy/share link as recovery. Rotation replaces only that recovery capability; revoke plus re-invite sends a fresh activation email for a MemoryOS-created unverified account.

Plaintext secrets remain absent from MemoryOS persistence, logs, exceptions, and JDBC sessions. Keycloak action emails contain no invitation secret or identifier. Staging Mailpit is the observable activation-delivery boundary; public-provider deliverability remains excluded.

## Exclusions

The capability does not implement bulk invitation, multi-Organization switching, owner transfer, role pickers, billing/seat logic, SCIM, group provisioning, domain JIT access, inactive tenant mappings, or a generic audit module.
