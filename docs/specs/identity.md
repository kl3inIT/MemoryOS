# Identity capability contract

## Purpose

The identity capability maps a validated external OIDC identity to a stable internal `ActorId`. Other capabilities own authority and data by `ActorId`, not by provider, email, username, or token.

## External identity

An `ExternalIdentity` is the exact, case-sensitive pair `(issuer, subject)`. Both values are non-null and nonblank; neither is normalized. The same subject at different issuers represents different identities.

## Actor binding

- `ActorId` is a UUID.
- One actor may own multiple external identities.
- One exact external identity may reference only one actor.
- A binding must reference an existing actor.
- An actor with bindings cannot be deleted.
- Authentication never creates an actor for an unknown binding.

PostgreSQL enforces these invariants through the `(issuer, subject)` primary key, the foreign key to `actors.id`, and `ON DELETE RESTRICT`.

## Authentication

Bearer authentication validates JWT signature, exact configured issuer, configured audience, expiry, not-before, and nonblank `sub`. It resolves exact `(iss, sub)` through `ExternalIdentityResolver`; a missing binding fails with `401`.

Browser authentication validates the OIDC Authorization Code + PKCE callback, then resolves the same exact pair. The actor must also have active Tenant authority. The callback replaces the provider principal with an application principal containing only `ActorId`, explicitly overwrites the HTTP-session security context, and discards the authorized client. Unknown or unauthorized identities receive `ACCESS_NOT_PROVISIONED` and no durable authenticated session.

The exact current-identity endpoint accepts either a bound bearer identity or an `ActorSessionAuthenticationToken` restored from the JDBC-backed browser session. Its higher-priority security chain may read an existing session but never saves bearer authentication into one. Every other `/api/**` endpoint remains stateless and bearer-only.

`GET /api/identity/me` composes the Identity-owned `ActorId` with the Tenant-owned durable presentation/authority projection:

```json
{
  "actorId": "<uuid>",
  "tenant": {
    "displayName": "Tasco",
    "role": "OWNER"
  },
  "capabilities": ["INVITATIONS_MANAGE"]
}
```

An active member receives the same Tenant shape with role `MEMBER` and an empty capability list. A bound bearer actor without active membership receives `tenant: null` and an empty list. Browser admission still requires active Tenant authority. Role is presentation-only; browser behavior uses capabilities. The projection suppresses known-forbidden UI and requests but never authorizes a server operation. Invitation endpoints continue to resolve durable membership on every request, and capabilities are never stored in Spring Session.

## Browser-session convergence

One persistent authenticated frontend layout owns the current-identity query across application routes. Internal route changes do not replace the browser document or remount the session boundary. The identity query refetches whenever the browser returns to the foreground because the JDBC-session cookie can change in another tab.

The frontend QueryClient retains the accepted actor id plus Tenant role/capability projection. Before rendering a changed actor or authority projection, it removes every non-identity query and all mutation state. An identity `401` performs the same private-state purge and renders signed-out state; a `401` from another private query or mutation additionally resets the active identity query so all protected UI converges through the canonical endpoint.

## Persistence

`JdbcExternalIdentityResolver` is the capability-owned read adapter. `JdbcExternalIdentityRegistrar` is the capability-owned write adapter exposed to authorized transactions. Registration creates an actor and exact binding atomically or returns the actor already bound to that exact identity. Invitation acceptance uses the locked registration operation, which holds the stable Actor row until the surrounding transaction completes so concurrent membership grants for one identity serialize. SQL failures use Spring's unchecked `DataAccessException` hierarchy.

Flyway owns the schema under `core/src/main/resources/db/migration/`. Applied migrations are immutable.

## Binding lifecycle boundary

No generic account-linking endpoint, administrative binding endpoint, provisioning CLI, or unauthenticated identity write surface exists. The initial Tenant transaction and the closed `invitation` capability are the only production binding writers. Invitation acceptance may invoke `ExternalIdentityRegistrar` only inside its authorized acceptance transaction; ordinary authentication never creates an actor.

## Local-Keycloak invitation provisioning

Keycloak is the fixed MemoryOS authentication plane and enterprise OIDC/SAML broker. MemoryOS PostgreSQL remains authoritative for Actors and application authority. The Identity capability owns the concrete Keycloak Admin Client implementation used by Invitation; no provider-neutral adapter or user-administration surface exists.

Invitation issue resolves one exact normalized email in the `memoryos` realm. An absent user is created enabled with email-as-username, `emailVerified=false`, minimal MemoryOS origin evidence, and bounded `VERIFY_EMAIL` plus `UPDATE_PASSWORD` required actions. A MemoryOS-created unverified user is reused on retry. An exact existing verified user is reused without required actions or password reset. An unrelated unverified or ambiguous account fails closed.

The action email returns to the additional exact `/invite/activate` browser URI without an invitation secret, invitation ID, or parallel nonce. The API uses a dedicated realm-local `memoryos-user-provisioner` service account, explicit bounded HTTP-client timeouts, and managed credentials that are never logged or persisted. A Keycloak account alone grants no MemoryOS authority.

## Runtime configuration

The API requires the OIDC issuer/JWKS/audience, confidential browser client secret, datasource credentials, initial Tenant values, and Keycloak invitation-provisioner values listed in the [development runtime runbook](../runbooks/development-runtime.md). Keycloak reconciliation requires the exact browser callback plus the exact `/invite/activate` return URI and rejects wildcard redirect values. Missing or invalid values fail startup or reconciliation. Plain HTTP JWKS and activation URIs are accepted only for literal loopback test hosts; production uses HTTPS. Session cookies default to `HttpOnly`, `Secure`, and `SameSite=Lax`.