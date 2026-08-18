# Identity capability contract

## Purpose

The identity capability maps a validated external OIDC identity to a stable internal `ActorId`. Other capabilities own data by `ActorId`, not by provider, email, username, or token.

## External identity

An `ExternalIdentity` is the exact pair:

```text
(issuer, subject)
```

Both values are non-null and nonblank. They are case-sensitive and are not normalized. The same subject at different issuers represents different external identities.

## Actor binding

- `ActorId` is a UUID.
- One actor may own multiple external identities.
- One exact external identity may reference only one actor.
- A binding must reference an existing actor.
- An actor with bindings cannot be deleted.
- Unknown bindings do not create actors implicitly.

PostgreSQL enforces these invariants through the primary key on `(issuer, subject)`, the foreign key to `actors.id`, and `ON DELETE RESTRICT`.

## Authentication contract

The API validates:

- JWT signature from the configured JWKS;
- exact configured issuer;
- configured audience `memoryos-api`;
- expiry and not-before timestamps; and
- nonblank `sub`.

After token validation, the API resolves exact `(iss, sub)` through `ExternalIdentityResolver`. A missing binding fails authentication with `401`. Email and username claims never substitute for a binding.

A successful request is authenticated with the internal actor principal. `GET /api/identity/me` returns exactly:

```json
{"actorId":"<uuid>"}
```

## Persistence contract

`JdbcExternalIdentityStore` is the capability-owned read adapter. It performs an exact parameterized lookup and returns `Optional.empty()` when no binding exists. SQL failures become an `IllegalStateException` with the original `SQLException` as cause.

Flyway owns the schema under `core/src/main/resources/db/migration/`. Applied migrations are immutable.

## Binding lifecycle boundary

No account-linking endpoint, administrative binding endpoint, provisioning CLI, or application write service exists. Bootstrap/recovery is an explicitly approved operator transaction described in the runbook, not a product capability.

A future runtime write flow must satisfy [ADR 0002](../decisions/0002-no-speculative-operational-surfaces.md) before production code is added.

## Runtime configuration

The API requires:

- `MEMORYOS_IDENTITY_ISSUER`
- `MEMORYOS_IDENTITY_JWK_SET_URI`
- `MEMORYOS_IDENTITY_AUDIENCE`
- `MEMORYOS_DATABASE_URL`
- `MEMORYOS_DATABASE_USERNAME`
- `MEMORYOS_DATABASE_PASSWORD`

Missing or invalid values fail startup. Plain HTTP JWKS is accepted only for literal loopback test hosts; production JWKS uses HTTPS.
