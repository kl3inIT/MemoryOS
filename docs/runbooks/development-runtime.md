# Development runtime runbook

## Prerequisites

- JDK 25.
- Checked-in Gradle wrapper.
- Access to the shared Keycloak and PostgreSQL hosts when running the real integration flow.
- Secrets retrieved from their managed locations, never copied into repository files or command history.

## Run the API

Set runtime configuration in the process environment:

```powershell
$env:MEMORYOS_IDENTITY_ISSUER = "https://auth.kl3in.tech/realms/memoryos"
$env:MEMORYOS_IDENTITY_JWK_SET_URI = "https://auth.kl3in.tech/realms/memoryos/protocol/openid-connect/certs"
$env:MEMORYOS_IDENTITY_AUDIENCE = "memoryos-api"
$env:MEMORYOS_DATABASE_URL = "jdbc:postgresql://127.0.0.1:15555/memoryos"
$env:MEMORYOS_DATABASE_USERNAME = "memoryos_app"
$env:MEMORYOS_DATABASE_PASSWORD = "<load from managed runtime secret>"

.\gradlew.bat :api:bootRun
```

Shared PostgreSQL binds only to server loopback port `5555`. Establish an SSH local forward to `127.0.0.1:15555` before using the URL above. Do not publish the database port.

| Endpoint | Access | Expected result |
| --- | --- | --- |
| `GET /actuator/health` | Public | API health |
| `GET /api/identity/me` | Bearer JWT with stored binding | `{"actorId":"<uuid>"}` |
| `GET /api/identity/me` | Missing/invalid token or unknown binding | `401` |

Startup failure for missing OIDC or datasource configuration is expected fail-fast behavior.

## Run the worker

```powershell
.\gradlew.bat :worker:bootRun
```

The foundation worker exits cleanly because no durable job loop exists yet.

## Shared Keycloak

- Realm: `memoryos`.
- Issuer: `https://auth.kl3in.tech/realms/memoryos`.
- JWKS: `https://auth.kl3in.tech/realms/memoryos/protocol/openid-connect/certs`.
- Public client: `memoryos-integration`.
- Flow: Authorization Code + PKCE S256.
- Redirect URIs: `http://127.0.0.1:8765/callback` and `http://localhost:8765/callback`.

The realm is an operator-created prerequisite. `infrastructure/keycloak/` reconciles only the client and audience mapper. Run its script with a service account limited to `realm-management/view-realm` and `realm-management/manage-clients`; do not grant `realm-admin`. The application never receives Keycloak administrator credentials.

Real-login verification uses a normal temporary user, calls `/api/identity/me`, and removes the user and temporary database rows afterward.

## Bootstrap an identity binding before account linking exists

MemoryOS currently has no account-linking or administrative write API. Do not add a one-shot application profile or unauthenticated endpoint to bridge that gap. When bootstrap is explicitly approved, an authorized database operator may execute one reviewed transaction using the exact token `iss`, exact token `sub`, and chosen internal UUID:

```sql
BEGIN;
INSERT INTO actors (id)
VALUES ('<internal-actor-uuid>')
ON CONFLICT DO NOTHING;

INSERT INTO external_identity_bindings (issuer, subject, actor_id)
VALUES ('<exact-issuer>', '<exact-subject>', '<internal-actor-uuid>');
COMMIT;
```

The binding insert intentionally has no conflict suppression: an existing external identity aborts rather than silently rebinding. Before execution, verify values without logging the raw token. After execution, query the exact key and call `/api/identity/me`. For any rebind or destructive change, follow [persistence recovery policy](../guidelines/persistence.md).

This transaction is bootstrap/recovery, not the product flow. A future runtime account-linking increment must authenticate both sides, authorize the change, transact the write, and audit it.

## Repository verification

```powershell
.\gradlew.bat clean check --no-daemon
```

The gate compiles all modules, runs capability and integration tests, verifies Spring Modulith and ArchUnit boundaries, and starts both composition roots in tests.
