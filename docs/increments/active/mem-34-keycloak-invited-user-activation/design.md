# MEM-34 design: pre-provision invited Keycloak users

## Outcome

An active Organization owner can invite a new local-Keycloak recipient without making that recipient use the generic registration form or re-enter the invited email. MemoryOS creates or reuses the exact provider account, Keycloak sends a bounded action email, and the recipient verifies the email and chooses a password before returning through the existing MemoryOS invitation acceptance boundary.

MEM-12 remains the delivered invitation, acceptance, and Organization-membership baseline. MEM-34 is the approved follow-up scope change for provider-account activation. Until MEM-34 merges, the implemented runtime continues to use MEM-12 self-registration.

## Product flow

### New local-Keycloak recipient

1. An active Organization `OWNER` creates an invitation for one normalized email.
2. MemoryOS creates one exact local-Keycloak user with that email and no application authority.
3. Keycloak sends one action email requiring `VERIFY_EMAIL` and `UPDATE_PASSWORD`.
4. The recipient opens the action link, verifies the fixed invited email, and chooses a password without using the generic registration form.
5. Keycloak returns to the additional exact registered `https://<memoryos-origin>/invite/activate` URI without an invitation secret or identifier in the action-token redirect. The public no-store endpoint immediately starts `/oauth2/authorization/memoryos`.
6. The OAuth callback resolves the still-valid pending invitation from the exact configured issuer and provider-verified normalized email, then accepts it through the existing locked transaction.
7. Exact external-identity binding, Organization `MEMBER`, and invitation consumption commit atomically; the final browser session contains only `ActorId`.

### Existing local-Keycloak recipient

An exact existing verified user is never forced to reset a password. The owner receives the existing copy/share invitation path and the recipient signs in normally. An unverified user may receive activation again only when durable provider evidence shows that MemoryOS created it for an earlier attempt; an unrelated unverified account is an identity conflict, not an account MemoryOS may take over.

## Activation correlation

The Keycloak action email never embeds the plaintext invitation secret, invitation ID, or a parallel nonce in its redirect URI or action token. The activation-email browser therefore returns without MEM-12 `InvitationSessionState`. When the OAuth callback receives either an unbound identity or a bound Actor without active Organization authority and no continuation, it may resolve an invitation only when the issuer is the exact configured local Keycloak issuer, subject is nonblank, `email_verified` is true, and exactly one pending unexpired invitation matches the normalized email. No match, an ambiguous match, or an unverified email follows the existing denial path. Resolution immediately enters the same invitation row lock, identity lock, conflict checks, and atomic acceptance transaction used by the capability-link flow.

This intentionally makes the owner-approved pending invitation plus provider-verified mailbox ownership the authorization correlation for the activation-email path. An exact existing verified Keycloak user with a pending invitation can therefore accept on normal login without possessing the copy/share secret. The secret remains the capability for explicit intake, rotation, and recovery; it is never sent to or stored by Keycloak.

## Scope boundary with MEM-12

MEM-12 continues to own:

- invitation issue/list/rotate/revoke lifecycle;
- digest-only secrets and redacted continuation state;
- exact verified-email acceptance;
- replay, conflict, and concurrency protection;
- Organization membership and `ActorId`-only browser sessions.

MEM-34 changes only the credential-onboarding path for a new local-Keycloak recipient. It replaces invitation-driven self-registration with provider-account pre-provisioning and Keycloak required actions. Copy/share remains the complete recovery path and the existing-user path.

## Capability ownership

Provider-account provisioning belongs to the `identity` capability in `core`. Identity owns the Keycloak-specific implementation and exposes one narrow root-package operation required by `invitation`. Invitation remains dependent only on the public APIs of `identity` and `organization`; the existing `invitation -> identity` edge does not change.

Keycloak is the fixed MemoryOS identity plane and enterprise identity broker, not one interchangeable provider among several. Upstream enterprise OIDC/SAML providers terminate in Keycloak; MemoryOS continues to trust the single `memoryos` realm issuer and its broker-local subject. MEM-34 therefore adds concrete local-Keycloak provisioning and no provider-neutral adapter, provider selection, or future provider-swap abstraction.

```text
core.identity
  public recipient-provisioning contract
  internal Keycloak Admin Client implementation

core.invitation
  issue/accept lifecycle
  invokes identity provisioning through its public API

api
  HTTP and OAuth2/security composition
  deployable properties, credential injection, and client construction only
```

`api` does not implement provider-account behavior. The Keycloak Admin Client dependency is capability implementation weight in `core`; its transitive presence in `worker` is accepted for this smallest complete path. A separate provider module is deferred until a second runtime consumer or concrete isolation requirement justifies a fourth build boundary and ADR.

## Runtime integration

