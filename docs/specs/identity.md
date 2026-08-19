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

`GET /api/identity/me` returns exactly:

```json
{"actorId":"<uuid>"}
```

## Persistence

`JdbcExternalIdentityResolver` is the capability-owned read adapter. `JdbcExternalIdentityRegistrar` is the capability-owned write adapter exposed to authorized transactions. Registration creates an actor and exact binding atomically or returns the actor already bound to that exact identity. SQL failures use Spring's unchecked `DataAccessException` hierarchy.

Flyway owns the schema under `core/src/main/resources/db/migration/`. Applied migrations are immutable.

## Binding lifecycle boundary

No generic account-linking endpoint, administrative binding endpoint, provisioning CLI, invitation flow, or unauthenticated write surface exists. The only production binding write is the deployment-configured initial Organization transaction defined by the [organization contract](organization.md). Any broader write flow must satisfy [ADR 0002](../decisions/0002-no-speculative-operational-surfaces.md).

## Runtime configuration

The API requires the OIDC issuer/JWKS/audience, confidential browser client secret, datasource credentials, and initial Organization values listed in the [development runtime runbook](../runbooks/development-runtime.md). Keycloak reconciliation also requires one exact absolute HTTP(S) browser callback URI and rejects wildcard redirect values. Missing or invalid values fail startup or reconciliation. Plain HTTP JWKS is accepted only for literal loopback test hosts; production uses HTTPS. Session cookies default to `HttpOnly`, `Secure`, and `SameSite=Lax`.