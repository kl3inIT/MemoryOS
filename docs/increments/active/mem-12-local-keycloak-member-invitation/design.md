# MEM-12 design: production member invitation

## Outcome

An authenticated Organization owner can onboard one member through a complete invitation lifecycle. The owner creates an invitation for one email, shares a one-time link, observes its status, and can revoke or rotate it. The recipient opens the link, authenticates with local Keycloak, and joins the existing Organization when the verified email matches.

This is a production vertical slice, not a temporary onboarding mode. Storage, authorization, recovery, concurrency, browser session handling, deployment configuration, and live runtime verification belong to the same increment.

## Product flow

### Owner

1. Open `Admin Panel` → `Invitations`.
2. Select `Invite member` and enter one email.
3. Receive a clear copy/share action for the plaintext invitation URL.
4. See invitation status as `PENDING`, `ACCEPTED`, `EXPIRED`, or `REVOKED`.
5. Revoke a pending invitation or rotate a lost link. Rotation returns a replacement secret once and permanently invalidates the previous link.

### Recipient

1. Open an invitation landing page that identifies the MemoryOS Organization.
2. Continue to the local Keycloak Authorization Code + S256 PKCE flow.
3. Sign in or create a local email-as-username account, then complete Keycloak email verification.
4. Return to MemoryOS with an exact issuer/subject and verified email.
5. Join automatically and land on `New Session`.

Technical state such as digests, locks, and provider-token disposal is never presented as a user step.

## Reference boundary

Onyx Enterprise is an interaction reference for invitation administration, its modal, pending status, loading, errors, and optional email delivery. MemoryOS does not copy Onyx's email-allowlist identity model, tenant switching, billing, or inactive tenant mappings.

MemoryOS adds `invitation` as a top-level closed Spring Modulith capability from the first implementation commit:

- identity owns stable `ActorId` and exact `(issuer, subject)` bindings;
- organization owns Organizations and memberships, and exposes only a narrow invitation-authority/membership port;
- invitation owns invitation lifecycle, secret handling, persistence, intake, and acceptance orchestration;
- Spring Security owns the OAuth2 continuation and JDBC-backed browser session;
- Keycloak owns credentials, authentication, and verified-email claims.

Dependency direction is `invitation -> identity` and `invitation -> organization`. Identity and organization never depend on invitation. Invitation persistence cannot write Organization membership tables directly; it invokes the Organization-owned mandatory-transaction port so binding, membership grants, and invitation consumption still commit atomically.

## Invitation lifecycle

The capability stores one invitation row with lifecycle facts and no plaintext secret. The intended state transitions are:

```text
PENDING -> ACCEPTED
PENDING -> REVOKED
PENDING + expires_at <= now -> EXPIRED response
PENDING -> PENDING with a new digest on rotation
```

`EXPIRED` is derived from a pending row and its durable expiry rather than requiring a background job. One partial unique constraint permits at most one pending invitation for a normalized email in one Organization.

Each invitation records:

- invitation ID and Organization ID;
- normalized email;
- SHA-256 digest of a 256-bit URL-safe random secret;
- creator `ActorId` and creation time;
- expiry;
- accepted actor/time or revoking actor/time when settled;
- the Organization grant authorized at issue time.

The plaintext secret exists only in the create or rotate response and the recipient's intake request. It is never logged, persisted, listed, or reconstructed.

## Schema strategy for this stage

MEM-12 is expected to be a genuinely additive schema change: add one Invitation-owned table and its indexes/constraints in the next small Flyway migration. Do not introduce expand/contract phases, dual reads or writes, compatibility columns, or a backfill framework for disposable development data.

If implementation reveals that an existing identity, membership, or session shape prevents the clean invitation model, stop and choose the final schema directly. At this project stage, an approved destructive reset is preferable to permanent compatibility code: verify a backup, recreate the MemoryOS database or affected schema, rerun Flyway and the initial-owner bootstrap, then reinsert only the minimal test data required for verification.

Existing applied migration files are not silently edited against a retained database. Any baseline squash must be an explicit coordinated reset of repository migrations and every MemoryOS database that recorded their checksums.

## Owner authorization

Issue, revoke, and rotate require the current `ActorId` to hold active Organization `OWNER` membership in an active Organization. The service derives Organization context from durable authority; clients do not submit an Organization or role selection.

An existing active member, an external identity already bound to another actor, or a conflicting pending invitation fails explicitly. No endpoint widens authority through email alone.

## Invitation intake

Opening `/invite/{secret}` hashes the secret, resolves exactly one available invitation, and rejects missing, expired, revoked, or consumed values before authority is created. A successful intake stores only invitation ID, Organization ID, and continuation expiry in the JDBC session. Spring Security owns OAuth2 state and OIDC nonce correlation; Invitation does not create a parallel nonce.

