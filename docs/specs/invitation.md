# Invitation lifecycle contract

## Purpose

Invitation is the IAM lifecycle that authorizes one verified external identity to receive fixed membership in the existing Tenant. Under [ADR 0007](../decisions/0007-unified-jpa-iam-and-group-authorization.md), Identity, Tenant membership, invitations, Users and Groups are one closed `io.memoryos.iam` capability. Public identifiers and narrow operation/read contracts remain at the IAM root; entities, repositories, projections and locks stay internal. There are no parallel Identity/Tenant/Invitation persistence modules or cross-module lifecycle writers.

## Administration

Current global `USERS_MANAGE` is required to create, list, rotate or revoke invitations. Authority is resolved from the administrator's latest active Tenant membership and expanded Group grants; `OWNER` is presentation/bootstrap state, not an alternate authorization path. Scoped managers cannot administer Users or invitations. The historical `INVITATION_NOT_OWNER` failure code is retained for a denied invitation-administration request, but it represents a missing `USERS_MANAGE` capability, not an owner-only gate.

An authorized administrator may:

- create one invitation for one normalized email;
- list bounded invitation lifecycle metadata with optional status/email filtering and allowlisted sorting;
- rotate a pending invitation, returning a replacement plaintext secret once; and
- revoke a pending invitation.

Clients cannot choose a Tenant or role grant. Acceptance always creates an active Tenant `MEMBER` with `STANDARD` Account Type and Basic Group membership; it never grants Admin membership, manager status or administration capabilities.

## Secret contract

A new or rotated invitation uses 32 bytes from `SecureRandom`, encoded as an unpadded URL-safe value. The API returns the plaintext only in that create/rotate response. PostgreSQL stores only its SHA-256 digest.

Plaintext secrets are absent from list responses, logs, browser sessions, exceptions and lifecycle rows. Rotation atomically replaces the digest and invalidates every previous link. Revocation makes the current digest unavailable.

## Lifecycle and persistence

Stored states are `PENDING`, `ACCEPTED`, `EXPIRED` and `REVOKED`. Expiry is settled from durable `expires_at` without a background runtime mode. A database constraint permits at most one pending invitation for one normalized email in one Tenant while preserving settled lifecycle evidence.

`InvitationEntity` and `JpaInvitationRepository` own lifecycle writes under `io.memoryos.iam.persistence`. The invitation row records Tenant scope, normalized email, secret digest, creator, expiry, and accepted or revoked facts; JPA relationships retain the Tenant and Actor evidence. Foreign keys keep rows inside one Tenant. `InvitationQueryRepository` remains a concrete SQL read projection for bounded filtering, counts and paging.

Invitation authority mutations use the IAM Tenant row as their serialization anchor. Issue performs provider provisioning outside the database transaction, then reauthorizes with an exclusive lock before creating the row. Rotate and revoke also reauthorize and lock before changing lifecycle state. List uses a shared authorization lock, settles pending expiry and reads count/page state in one transaction. Provider failure before the write transaction leaves no invitation and does not advance the Tenant authorization revision.

## Invitation history query

The `USERS_MANAGE`-authorized list is Tenant-scoped by durable authority; clients never submit a Tenant selector. `GET /api/invitations` accepts optional lifecycle status and normalized-email filters, an allowlisted invitation sort, a zero-based page and a bounded page size. Defaults are newest-first, page `0` and size `20`; the maximum size is `100`.

The response contains invitation items plus page, size, total-item and total-page metadata. Count and selection use the same filter after pending expiry is settled in the same transaction. Every order appends invitation ID as a deterministic tie-breaker. Invalid negative or oversized input returns the RFC 9457 validation contract; a valid page beyond the result is empty with unchanged totals.

Invitation administration deliberately uses offset pagination because operators need filters, totals and numbered pages over bounded lifecycle history. Future high-churn append feeds such as audit use cursor pagination under their own contract.

## Authoritative Users directory

`GET /api/users` is a global-`USERS_MANAGE` SQL projection over every current Tenant membership (`ACTIVE` and `INACTIVE`) plus eligible `PENDING`, unexpired invitations. It reads the latest durable membership state for each request. Settled invitation history and expired, revoked, accepted or superseded invitations are absent. A pending invitation is also omitted when an existing membership has a verified observed email matching its normalized email.

