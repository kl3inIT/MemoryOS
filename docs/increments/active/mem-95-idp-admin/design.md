# MEM-95 — In-app Keycloak identity provider administration

Tracking: [MEM-95](https://linear.app/memory-os/issue/MEM-95), assign `nhudinhnhat2004`. Backend and admin UI delivered together.

## Goal and trust boundary

Let a `SYSTEM_ADMIN` actor manage upstream OIDC identity providers (IdP brokering) on the `memoryos` realm through MemoryOS instead of the Keycloak Admin Console. Keycloak remains the only trusted OIDC issuer and the source of truth for IdP configuration; MemoryOS PostgreSQL remains authoritative for Actors, memberships, Groups, and the JIT allowlist.

The existing MEM-59 trust boundary is unchanged: only the validated ID token's strict-String `memoryos_identity_provider` claim, issued by the configured Keycloak issuer, selects JIT admission. What changes is where the allowlist lives: it moves from the env-bound `memoryos.identity.jit.allowed-provider-aliases` set to a durable `jit_allowed_provider` table managed at runtime. The env value becomes a one-time startup seed, preserving existing deployments (`tasco` keeps working without reconfiguration).

## Decisions

- **Authorization**: every operation requires global `SYSTEM_ADMIN` via `IamAuthorization`. Reads use `require`; writes re-authorize under the exclusive Tenant lock after provider IO, per the existing "provider IO outside the lock" convention.
- **OIDC only.** No SAML or social providers. Alias is immutable after creation (Keycloak treats it as identity); issuer is also immutable — changing upstream means delete + recreate.
- **No `identity_providers` table.** Keycloak owns IdP config; the API is a management proxy. PostgreSQL stores only what Keycloak cannot: the JIT allowlist (`jit_allowed_provider`) with `created_by` audit.
- **Secret write-only.** `clientSecret` is accepted on create/update, never returned. A null secret on update keeps the stored one.
- **Server-side discovery.** `POST /api/identity-providers/discovery` fetches `{issuer}/.well-known/openid-configuration` with bounded timeouts, requires HTTPS except literal loopback, and verifies the document's `issuer` matches the input exactly.
- **Delete is allowed** with the JIT alias removed first (fail-safe: a deleted IdP can never emit the claim again, so an orphaned allowlist row would be harmless; the reverse order would leave a live IdP with no JIT entry — also harmless but less clean). Keycloak delete happens after the DB row is removed.
- **Broker redirect URI** is derived from the configured public issuer (`spring.security.oauth2.resourceserver.jwt.issuer-uri` + `/broker/{alias}/endpoint`), not the internal admin `server-url`.
- **Shared admin client.** `KeycloakAdminConfiguration` exposes the `Keycloak` bean built from existing `KeycloakAdminProperties`; both the recipient provisioner and the new IdP gateway inject it. The realm script grants `manage-identity-providers` to `memoryos-user-provisioner` alongside `manage-users`.

## Structure

```
core/src/main/java/io/memoryos/iam/
├── keycloak/
│   ├── KeycloakAdminConfiguration.java          exposes shared Keycloak bean
│   ├── AdminClientKeycloakRecipientProvisioner  injects the bean (was: builds its own)
│   ├── KeycloakIdentityProviderGateway.java     wraps realm().identityProviders()
│   ├── OidcDiscoveryClient.java                 bounded .well-known fetch + issuer check
│   └── DiscoveredOidcProvider.java              resolved endpoint record
├── identityprovider/
│   ├── IdentityProviderAdministration.java      interface
│   ├── DefaultIdentityProviderAdministration.java
│   ├── IdentityProviderView.java                alias/displayName/issuer/clientId/enabled/jitAllowed
│   ├── IdentityProviderCommand.java             create/update payload; null secret = keep
│   ├── IdentityProviderException.java           BusinessException
│   ├── IdentityProviderFailureReason.java       NOT_FOUND/ALIAS_CONFLICT/DISCOVERY_FAILED/PROVIDER_UNAVAILABLE/INVALID
│   ├── JitAdmissionPolicy.java                  boolean allows(Object claim)
│   ├── DefaultJitAdmissionPolicy.java           DB-backed; strict String + allowlist
│   └── persistence/JitAllowlistRepository.java  JDBC CRUD + seed
└── db/migration/V53__jit_allowed_provider.sql

api/src/main/java/io/memoryos/api/
├── identityprovider/
│   ├── IdentityProviderController.java          /api/identity-providers
│   └── contract/                                request/response records
└── security/
    ├── ActorSessionLoginSuccessHandler.java     jitProperties.allows → jitAdmissionPolicy.allows
    ├── SessionSecurityConfiguration.java        wires policy + startup seeder
    └── JitAdmissionProperties.java              unchanged; now a seed source only
```

## API surface

```
GET    /api/identity-providers                  list views (SYSTEM_ADMIN)
POST   /api/identity-providers/discovery        { issuerUrl } → resolved endpoints
POST   /api/identity-providers                  create + optional jitAllowed grant
PUT    /api/identity-providers/{alias}          displayName/clientId/secret/enabled/jitAllowed
DELETE /api/identity-providers/{alias}          remove IdP + JIT alias
```

`IdentityProviderResponse` adds `brokerRedirectUri` computed in the controller from the public issuer.

## Transaction and failure model

- Reads: `require(actor, SYSTEM_ADMIN, false)` then Keycloak IO; no DB writes.
- Create/update: `require` → Keycloak IO → `@Transactional` re-authorize under exclusive Tenant lock → JIT allowlist write. A Keycloak failure leaves no DB residue; a DB failure after Keycloak create leaves an IdP without JIT — safe, retryable.
- Delete: `@Transactional` re-authorize + allowlist delete → Keycloak delete. If Keycloak delete fails after commit, the IdP remains but cannot JIT — fail-safe.
- Keycloak `WebApplicationException`/`ProcessingException` map to `IdentityProviderException`: 404 → `IDP_NOT_FOUND`, 409 → `IDP_ALIAS_CONFLICT`, others → `IDP_PROVIDER_UNAVAILABLE` (503). Discovery failures → `IDP_DISCOVERY_FAILED` (400 for invalid doc/issuer mismatch, 503 for unreachable).

## Non-goals

- No test-connection endpoint (phase 2), no SAML/social providers, no upstream-side client creation, no per-IdP mappers (the `memoryos-web` session-note mapper is provider-agnostic), no changes to invitation or bearer paths.
