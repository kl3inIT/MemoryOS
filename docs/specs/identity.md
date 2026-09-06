# Identity and IAM authorization contract

## Purpose

The closed `io.memoryos.iam` capability owns identity, Tenant membership, invitations, Users, Groups and authorization. It maps validated external OIDC identities to stable internal `ActorId` values. Other capabilities own authority and data by `ActorId`, not provider, email, username or token. JPA lifecycle entities remain internal; public roots expose identifiers and narrow operation/read contracts.

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

Browser authentication validates the OIDC Authorization Code + PKCE callback, then resolves the same exact pair. The actor must also have active Tenant authority, or an eligible pending invitation must atomically establish it. Only after admission succeeds does the callback record the provider's latest nullable display-name/email observation and email-verification flag against that Actor and exact binding. It then replaces the provider principal with an application principal containing only `ActorId`, explicitly overwrites the HTTP-session security context, and discards the authorized client. Unknown or unauthorized identities receive `ACCESS_NOT_PROVISIONED` and no durable authenticated session. Authentication performs no just-in-time Actor or membership creation outside the authorized invitation transaction.

Every `/api/**` endpoint accepts either a bound bearer identity or an `ActorAuthenticationToken` restored from the JDBC-backed browser session. The API security chain may read an existing session but never creates one and never saves bearer authentication into one.

When a bearer token accompanies a browser cookie, the bearer determines that request's identity and does not replace the saved browser identity. An invalid bearer fails authentication rather than falling back to the cookie. Provider role/scope claims do not grant IAM capabilities; effective authority comes from current Tenant and Group state.

Application membership/Group revocation and provider session revocation are separate contracts. Current local JWT validation does not introspect Keycloak, and Actor-only browser sessions do not implement incoming OIDC logout. Application-initiated logout invalidates the local session and returns the provider logout location; this does not establish propagation from Keycloak or an upstream IdP. The configured session timeout defaults to 30 minutes of inactivity, not an absolute authentication lifetime.

`GET /api/identity/me` returns one repeatable-read IAM presentation/authority projection. For example, an admitted Actor with explicit `GROUPS_READ`:

```json
{
  "actorId": "<uuid>",
  "tenant": {
    "displayName": "Tasco",
    "role": "MEMBER"
  },
  "capabilities": ["GROUPS_READ"],
  "scopedCapabilities": [],
  "authorizationVersion": 7
}
```

Capabilities come from current Group grants, not membership role. A Basic-only active member has empty capability sets. A bound Actor without active membership receives `tenant: null`, empty global/scoped sets and revision `0`; ordinary browser admission still requires active Tenant authority. The projection suppresses forbidden UI but never authorizes a server operation. Every protected API resolves durable authority for the operation. Sessions retain no capabilities, Group edges or revision.

## Account classification and Group authority

`AccountType` belongs to Actor and is neither a membership role nor a permission. Only persisted `STANDARD` interactive accounts are implemented; Users exposes that classification for membership rows. Invitations do not fabricate an Actor or account classification before admission. No bot, anonymous, service-account, SCIM or Requests creation/control surface exists.

Explicit capabilities are `IAM_ADMIN`, `USERS_MANAGE`, `GROUPS_READ`, `GROUPS_MANAGE`, `SOURCES_READ`, `SOURCES_MANAGE` and `SOURCES_DELETE`. Authority is the union of active membership's Group grants, expanded centrally: `IAM_ADMIN` implies all implemented capabilities; `GROUPS_MANAGE` implies `GROUPS_READ`; `SOURCES_MANAGE` and `SOURCES_DELETE` each imply `SOURCES_READ`. No effective-permission cache or role-derived fallback exists.

Every Tenant has protected Admin and Basic system Groups. Only Admin may carry `IAM_ADMIN`, and Admin accepts no other explicit grant; Basic accepts none. The owner belongs to both. Invitation acceptance adds only a non-manager Basic edge. Ordinary Groups carry explicit non-admin grants and an `isManager` flag on individual membership edges, not a global manager role. Tenant-qualified keys prevent cross-Tenant associations.

`IamAuthorization` resolves `GLOBAL`, `SCOPED` or `NONE`. A global capability authorizes its operation across the Tenant. Without it, an ordinary-Group manager receives only eligible scoped Group/Source operations on concrete associated resources. Invalid or inactive authority fails closed. Read projections filter scope before exposing rows or totals.

