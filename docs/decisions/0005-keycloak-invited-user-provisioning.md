# ADR 0005: Identity-owned Keycloak invitation provisioning

## Status

Accepted

## Context

MEM-12 proved a production invitation lifecycle whose new recipients used public local-Keycloak self-registration. The flow is secure but asks an invited user to re-enter the invited email in a generic registration surface. MemoryOS has also committed to Keycloak as its fixed authentication plane and future enterprise OIDC/SAML broker, while PostgreSQL remains the source of truth for Actors, exact identity bindings, Organization memberships, invitations, and future SCIM provisioning records.

A new local-password recipient therefore needs a provider credential account before invitation acceptance, but provider-account existence must never imply MemoryOS authority. The integration must not move invitation state or Organization roles into Keycloak, introduce a provider-neutral abstraction, or put plaintext invitation correlation into Keycloak action tokens.

## Decision

The `identity` capability in `core` owns one narrow local-Keycloak invitation-provisioning API and its Keycloak Admin Client implementation. The `invitation` capability invokes that API synchronously while issuing a pending invitation through the existing allowed `invitation -> identity` dependency. `api` remains the HTTP/OAuth2/security composition root and supplies managed runtime properties only.

For an absent exact email, Identity creates one enabled email-as-username Keycloak user with minimal MemoryOS origin evidence and sends a bounded action email requiring `VERIFY_EMAIL` and `UPDATE_PASSWORD`. A MemoryOS-created unverified user is reused on retry. An exact existing verified user is reused without password reset and accepts on normal sign-in. An unrelated unverified or ambiguous account fails closed.

The action email returns to one additional exact `/invite/activate` URI registered on `memoryos-web`. That endpoint stores no invitation identifier or secret and immediately starts the existing Authorization Code + S256 PKCE flow. When the callback has no capability-link continuation, it may resolve exactly one pending unexpired invitation only from the exact configured issuer, nonblank subject, and provider-verified normalized email. It then enters the existing invitation-row lock, Actor-row lock, binding, Organization `MEMBER`, and conditional acceptance transaction. Provider tokens and activation state are removed before the final `ActorId`-only session is saved.

The dedicated `memoryos-user-provisioner` service account exists only in the `memoryos` realm. It receives realm-local `manage-users` because Keycloak 26.7.0 currently requires that broad grant for `execute-actions-email`; it receives no `realm-admin`, `master`, client-management, or OrgMemory authority. The grant must be narrowed when `keycloak/keycloak#51411` permits it. Public self-registration is disabled at the clean cutover.

Provider calls remain synchronous and idempotent with explicit connect, connection-request, and read timeouts that bound the invitation transaction's database-connection hold time. No outbox, worker, custom Keycloak SPI, duplicate invitation store, or generic IAM CRUD facade is added without observed operational pressure.

## Consequences

- New local-password invitees verify the fixed invited email and choose a password without generic self-registration.
- Keycloak remains authentication authority; MemoryOS remains application-user and product-authority source of truth and stays compatible with future SCIM provisioning.
- Existing verified Keycloak users can accept a pending invitation on normal login without the copy/share secret.
- Rotation invalidates prior capability links but cannot invalidate an already-sent Keycloak action email; revoke, expiry, consumption, and the locked acceptance boundary remain authorization controls.
- A provider user may survive a partial database failure, but exact origin evidence makes retry idempotent and the account alone grants no MemoryOS authority.
- The Admin Client dependency is capability implementation weight in `core` and is transitively present but inert in `worker`; a fourth integration module remains deferred until a second runtime consumer or concrete isolation requirement exists.