Use the official Keycloak Admin Client aligned with the deployed Keycloak version. Reconciliation creates a dedicated confidential `memoryos-user-provisioner` client with a service account in the `memoryos` realm. It never authenticates in `master`, never receives `realm-admin`, and never receives OrgMemory realm authority.

The API deployable obtains the managed credential through the existing Infisical injection path and constructs the runtime client consumed by the identity-owned implementation. Admin access tokens, service-account credentials, action tokens, and plaintext invitation secrets are never persisted or logged.

Keycloak 26.7.0 currently requires broad user `manage` authority for `execute-actions-email` under Fine-Grained Admin Permissions V2. The initial grant is therefore realm-local `manage-users`, with no broader realm administration. Narrowing that grant is a follow-up when Keycloak resolves `keycloak/keycloak#51411`; a custom Keycloak SPI is not introduced to work around it.

## Minimal consistency model

Provisioning is synchronous and idempotent by exact normalized email:

1. Insert the pending MemoryOS invitation inside its existing transaction.
2. Resolve the exact Keycloak email.
3. Create the provider user only when absent, tagging only the minimum origin evidence needed to recognize a safe retry.
4. Set `VERIFY_EMAIL` and `UPDATE_PASSWORD` and send a bounded action email whose return target is the additional exact `/invite/activate` URI and contains no invitation secret or identifier.
5. Commit the MemoryOS invitation only when the provider call succeeds.

The Keycloak Admin Client uses explicit connect, connection-request, and read timeouts. Their combined upper bound limits how long the invitation transaction may hold a database connection while Keycloak is slow but reachable; timeout failure follows the same rollback and idempotent-retry path as provider unavailability.

If Keycloak creates the user but a later provider call or database commit fails, retry reuses that exact provider user and resends a fresh bounded action email. No duplicate user is created. A stale action email can at worst lead to an unavailable invitation; it cannot grant application authority.

No outbox, worker, broker, saga framework, second invitation-token store, or generic IAM CRUD abstraction is introduced without observed operational pressure. Provider-account existence never implies MemoryOS membership. Expired, revoked, consumed, or replayed invitations still fail at the existing acceptance lock. Rotation invalidates prior copy/share capability links and extends the still-pending invitation; it does not invalidate an already-sent Keycloak action email or verified-email correlation.

Revocation does not delete an existing or activated Keycloak user. Account deletion has a different lifecycle and may destroy an independently usable identity. Provider-created cleanup remains absent until a concrete retention or operator requirement owns it.

## Failure behavior

- Provider user conflict or ambiguous exact-email ownership fails before membership mutation.
- Keycloak unavailability or action-email failure rolls back the invitation issue response and leaves a safe retry path.
- A provider user created by a partial attempt is reused only with exact origin evidence.
- An existing verified user follows normal sign-in and is never assigned `UPDATE_PASSWORD`.
- An action completed after invitation expiry or revocation cannot create binding or membership; rotation changes only the copy/share capability secret while the pending email-targeted invitation remains acceptable.
- Acceptance failure invalidates the partial browser session exactly as in MEM-12.

Provider diagnostics remain server-side. REST failures use the existing typed Problem Details contract with a new identity/invitation failure reason only when the caller can act on it safely.

## Product surface

The owner still receives the invitation link immediately. For a newly provisioned recipient, the UI also reports that Keycloak activation was sent without claiming public email deliverability. Staging Mailpit remains the observable delivery boundary.

The recipient action email and Keycloak required-action pages must identify MemoryOS and the invited email clearly. After password setup and verification, the browser continues automatically through MemoryOS acceptance; it does not return to the generic registration or sign-in choice screen.

## Verification

The increment requires evidence for:

- new-user create-if-absent and bounded `VERIFY_EMAIL` + `UPDATE_PASSWORD` delivery;
- exact existing verified-user reuse without password reset;
- unrelated unverified-user conflict;
- provider partial failure followed by idempotent retry;
- revoke/expire/replay after provider account creation, plus rotation invalidating only prior capability links without breaking verified-email acceptance;
- no membership before successful invitation acceptance;
- exact issuer/subject binding and Organization `MEMBER` after acceptance;
- absence of provider/admin tokens and invitation continuation from the final session;
- realm-local service-account authority and secret-safe logs;
- complete staging browser flow through Mailpit and local Keycloak.

## Exclusions

- Custom Keycloak SPI or theme-owned invitation-token validation.
- A second invitation lifecycle inside Keycloak.
- Generic identity-administration endpoints.
- Owner-visible Keycloak user CRUD.
- Automatic deletion of provider users on revoke or expiry.
- Bulk invitation, SCIM, domain JIT access, or public-provider email delivery.
- Outbox/worker delivery before retry evidence requires it.
