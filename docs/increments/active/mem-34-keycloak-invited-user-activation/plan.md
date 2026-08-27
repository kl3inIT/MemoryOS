# MEM-34 implementation plan: pre-provision invited Keycloak users

## Contract and architecture

- [x] Reconcile the approved MEM-34 flow with the identity and invitation capability specs before implementation.
- [x] Add a MEM-34 verification matrix covering new, existing, conflicting, partial-failure, revoke, expiry, rotation, and replay paths.
- [x] Add one narrow identity-root local-Keycloak recipient-provisioning API consumed by invitation; do not add a provider-neutral abstraction or generic IAM CRUD facade.
- [x] Keep the Keycloak-specific implementation inside the `identity` capability in `core`; keep `api` limited to deployable properties, client construction, HTTP, OAuth2, and security composition.
- [x] Add the official Keycloak Admin Client at the deployed-compatible version and update architecture/dependency rules for the concrete provider SDK.
- [x] Record an ADR only after implementation starts, capturing provider ownership, SDK placement, accepted worker transitivity, and the condition for a future separate integration module.

## Keycloak desired state

- [x] Reconcile a dedicated confidential `memoryos-user-provisioner` client in the `memoryos` realm through the deployment-owned `kcadm` script.
- [x] Enable its service account and grant only realm-local user authority required by Keycloak 26.7.0; never grant `realm-admin`, `master` access, client management, or OrgMemory authority.
- [x] Inject the service-account credential through Infisical without command arguments, output, or persisted application state.
- [x] Configure explicit Keycloak Admin Client connect, connection-request, and read timeouts so the synchronous provider call has a documented upper bound on invitation-transaction and database-connection hold time.
- [x] Register one additional exact `https://<memoryos-origin>/invite/activate` URI for `memoryos-web`; implement it as a public no-store redirect into `/oauth2/authorization/memoryos`, retain the exact Spring callback, and keep wildcard redirects forbidden.
- [x] Keep self-registration available only until the MEM-34 clean cutover is deployed and verified, then reconcile `registrationAllowed=false`, remove obsolete registration branding, and document operator recovery for an unrelated pre-existing unverified email.

## Identity capability

- [x] Implement exact normalized-email lookup without ambiguous partial matching.
- [x] Create a local-Keycloak user only when absent, with fixed email-as-username, `emailVerified=false`, no application role, and minimum origin evidence for safe retry.
- [x] Assign `VERIFY_EMAIL` and `UPDATE_PASSWORD` only to a newly provider-created user or an exact safe retry of that user.
- [x] Send the bounded action email through Keycloak `execute-actions-email` with exact client and return URI.
- [x] Reuse an existing verified user without forced password reset.
- [x] Reject an unrelated unverified user or conflicting provider identity without mutating it.
- [x] Redact service credentials, admin tokens, action tokens, invitation secrets, issuer subjects, and recipient PII from logs and exceptions.

## Invitation orchestration

- [x] Invoke identity provisioning during owner invitation issue while retaining owner authorization, digest-only secret handling, and one-open-email constraints.
- [x] Make retry reuse a provider user created by a prior partial attempt and issue one fresh action email without creating a duplicate user or invitation.
- [x] Define safe REST outcomes for provider conflict, provider unavailable, and activation delivery failure using the existing typed Problem Details boundary.
- [x] Preserve copy/share for recovery and for exact existing verified users; rotation invalidates only prior capability links and does not claim to invalidate an already-sent Keycloak action email.
- [x] On OAuth callback with no `InvitationSessionState`, allow both an unbound identity and a bound Actor without active Organization authority to resolve exactly one pending unexpired invitation only from the exact configured issuer, nonblank subject, and provider-verified normalized email, then enter the existing locked acceptance transaction.
- [x] Keep plaintext invitation secret, invitation ID, and parallel correlation nonces out of the Keycloak action email redirect URI and action token; retain capability-link continuation only for copy/share recovery.
- [x] Preserve the existing atomic external-identity binding, Organization `MEMBER`, and invitation-consumption transaction.
- [x] Keep provider-user deletion outside revoke/expiry; a Keycloak account alone must remain unable to acquire MemoryOS authority.

## Product experience

- [x] Update the owner result state to distinguish activation sent from copy/share-only recovery without exposing provider internals.
- [x] Brand the Keycloak required-action email and pages for MemoryOS and show the fixed invited email clearly.
- [x] Ensure a new recipient never sees generic registration and never re-enters the invited email.
- [x] Return successful activation directly through MemoryOS acceptance to `New Session` without a redundant sign-in choice.
- [x] Provide plain-language recovery for expired action email, revoked invitation, provider conflict, and delivery failure; use revoke plus re-invite to resend a fresh activation email, with Keycloak forgot-password as the recipient self-service fallback.

## Verification and delivery

- [x] Test new-user provisioning, exact-email idempotency, existing verified-user reuse, unrelated unverified-user conflict, and provider partial failures at the narrowest useful boundary.
- [x] Test activation callback correlation for exact verified-email match, no match, ambiguous match, unverified email, and an existing verified user signing in without the copy/share secret.
- [x] Test that provider creation or email failure creates no membership and that retry converges to one Keycloak user and one open invitation.
- [x] Exercise revoke, expiry, and replay after the provider account exists; none may create binding or authority. Verify rotation invalidates prior capability links while the still-pending invitation remains acceptable through exact verified-email correlation.
- [ ] Exercise the real Spring browser chain and local Keycloak action email with `VERIFY_EMAIL`, `UPDATE_PASSWORD`, exact redirect, Authorization Code + S256 PKCE, and `ActorId`-only final session.
- [ ] Verify the `memoryos-user-provisioner` grant is realm-local and no broader than Keycloak 26.7.0 requires; record `keycloak/keycloak#51411` as the narrowing trigger.
- [x] Inspect every changed Java, Kotlin DSL, YAML, properties, and XML file with JetBrains warnings enabled, then compile affected modules.
- [x] Run focused capability/API tests, `gradlew.bat clean check --no-daemon`, `pnpm check`, and the browser contract suite.
- [ ] Deploy one exact reviewed SHA and complete the owner invitation → action email → verify email → set password → automatic acceptance staging flow through Mailpit and shared Keycloak.
- [ ] Consolidate implemented facts into architecture/spec/test/runbook documents, complete the guarded PR loop, and close MEM-34 only after exact-merge verification.