Intake and current-continuation resolution are read-only availability checks and therefore use ordinary reads. They may observe a continuation that becomes unavailable immediately afterward; acceptance always revalidates and locks the invitation before any authority write, so revoke, rotation, expiry, or consumption still wins safely.

The response uses `Cache-Control: no-store` and `Referrer-Policy: no-referrer`, then redirects into the existing OAuth2 authorization endpoint. The raw secret is not retained in the session.

## OAuth2 callback and acceptance

The browser callback keeps the existing bound-member path unchanged. An unbound identity may be provisioned only when a valid invitation continuation exists.

Acceptance requires:

- exact configured issuer and nonblank subject;
- a verified email claim;
- normalized email equal to the invitation email;
- matching, unexpired continuation;
- invitation still pending and unexpired under a row lock;
- no conflicting binding or memberships.

One transaction creates or resolves the Actor as permitted, inserts the exact external identity binding, grants Organization `MEMBER`, and conditionally accepts the invitation. Concurrent or replayed callbacks leave exactly one accepted result.

After commit, the callback rotates the HTTP session ID, replaces the provider principal with the existing `ActorId`-only application principal, saves the security context explicitly, and removes provider authorized-client state. Failure invalidates the partial session.

## Recovery and operational behavior

- Revocation conditionally settles only a pending invitation.
- Rotation conditionally replaces only a pending, unexpired digest and invalidates every previous link.
- Listing exposes lifecycle metadata, never plaintext secrets or digests.
- Copy/share is the complete delivery path. No speculative email provider abstraction is added; configured email delivery may be added only with a concrete provider and observable failure contract.
- Keycloak administrator and SMTP credentials never enter MemoryOS. The deployment-owned reconciliation script uses `kcadm` to enable self-registration, require verified email, and configure SMTP from managed environment values. At runtime, Spring Security performs the standard OAuth2 authorization-code/token exchange; MemoryOS has no Keycloak Admin SDK, custom Admin REST client, or SMTP client.
- Rate limits must work across API replicas or be enforced by the production gateway; an in-memory-only limiter is not acceptable.
- The only deployed server is staging. It uses a digest-pinned Mailpit mailbox to verify Keycloak-generated email without sending development mail to external recipients. Mailpit accepts only authenticated STARTTLS traffic on the internal Compose network; Keycloak trusts the deployment-owned private CA. Operators retain a server-loopback mailbox endpoint for SSH access. Browser access uses `https://memoryos-mail.72-62-193-33.nip.io` through Nginx Proxy Manager and a dedicated OAuth2 Proxy client with S256 PKCE, secure minimal cookies, secrets mounted from files, and an exact initial-owner email allowlist. Mailpit itself never joins the public proxy network. This proves self-registration, verification-link handling, and invitation continuation, but it is not evidence of public email deliverability or a production SMTP provider.
- The authenticated account menu exposes a real `Sign out` action. A same-origin guarded POST invalidates the JDBC application session, clears authentication and the `SESSION` cookie, then returns a provider logout URL derived from Keycloak's `end_session_endpoint`, `client_id=memoryos-web`, and the current registered post-logout origin. The browser navigates there to terminate the Keycloak SSO session; because MemoryOS intentionally retains no ID token, Keycloak may require logout confirmation before returning to the signed-out application.

## Approved follow-up scope

[MEM-34](https://linear.app/memory-os/issue/MEM-34/pre-provision-invited-keycloak-users-with-activation-email) owns the approved replacement for new-recipient credential onboarding: Identity-owned local-Keycloak account pre-provisioning plus bounded `VERIFY_EMAIL` and `UPDATE_PASSWORD` action email. This does not retroactively change the delivered MEM-12 contract or its evidence. Until MEM-34 merges, invitation recipients continue through the self-registration flow described above; the MEM-34 clean cutover then supersedes only that new-recipient credential path while retaining MEM-12 invitation lifecycle and acceptance semantics.

## Failure outcomes

- unauthorized owner action → `403`;
- missing, expired, revoked, consumed, or superseded secret → `INVITATION_NOT_AVAILABLE`;
- unverified email → `INVITATION_EMAIL_NOT_VERIFIED`;
- mismatched email → `INVITATION_EMAIL_MISMATCH`;
- missing or mismatched continuation → `INVITATION_CONTINUATION_MISMATCH`;
- existing identity or membership conflict → explicit conflict with no authority change;
- ordinary unknown login without invitation → existing `ACCESS_NOT_PROVISIONED` flow.

Recipient pages use plain-language recovery actions rather than exposing these internal codes as the primary message.

## Explicit exclusions

- multi-Organization switching or owner transfer;
- Organization role pickers;
- billing, seat metering, and trial quotas;
- bulk invitation;
- SCIM, group provisioning, and domain-based JIT access;
- inactive tenant mappings or a second authority model;
- a generic audit capability. Invitation lifecycle columns remain capability-owned evidence.