Membership rows remain one row per `ActorId`; actors that share an email are never merged. A membership with no admitted-login profile observation remains visible with nullable profile fields. Membership rows expose the persisted `STANDARD` account type and current ordered Groups. Invitation rows carry `invitationId`, normalized email, expiry and `INVITED` status; `actorId`, role, account type, Groups, profile issuer, email verification and display name are absent or empty as defined by the wire schema. Directory responses never contain plaintext invitation secrets.

The query accepts optional normalized display-name/email `search`, `status` (`ACTIVE`, `INACTIVE`, `INVITED`), membership `role` (`OWNER`, `MEMBER`) and `groupId`; allowlisted name, email, status or role ascending/descending sort; zero-based `page`; and bounded `size`. Search is at most 200 characters, size defaults to 20 and is limited to 100, and deterministic identity tie-breakers follow every selected sort. `totalItems` and `totalPages` describe the filtered query; `counts.active`, `counts.inactive` and `counts.invited` always describe the unfiltered current directory in the same repeatable-read transaction. A Group filter includes membership rows only and does not turn pending invitations into Group members.

The Users lifecycle commands require global `USERS_MANAGE`; the row Group editor requires global `IAM_ADMIN` and replaces ordinary memberships while preserving system memberships and retained manager flags. Capability-specific Group and Source behavior remains in the IAM authority and [Connector](connector.md) contracts rather than being duplicated here.

