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

Browser authentication validates the OIDC Authorization Code + PKCE callback, then resolves the same exact pair. The actor must also have active Organization authority. The callback replaces the provider principal with an application principal containing only `ActorId`, explicitly overwrites the HTTP-session security context, and discards the authorized client. Unknown or unauthorized identities receive `ACCESS_NOT_PROVISIONED` and no durable authenticated session.

The exact current-identity endpoint accepts either a bound bearer identity or an `ActorSessionAuthenticationToken` restored from the JDBC-backed browser session. Its higher-priority security chain may read an existing session but never saves bearer authentication into one. Every other `/api/**` endpoint remains stateless and bearer-only.

`GET /api/identity/me` composes the Identity-owned `ActorId` with the Organization-owned durable presentation/authority projection:

```json
{
  "actorId": "<uuid>",
  "organization": {
    "displayName": "Tasco",
    "role": "OWNER"
  },
  "capabilities": ["INVITATIONS_MANAGE"]
}
```

An active member receives the same Organization shape with role `MEMBER` and an empty capability list. A bound bearer actor without active membership receives `organization: null` and an empty list. Browser admission still requires active Organization authority. Role is presentation-only; browser behavior uses capabilities. The projection suppresses known-forbidden UI and requests but never authorizes a server operation. Invitation endpoints continue to resolve durable membership on every request, and capabilities are never stored in Spring Session.

## Persistence

`JdbcExternalIdentityResolver` is the capability-owned read adapter. `JdbcExternalIdentityRegistrar` is the capability-owned write adapter exposed to authorized transactions. Registration creates an actor and exact binding atomically or returns the actor already bound to that exact identity. Invitation acceptance uses the locked registration operation, which holds the stable Actor row until the surrounding transaction completes so concurrent membership grants for one identity serialize. SQL failures use Spring's unchecked `DataAccessException` hierarchy.

Flyway owns the schema under `core/src/main/resources/db/migration/`. Applied migrations are immutable.

## Binding lifecycle boundary

No generic account-linking endpoint, administrative binding endpoint, provisioning CLI, or unauthenticated identity write surface exists. The initial Organization transaction and the closed `invitation` capability are the only production binding writers. Invitation acceptance may invoke `ExternalIdentityRegistrar` only inside its authorized acceptance transaction; ordinary authentication never creates an actor.

## Runtime configuration

The API requires the OIDC issuer/JWKS/audience, confidential browser client secret, datasource credentials, and initial Organization values listed in the [development runtime runbook](../runbooks/development-runtime.md). Keycloak reconciliation also requires one exact absolute HTTP(S) browser callback URI and rejects wildcard redirect values. Missing or invalid values fail startup or reconciliation. Plain HTTP JWKS is accepted only for literal loopback test hosts; production uses HTTPS. Session cookies default to `HttpOnly`, `Secure`, and `SameSite=Lax`.