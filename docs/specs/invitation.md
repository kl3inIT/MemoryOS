# Invitation capability contract

## Purpose

The invitation capability owns the production lifecycle that authorizes one verified external identity to receive fixed membership in the existing Organization and default Workspace. It is a top-level closed Spring Modulith capability, not an Organization subpackage.

Its allowed dependency direction is:

```text
invitation -> identity
invitation -> organization
```

Identity and organization never depend on invitation. Invitation persistence owns only invitation tables and never writes identity bindings or Organization membership tables directly.

## Owner lifecycle

An active Organization `OWNER` may:

- create one invitation for one normalized email;
- list invitation lifecycle metadata;
- rotate a pending invitation, returning a replacement plaintext secret once;
- revoke a pending invitation.

Organization supplies the owner authority and default Workspace through a narrow public port. Clients cannot choose an Organization role or Workspace grant. Acceptance always grants Organization `MEMBER` and default-Workspace `MEMBER`.

## Secret contract

A new or rotated invitation uses 32 bytes from `SecureRandom`, encoded as an unpadded URL-safe value. The API returns the plaintext only in that create/rotate response. PostgreSQL stores only its SHA-256 digest.

Plaintext secrets are absent from list responses, logs, JDBC sessions, exceptions, and lifecycle rows. Rotation atomically replaces the digest and invalidates every previous link. Revocation makes the current digest unavailable.

## Lifecycle and persistence

Stored states are `PENDING`, `ACCEPTED`, `EXPIRED`, and `REVOKED`. Expiry is settled from durable `expires_at` without a background runtime mode. A database constraint permits at most one pending invitation for one normalized email in one Organization while preserving settled lifecycle evidence.

The invitation row records Organization/default-Workspace scope, normalized email, secret digest, creator, expiry, and accepted or revoked lifecycle facts. Foreign keys prevent cross-Organization Workspace grants and reference stable actors for creator/consumer evidence.

## Intake continuation

`GET /invite/{secret}` hashes and locks the matching invitation. Missing, expired, revoked, consumed, or superseded secrets return the not-available flow. A valid intake stores only redacted continuation state in the JDBC-backed browser session and redirects to the invitation landing surface.

The continuation contains only invitation ID, Organization ID, and expiry. It never contains the plaintext secret or a parallel invitation nonce; Spring Security owns OAuth2 state and OIDC nonce correlation. Intake responses use `Cache-Control: no-store` and `Referrer-Policy: no-referrer`.

## Acceptance transaction

An unbound browser identity may be provisioned only from a valid invitation continuation. Acceptance requires:

- exact configured issuer and nonblank subject;
- verified email exactly matching the normalized invitation email;
- active Organization and default Workspace;
- invitation still pending and unexpired under a row lock;
- no conflicting existing membership or identity ownership.

One transaction calls the Identity-owned mandatory binding port, calls the Organization-owned mandatory membership port, and conditionally accepts the Invitation-owned row. Actor binding, Organization `MEMBER`, default-Workspace `MEMBER`, and invitation consumption commit or roll back together. Concurrent or replayed callbacks leave exactly one accepted result.

After commit, the browser flow rotates the session ID and persists only the existing `ActorId` application principal. Provider access tokens, refresh tokens, raw ID tokens, and authorized-client state are discarded. Every failed partial flow invalidates its session.

## Product surface

The owner uses `Admin Panel` → `People` to create, list, rotate, and revoke invitations. Create and rotate expose an immediate copy/share link. Recipient surfaces identify the workspace, lead into local Keycloak sign-in/account creation, and provide plain-language recovery for unavailable, unverified, or mismatched invitations.

Copy/share is the complete delivery contract. Email delivery is not implemented without a concrete provider and observable production failure behavior.

## Exclusions

The capability does not implement bulk invitation, multi-Organization switching, owner transfer, role or Workspace pickers, billing/seat logic, SCIM, group provisioning, domain JIT access, inactive tenant mappings, or a generic audit module.