`POST /api/users/{actorId}/activate` and `POST /api/users/{actorId}/deactivate` delegate to IAM Tenant membership management. They are idempotent for existing non-owner rows, protect the configured owner and final active `STANDARD` administrator, and preserve Actor, binding, membership, Group and invitation history. The [Tenant lifecycle contract](tenant.md#member-lifecycle) defines reactivation and session behavior.

## HTTP lifecycle commands

Rotation and revocation are durable state transitions, not resource deletion:

```text
POST /api/invitations/{invitationId}/rotate
POST /api/invitations/{invitationId}/revoke
```

Both preserve the invitation history resource. Revocation returns `204 No Content`; the obsolete DELETE-shaped route does not exist. OpenAPI groups invitation operations under the stable consumer-facing `Invitations` tag and current identity under `Identity`, never generated controller class names.

## Intake continuation

`GET /invite/{secret}` hashes and reads the matching invitation. Missing, expired, revoked, consumed or superseded secrets return the not-available flow. A valid intake stores only redacted continuation state in the JDBC-backed browser session and redirects to the invitation landing surface. Intake and current-continuation resolution do not lock the row; acceptance revalidates availability under the lifecycle lock before any authority write.

The continuation contains only invitation ID, Tenant ID and expiry. It never contains the plaintext secret or a parallel invitation nonce; Spring Security owns OAuth2 state and OIDC nonce correlation. Intake and current-continuation responses use `Cache-Control: no-store`; intake also uses `Referrer-Policy: no-referrer`. Failed intake removes any prior continuation from the browser session.

`GET /invite/activate` is the fixed post-Keycloak-action entry point. It stores only a non-correlating activation-flow marker, removes any stale capability-link continuation, applies no-store/no-referrer headers and immediately starts `/oauth2/authorization/memoryos`.

## Acceptance transaction

An identity may be admitted from either a valid capability-link continuation or the activation-email callback. The latter proceeds only for an unbound external identity or a bound Actor without Tenant authority when all of these remain true:

- exact configured issuer and nonblank subject;
- verified email exactly matching one normalized pending invitation email;
- exactly one pending unexpired invitation match;
- active Tenant;
- invitation still pending and unexpired under its lifecycle lock; and
- no conflicting durable membership or identity ownership.

Acceptance first acquires the exclusive Tenant authority lock and then the invitation row lock. It revalidates the active Tenant and invitation after both locks. The IAM identity registry resolves or creates the Actor while holding the stable Actor lock, and the membership provisioner checks that Actor's latest durable membership state. Any existing Tenant membership, including inactive history, is an identity conflict rather than a membership reactivation path.

One transaction persists the exact identity binding, one active Tenant `MEMBER`, one non-manager Basic Group edge and the invitation's accepted state. Those writes commit or roll back together. Basic provisioning cannot change membership activation, assign Admin or set a manager flag. If the invitation reaches `expires_at` after the initial availability check but before `InvitationEntity.accept`, acceptance raises typed `INVITATION_NOT_AVAILABLE` and the new Actor, binding, Tenant membership and Basic edge all roll back. The expired timestamp remains authoritative and a later lifecycle query may settle the row to `EXPIRED`.

The Tenant/invitation locks reject replay of one invitation. The stable Actor lock and latest membership query serialize different invitations targeting the same external identity. Concurrent losers receive typed `INVITATION_NOT_AVAILABLE` or `INVITATION_IDENTITY_CONFLICT` according to the durable state they observe; they never create partial authority.

Capability-link acceptance uses the redacted continuation. Activation-email acceptance uses administrator-approved pending state plus exact provider-verified mailbox ownership and sends no invitation correlation through Keycloak. After commit, the browser flow rotates the session ID and persists only the existing `ActorId` application principal. Provider access tokens, refresh tokens, raw ID tokens and authorized-client state are discarded. Every failed partial flow invalidates its session.

## Failure contract

Invitation exposes expected REST failures through capability-prefixed codes: `INVITATION_NOT_OWNER`, `INVITATION_INVALID_EMAIL`, `INVITATION_CONFLICT`, `INVITATION_NOT_AVAILABLE`, `INVITATION_EMAIL_NOT_VERIFIED`, `INVITATION_EMAIL_MISMATCH` and `INVITATION_IDENTITY_CONFLICT`. `INVITATION_NOT_OWNER` is the legacy wire name for failed `USERS_MANAGE` authorization. Each code otherwise carries one semantic failure category and safe English fallback message. The API renders these as RFC 9457 Problem Details and never exposes the diagnostic `InvitationException` message.

Browser intake and OAuth flows catch `InvitationException` directly and translate selected reasons to redirect recovery states.

## Product surface

The page has one `Users` heading with `Invite member` beside it, followed by status counts, filters and the table. It has no introductory subtitle or second `User directory` heading. Search is labelled `Search users…`; empty results say `No users found`. Membership Role remains distinct from Account Type.

The administrator uses `Admin Panel` → `Users` at `/admin/users`. The table-first surface presents the authoritative current directory, server-driven URL search/status/role/Group/sort/page state and global counts. It creates invitations, exposes one-time copy/share recovery, rotates or revokes eligible pending invitations, and activates or deactivates members. Rows show current Groups and `STANDARD` Account Type. An `IAM_ADMIN` can replace a User's ordinary Groups; system memberships are displayed as protected and are preserved.

The Users surface mounts only when `/api/identity/me` projects global `USERS_MANAGE`; role names are not behavior gates. A caller without that capability preserves a deep-link URL, renders access denied and issues no Users or invitation request. The projection is presentation only: Users, invitation and membership operations still resolve current durable IAM authority on every request.

Deactivation denies the next protected request for both browser and bearer callers. While the Actor-only session remains, identity refresh exposes no active Tenant; a later inactive OIDC admission is rejected and invalidates the partial session. Reactivation preserves the Actor, binding, Basic/ordinary Group memberships and manager flags, so Group-derived authority returns without a role-based grant. A browser whose session was invalidated signs in again.

Invitation creation and rotation are globally single-flight in the browser. For a new local-Keycloak recipient, creation synchronously provisions the exact provider account and Keycloak sends a bounded activation email. For an exact existing verified account, the recipient accepts on normal login without forced password reset. Create and rotate responses transfer the plaintext recovery capability directly into dialog-local one-time state rather than retaining secret-bearing mutation results; closing the dialog clears it. Rotation replaces only that recovery capability; revoke plus re-invite sends a fresh activation email for a MemoryOS-created unverified account.

The invitation lifecycle APIs and public `/invite/{secret}` and `/invite/activate` routes remain production contracts. The former invitation-history administration page is removed without a redirect or compatibility alias.

Plaintext secrets remain absent from MemoryOS persistence, logs, exceptions, browser sessions, directory/list responses and client mutation caches. Keycloak action emails contain no invitation secret or identifier. Staging Mailpit is the observable activation-delivery boundary; public-provider deliverability remains excluded.

## Exclusions

The lifecycle does not implement bulk invitation, multi-Tenant switching, owner transfer, role editing, billing/seat logic, SCIM, domain JIT access, inactive-Tenant mapping or a generic audit module. Only `STANDARD` account admission is implemented. Invitation acceptance never creates privileged, bot, anonymous or service-account authority.