| Operation | Required authority |
| --- | --- |
| Users, invitations, member activation/deactivation | Global `USERS_MANAGE`; protected owner/final-active-`STANDARD`-admin guards still apply |
| Group list/detail/member reads | Global `GROUPS_READ` or own managed ordinary Group |
| Create/rename/delete ordinary Groups | Global `GROUPS_MANAGE`; system Groups remain protected |
| Add/remove ordinary Group members | Global `GROUPS_MANAGE` or own managed Group, subject to delegation and protected-membership checks |
| Assign/remove an ordinary Group's manager flag | Global `GROUPS_MANAGE`; scoped management cannot change manager status |
| Replace explicit Group grants | Global `IAM_ADMIN` |
| Change Admin membership or replace a User's ordinary Groups | Global `IAM_ADMIN`; preserve system edges and retained manager flags in ordinary-membership replacement |
| Source operations and associations | The [Connector management matrix](connector.md#management-authority-and-group-associations) |

A scoped manager cannot remove another manager membership, including their own, as an indirect manager-status mutation. Group commands revalidate delegation under the authority lock. Protected Groups cannot be deleted or renamed, the configured owner cannot lose protected authority, and the final active `STANDARD` administrator cannot be removed or deactivated.

Permission mutations serialize on the Tenant row using an exclusive lock and advance `authorization_version` in the same transaction. Protected resource writes take the corresponding shared lock, then reauthorize scope before committing. Provider IO occurs outside the lock and is followed by reauthorization. JPA lifecycle writes and concrete JDBC projections/locks share one transaction manager and DataSource; Flyway owns DDL, Hibernate validates, open-in-view and ORM caches are disabled.

## Browser-session convergence

One persistent authenticated frontend layout owns the current-identity query across application routes. Internal route changes do not replace the browser document or remount the session boundary. The identity query refetches whenever the browser returns to the foreground because the JDBC-session cookie can change in another tab.

The frontend QueryClient fingerprints Actor, Tenant role, both capability sets and `authorizationVersion` across boundary remounts. Changed authority resets each non-identity query before cache removal so mounted observers stop showing revoked data, then clears mutation state. `ApplicationSessionProvider` remains keyed by `actorId` for cross-Actor local-state isolation rather than blanket revision remounts that would destroy one-time invitation results. Identity `401` performs the same purge; private-query/mutation `401` resets identity. Private `403` invalidates the canonical identity query with active refetch. Revision-only changes purge private data even when capability tokens remain unchanged; an ordinary denied operation with unchanged identity retains private state.

## Persistence

`JpaExternalIdentityRegistry` implements exact binding resolution and authorized registration through concrete IAM persistence. Registration atomically creates a `STANDARD` Actor and binding or returns the Actor already bound to that identity. Invitation acceptance uses the stable Actor lock to serialize competing membership grants. `JpaActorProfileRecorder` writes admitted profile observations. Lifecycle entities are not exported, and bounded projections/explicit authorization locks remain concrete JDBC repositories.

V13 adds one optional latest-observation row per Actor in `actor_profiles`. `display_name` and `email` are nullable; `email_verified`, `observed_at` and exact `issuer`/`subject` provenance are required. A composite foreign key requires provenance to name an existing binding for the same Actor. Profile recording creates no Actor, membership or provider credential state. V14 adds Account Type and authorization revision and invalidates existing serialized Spring Sessions for the `ActorId` namespace cutover. V15 adds the protected Group/grant model and seeds existing memberships.

Flyway owns the schema under `core/src/main/resources/db/migration/`. Applied migrations are immutable.

## Binding lifecycle boundary

No generic account-linking endpoint, administrative binding endpoint, provisioning CLI or unauthenticated identity write surface exists. Initial Tenant bootstrap and authorized invitation acceptance inside IAM are the only production binding writers; ordinary authentication never creates an Actor.

## Local-Keycloak invitation provisioning

Keycloak is the fixed MemoryOS authentication plane and the intended enterprise OIDC/SAML broker. The checked-in realm reconciliation configures application clients and local invitation provisioning; it does not configure or verify an upstream enterprise IdP. MEM-59 owns that future broker/admission flow and its separate simulator and real-provider acceptance. MemoryOS PostgreSQL remains authoritative for Actors and application authority. IAM owns the concrete Keycloak Admin Client used by invitation provisioning; no provider-neutral provisioning adapter exists, and the Users directory never administers provider accounts.

Invitation issue resolves one exact normalized email in the `memoryos` realm. An absent user is created enabled with email-as-username, `emailVerified=false`, minimal MemoryOS origin evidence, and bounded `VERIFY_EMAIL` plus `UPDATE_PASSWORD` required actions. A MemoryOS-created unverified user is reused on retry. An exact existing verified user is reused without required actions or password reset. An unrelated unverified or ambiguous account fails closed.

Realm reconciliation declares `memoryos.provisioned` as an optional, single-valued `true` attribute with admin-only view and edit permissions. This preserves provisioning provenance under Keycloak's managed user-profile policy without changing other attributes. Accounts created before this declaration may lack provenance; they remain fail-closed and are never automatically relabeled. A recipient can still complete an existing provider activation flow, after which the verified-account reuse rule applies.

The action email returns to the additional exact `/invite/activate` browser URI without an invitation secret, invitation ID, or parallel nonce. The API uses a dedicated realm-local `memoryos-user-provisioner` service account, explicit bounded HTTP-client timeouts, and managed credentials that are never logged or persisted. A Keycloak account alone grants no MemoryOS authority.

## Runtime configuration

The API requires the OIDC issuer/JWKS/audience, confidential browser client secret, datasource credentials, initial Tenant values, and Keycloak invitation-provisioner values listed in the [development runtime runbook](../runbooks/development-runtime.md). Keycloak reconciliation requires the exact browser callback plus the exact `/invite/activate` return URI and rejects wildcard redirect values. Missing or invalid values fail startup or reconciliation. Plain HTTP JWKS and activation URIs are accepted only for literal loopback test hosts; production uses HTTPS. Session cookies default to `HttpOnly`, `Secure`, and `SameSite=Lax`.
