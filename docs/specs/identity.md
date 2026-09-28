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
- Bearer authentication never creates an actor for an unknown binding; browser creation requires authorized invitation acceptance or explicitly trusted JIT admission.

PostgreSQL enforces these invariants through the `(issuer, subject)` primary key, the foreign key to `actors.id`, and `ON DELETE RESTRICT`.

## Authentication

Bearer authentication validates JWT signature, exact configured issuer, configured audience, expiry, not-before, and nonblank `sub`. It resolves exact `(iss, sub)` through `ExternalIdentityResolver`; a missing binding fails with `401`.

Browser authentication validates the OIDC Authorization Code + PKCE callback, then hands IAM's `SignInAdmission` the exact pair, the ID token's provider claim, the profile observation and the session's invitation continuation or activation marker. Admission preserves the existing active-member path, otherwise selects trusted browser JIT when eligible, otherwise retains eligible invitation acceptance. Only after admission succeeds is the provider's latest nullable display-name/email observation and email-verification flag recorded against that Actor and exact binding; admission, that profile observation and the `auth.login` event commit in one transaction, so a failed profile write leaves no new membership or consumed invitation behind. A refusal rolls that transaction back and is recorded separately as `auth.login_failure`: `DENIED` with reason `NOT_ADMITTED` when neither JIT nor an invitation admits the identity, `FAILURE` with `UNREADABLE_IDENTITY` when the token carries no usable issuer or subject, and `FAILURE` with `INVITATION_<reason>` when a followed invitation or activation cannot be used. The callback maps the sealed outcome to the Actor session and `/`, `/access-not-provisioned` or `/invitation?reason=…`, and invalidates the partial session on any refusal or failure. It then replaces the provider principal with an application principal containing only `ActorId`, explicitly overwrites the HTTP-session security context, and discards the authorized client. Unknown or unauthorized identities receive `ACCESS_NOT_PROVISIONED` and no durable authenticated session.

Every `/api/**` endpoint accepts either a bound bearer identity or an `ActorAuthenticationToken` restored from the JDBC-backed browser session. The API security chain may read an existing session but never creates one and never saves bearer authentication into one.

When a bearer token accompanies a browser cookie, the bearer determines that request's identity and does not replace the saved browser identity. An invalid bearer fails authentication rather than falling back to the cookie. Provider role/scope claims do not grant IAM capabilities; effective authority comes from current Tenant and Group state.

Application membership/Group revocation and provider session revocation are separate contracts. Current local JWT validation does not introspect Keycloak, and Actor-only browser sessions do not implement incoming OIDC logout. Browser login records the ID token `sid` (the Keycloak user session id) in the HTTP session; provider tokens are still discarded. Application-initiated logout first ends that Keycloak session through the realm admin API (`DELETE /sessions/{sid}`, authorized by the provisioner service account `manage-users` role), then invalidates the local session and returns `204` without a location, so the browser shows no provider logout page. When no `sid` was recorded or Keycloak cannot end the session, the response carries the provider logout location instead and sign-out completes through Keycloak. For a brokered session Keycloak ends the upstream IdP session by back-channel logout (a server-side request to the provider's end-session endpoint with the stored upstream ID token) on both paths, but only when the provider has a logout URL and back-channel logout enabled; otherwise the upstream session survives and the next sign-in completes without a login form. The configured session timeout defaults to 30 minutes of inactivity, not an absolute authentication lifetime.

The browser application treats a `401` from `GET /api/identity/me` as signed out and navigates to `/oauth2/authorization/memoryos` immediately, showing the brand loader without an intermediate sign-in screen; because logout returns to `/`, a completed logout lands on the provider login. A still-signed-out result after the provider round trip redirects again rather than showing a manual gate, relying on the provider session to complete the sign-in.

`GET /api/identity/me` returns one repeatable-read IAM presentation/authority projection. For example, an admitted Basic member with an additional explicit `GROUPS_MANAGE` grant:

```json
{
  "actorId": "<uuid>",
  "uiLanguage": "en",
  "tenant": {
    "displayName": "Tasco",
    "role": "MEMBER"
  },
  "capabilities": ["SYSTEM_BASIC", "SEARCH_READ", "CHAT_READ", "CHAT_WRITE", "IMAGE_GENERATE", "LLM_GATEWAY_USE", "GROUPS_MANAGE", "GROUPS_READ"],
  "scopedCapabilities": [],
  "authorizationVersion": 7
}
```

Capabilities come from current Group grants, not membership role. A Basic-only active member has global `SYSTEM_BASIC` and its five derived child tokens, with no scoped or administrative capabilities. Reserved future tokens describe permission vocabulary, not feature availability. A bound Actor without active membership receives `tenant: null`, empty global/scoped sets and revision `0`; ordinary browser admission still requires active Tenant authority. The projection suppresses forbidden UI but never authorizes a server operation. Every implemented protected API resolves durable authority for the operation. Sessions retain no capabilities, Group edges or revision.

## Trusted browser JIT admission

The durable `jit_allowed_provider` table is the JIT allowlist, keyed by the exact Keycloak identity-provider alias. `memoryos.identity.jit.allowed-provider-aliases`, supplied by optional `MEMORYOS_JIT_ALLOWED_PROVIDER_ALIASES`, is a startup seed only: its values are inserted idempotently and runtime administration owns the table afterwards. The configured Keycloak issuer remains the only trusted issuer. JIT requires `memoryos_identity_provider` directly in the validated ID token to be a strict String exactly matching an allowlisted alias; Tasco requires explicit `tasco` opt-in. Missing, blank, non-String, or unlisted values do not select JIT. UserInfo, access-token claims, request parameters, email domains, and provider roles/scopes cannot substitute.

For a trusted browser identity without active membership, the IAM application transaction locks the configured `MEMORYOS_TENANT_ID` Tenant before Actor, rejects an inactive Tenant or inactive/incompatible existing membership, creates or reuses the exact `(issuer, sub)` STANDARD Actor, and grants only active MEMBER plus a non-manager Basic edge. The concrete persistence layer owns SQL and locks. New Actor/binding/membership/Basic state commits atomically or rolls back; repeat and concurrent same-identity admission are idempotent. JIT never reactivates an inactive member or elevates an existing role/Group edge.

Email and `email_verified` are profile observations, not JIT eligibility or linking inputs. A verified email is matched, in lower case, against the provider permissions of Auto Sync Sources only; it never binds, admits or JIT-provisions an identity ([ADR 0011](../decisions/0011-verified-email-source-permission-matching.md)). JIT never creates, consumes, or modifies an invitation. Existing active-member admission has precedence; when the provider does not qualify, the existing invitation path retains its own verified-email rules. Bearer authentication remains resolve-only even if a token contains this provider claim.

Realm reconciliation maps the Keycloak User Session Note `identity_provider` into String ID-token claim `memoryos_identity_provider` on `memoryos-web` only. Access-token, UserInfo, introspection, and token-response emission are disabled. This mapper neither changes an upstream provider nor grants authority by itself. [MEM-59](../increments/completed/mem-59-tasco-jit/design.md) separates pending simulator verification from actual Tasco acceptance.

`SYSTEM_ADMIN` actors manage upstream OIDC identity providers through `/api/identity-providers`: list, issuer discovery, create, update (immutable alias and issuer; write-only client secret), and delete. The API is a management proxy over the realm's Keycloak Admin REST identity-provider resource via the shared `memoryos-user-provisioner` service account, which holds `manage-users` and `manage-identity-providers`. Create/update optionally grant or revoke the alias's JIT allowlist row in the same authorized operation; delete removes the alias first. Create and update set Keycloak `backchannelSupported=true` whenever the provider has a logout URL and remove it otherwise, so application sign-out also ends the upstream session. Provider IO occurs outside the authorization lock and durable writes re-authorize under the exclusive Tenant lock. See [MEM-95](../increments/completed/mem-95-idp-admin/design.md).

## Account classification and Group authority

`AccountType` belongs to Actor and is neither a membership role nor a permission. Only persisted `STANDARD` interactive accounts are implemented; Users exposes that classification for membership rows. Invitations do not fabricate an Actor or account classification before admission. No bot, anonymous, service-account, SCIM or Requests creation/control surface exists.

Code-defined capabilities include SYSTEM_BASIC, SYSTEM_ADMIN, five assignable administrative capabilities (USERS_MANAGE, GROUPS_MANAGE, SOURCES_MANAGE, MODELS_MANAGE, MCP_MANAGE), two agent capabilities (AGENTS_CREATE, and AGENTS_MANAGE which implies it; Onyx `ADD_AGENTS`/`MANAGE_AGENTS`, implied by SYSTEM_ADMIN and not by SYSTEM_BASIC, as the Onyx Enterprise default), derived GROUPS_READ/SOURCES_READ/SOURCES_DELETE and five Basic child tokens. SYSTEM_ADMIN expands to every enum value; SYSTEM_BASIC implies SEARCH_READ, CHAT_READ, CHAT_WRITE, IMAGE_GENERATE and LLM_GATEWAY_USE. GROUPS_MANAGE implies GROUPS_READ; SOURCES_MANAGE implies Source read/delete. Derived tokens cannot be persisted directly. Chat enforces CHAT_READ for transcript reads (conversation list/search/history/branches, reply events, shared transcripts) and CHAT_WRITE for creating conversations and send/edit/regenerate/Stop, before ownership checks; owner settings (rename, delete, branch selection, Persona/Project, sharing, feedback) need active membership and ownership only ([MEM-93](../increments/completed/mem-93-chat-search-authorization/design.md)). Image/gateway features remain absent. Full Admin does not bypass active Tenant/membership, protected account guards or independent resource eligibility.

Every Tenant has protected Admin and Basic system Groups storing exactly SYSTEM_ADMIN and SYSTEM_BASIC. The owner belongs to both; invitation acceptance and trusted JIT add only a non-manager Basic edge. Ordinary Groups carry explicit administrative grants and a per-membership manager flag. Tenant-qualified keys prevent cross-Tenant associations. V43 seeds Basic and advances affected revisions; V44 renames system grants while preserving existing model grants. Published main V1–V42 are not rewritten.

V45 revokes standalone GROUPS_READ without upgrading it to GROUPS_MANAGE. Group reads remain derived from Manage groups/Admin or scoped to managed groups. Basic and USERS_MANAGE do not gain global Group reads. Source selection uses its SOURCES_MANAGE-authorized identity projection; Users membership editing remains SYSTEM_ADMIN-only and Group-filter options require current read authority.

AGENTS_CREATE authorizes creating a custom agent. Using one is resource policy, not a capability: the builtin agent, its owner or a member of its owner Group, a public agent, a direct share or a share to one of the actor's Groups. Editing takes ownership, an EDITOR share or a public EDITOR agent; a Group manager edits a private agent whose share Groups they all manage. AGENTS_MANAGE edits any agent and alone controls listing, featuring, display priority, undelete, label administration and public prompt shortcuts. An agent with no owner Group whose owner Actor has no active membership is vacant, and only AGENTS_MANAGE transfers it; revoking membership never deletes an agent. Share Groups must be ordinary Groups ([MEM-119](../increments/completed/mem-119-custom-agents/design.md)).

V46 makes Manage Sources the only Source switch, covering global read/create/configuration/upload/reindex/removal/deletion. It revokes standalone read/delete grants without promotion and advances revisions for affected grants or existing Source managers. V47 adds scoped ownership/operations; V48 enforces ordinary-only Source associations. Scoped rights are concrete resource policy, not global deletion authority; the narrow existing non-public groupless-creator cleanup exception is defined in the Connector matrix. Main MODELS_MANAGE remains independently grantable and is not implied by Source management.

The capability registry supplies labels, descriptions, editability and implications for every enum value, including noneditable derived capabilities. Reserved future permissions are described as such rather than advertised as implemented features. Admin implication metadata includes all other capabilities, excluding itself. Group detail uses an Onyx-style collapsible permission card. System Admin displays exactly one disabled Administrator access (SYSTEM_ADMIN) switch; Basic displays exactly one disabled Basic access (SYSTEM_BASIC) switch. Checked state reflects the persisted explicit grant, not implications or the Group name. No other administrative, Source or derived rows appear for system Groups; ordinary Groups expose editable grants only. Browser labels/descriptions use account-language copy keyed by stable capability IDs; user-provided Group names are not translated and system behavior is keyed by `systemKey`.

The Groups list keeps protected defaults before ordinary Groups, separated visually, with truthful member counts and detail navigation. Its Onyx-aligned layout uses a centered 840px column, scoped light/dark surfaces, an information banner, debounced server search and compact cards. Pagination remains server-driven when needed. Creation appears only with the corresponding authority and inline rename only with the Group's `manage` permission; rename retains the same-origin request guard. No connector, document-set or agent counts are invented from missing API data.

System detail follows Onyx's Edit Group layout: users icon/header, Cancel and settings Save Changes, blue System group notice, readonly Group Name and compact Name/Account Type member table with search, authorized Add/removal and bounded pagination. System Groups do not mount Source-sharing controls or issue their queries. Every mutable Group-detail control is browser-local until the page-level Save Changes action: membership additions/removals, manager status, ordinary-Group name/direct grants, and Source associations commit only from that action. Cancel discards every draft. Existing owner/final-admin protection and ordinary-Group manager operations remain enforced.

Users/Groups identity glyphs use the shared Onyx-derived SVG module: user for Users/Standard accounts, users for Groups, user-manage for administrator/management presentation, plus/check/x for user lifecycle and user-shield for group-manager controls. Navigation, group selectors/associations and invitation identity presentation reuse the same paths. No authority or action semantics are inferred from an icon.

Group Name, Group Members and Group Permissions use the same section-heading typography. The member identity column shows email only (or an explicit unavailable-email state), without display names or Owner/Manager/Inactive badges; Account Type and authorized actions remain. User-profile details belong in Users. Group Permissions has no section subtitle; individual permission descriptions remain. Hiding identity badges does not change protected-owner or manager authorization.

Group summaries carry a typed `permissions` map (`manage`, `manageMembers`, `delete`, `editPermissions`, `manageSources`) computed by `GroupPermissions` from the guard decisions: ordinary Groups allow `manage`/`manageMembers` for global `GROUPS_MANAGE` or their managers, `delete` for `GROUPS_MANAGE`, `editPermissions` for `SYSTEM_ADMIN`, and `manageSources` for global `SOURCES_MANAGE` or their managers; system Groups allow only `manageMembers` for `SYSTEM_ADMIN` and `manageSources` for `SOURCES_MANAGE`. It is a hint only; per-target and count invariants (protected owner, own manager scope, last administrator) stay in the service, and member and user rows have no map. Ordinary Group Permissions is mounted only when the Group grants `editPermissions`. Without it, scoped managers and global Group administrators see neither the section nor readonly switches, and the detail page does not enable the capability-registry query. Loss of the action hides the section on projection refresh. System-group readonly bundle presentation remains unchanged.

The member section has mutually exclusive browse/add modes, one shared search toolbar and only the active mode's list and pagination. Add opens eligible candidates; Done restores the existing member filter without a mutation; adding selected users persists through the guarded command and returns to member browsing. Empty candidate results must not leave the member table or a second pager underneath.

`IamAuthorization` resolves `GLOBAL`, `SCOPED` or `NONE`. A global capability authorizes its operation across the Tenant. Without it, an ordinary-Group manager receives only eligible scoped Group/Source operations on concrete associated resources. Invalid or inactive authority fails closed. Read projections filter scope before exposing rows or totals.

| Operation | Required authority |
| --- | --- |
| Users, invitations, member activation/deactivation | Global `USERS_MANAGE`; protected owner/final-active-`STANDARD`-admin guards still apply |
| Group list/detail/member reads | Global `GROUPS_READ` or own managed ordinary Group |
| Create/rename/delete ordinary Groups | Global `GROUPS_MANAGE`; system Groups remain protected |
| Add/remove ordinary Group members | Global `GROUPS_MANAGE` or own managed Group, subject to delegation and protected-membership checks |
| Assign/remove an ordinary Group's manager flag | Global `GROUPS_MANAGE` or own managed Group; a scoped manager cannot remove their own manager flag |
| Replace explicit Group grants | Global `SYSTEM_ADMIN` |
| Change Admin membership or replace a User's ordinary Groups | Global `SYSTEM_ADMIN`; preserve system edges and retained manager flags in ordinary-membership replacement |
| Source operations and associations | The [Connector management matrix](connector.md#management-authority-and-group-associations) |
| Search and document passage reads | Global `SEARCH_READ`, followed by existing Source/document eligibility checks; this does not grant Source administration or universal document access |

A scoped manager may delegate and revoke manager status and remove another manager's membership in a Group they manage, but cannot remove their own manager flag or their own membership. Group commands revalidate delegation under the authority lock. Protected Groups cannot be deleted or renamed, the configured owner cannot lose protected authority, and the final active `STANDARD` administrator cannot be removed or deactivated.

Permission mutations serialize on the Tenant row using an exclusive lock and advance `authorization_version` in the same transaction. Protected resource writes take the corresponding shared lock, then reauthorize scope before committing. Provider IO occurs outside the lock and is followed by reauthorization. JPA lifecycle writes and concrete JDBC projections/locks share one transaction manager and DataSource; Flyway owns DDL, Hibernate validates, open-in-view and ORM caches are disabled.

## People and Group search

`GET /api/identity/principals?search=&size=` is the one search every sharing surface uses to name people and Groups (agent sharing and transfer, document sets, meetings). IAM's `PrincipalSearch` returns up to `size` (1–50, default 20) active members and up to `size` ordinary Groups of the searcher's own Tenant whose display name, e-mail (people) or name (Groups) contains the stripped search text case-insensitively, ordered by name; system Groups, inactive members and pending invitations never appear. Any active member of the Tenant may search, independent of Chat or any other capability (owner decision 2026-09-25): the picker also serves meetings, whose owners need no Chat authority. A caller without an active membership in an active Tenant is refused with `403`. It is a read over current IAM projections only: each consumer rechecks every person and Group it is finally given. It replaced Chat's `GET /api/chat/persona-share-options`, which was removed.

## Browser-session convergence

One persistent authenticated frontend layout owns the current-identity query across application routes. Internal route changes do not replace the browser document or remount the session boundary. The identity query refetches whenever the browser returns to the foreground because the JDBC-session cookie can change in another tab.

The frontend QueryClient fingerprints Actor, Tenant role, both capability sets and `authorizationVersion` across boundary remounts. Changed authority resets each non-identity query before cache removal so mounted observers stop showing revoked data, then clears mutation state. `ApplicationSessionProvider` remains keyed by `actorId` for cross-Actor local-state isolation rather than blanket revision remounts that would destroy one-time invitation results. Identity `401` performs the same purge; private-query/mutation `401` resets identity. Private `403` invalidates the canonical identity query with active refetch. Revision-only changes purge private data even when capability tokens remain unchanged; an ordinary denied operation with unchanged identity retains private state.

## Persistence

### Account interface language

Account preference persistence uses Spring Data `JpaActorRepository`. A narrow `ActorRefresh` fragment reloads an already-managed Actor under a write lock before mutation; routine reads use `findById`. Authorization and transaction boundaries remain in `ActorLanguageService`.

`actors.ui_language` is an account-owned preference (`vi`/`en`, default `vi`), not an IdP profile observation. GET `/api/identity/me` includes `uiLanguage`, including for authenticated actors without membership. PUT `/api/identity/me/language` accepts `{uiLanguage}` and returns the confirmed value; it requires current active membership and the existing unsafe-request guard. The principal selects the Actor; no actor identifier or administrative capability is accepted from the client. IAM locks membership before the Actor write. This preference never advances `authorizationVersion`.

The web application exposes personal `/settings/general` through the account menu. Bundled i18next/react-i18next resources render Vietnamese/English with English fallback. The identity query remains authoritative, including focus refetch on other devices/tabs; i18next owns presentation only. The picker waits for persistence confirmation, reconciles lost responses through identity refetch, and rejects late results for another Actor. Locale is excluded from authorization fingerprints and never keys a React subtree. Transient background identity failure retains the mounted workspace with a retry notice; authentication/authorization failures remain fail-closed.

Application localization is implemented in [MEM-74/MEM-22](../increments/completed/mem-74-22-i18n-errors/plan.md). The canonical [localization contract](localization.md) defines covered surfaces, preserved user content and acceptance boundaries.

User/invitation/group-edit failures consume the shared typed problem presenter and store safe message descriptors, translated at render time. Known capability codes keep specific messages; unknown codes use the common HTTP taxonomy without exposing server text. Invitation email errors link to the field; other validation failures remain a form summary. Confirmed actions keep failures in their dialog while recovery-link rotation uses its row, avoiding duplicate feedback. Users labels, dates and success notices follow the account locale.


`JpaExternalIdentityRegistry` implements exact binding resolution and authorized registration through concrete IAM persistence. Registration atomically creates a `STANDARD` Actor and binding or returns the Actor already bound to that identity. Invitation acceptance uses the stable Actor lock to serialize competing membership grants. `JpaActorProfileRecorder` writes admitted profile observations. Lifecycle entities are not exported, and bounded projections/explicit authorization locks remain concrete JDBC repositories.

V13 adds one optional latest-observation row per Actor in `actor_profiles`. `display_name` and `email` are nullable; `email_verified`, `observed_at` and exact `issuer`/`subject` provenance are required. A composite foreign key requires provenance to name an existing binding for the same Actor. Profile recording creates no Actor, membership or provider credential state. V14 adds Account Type and authorization revision and invalidates existing serialized Spring Sessions for the `ActorId` namespace cutover. V128 deletes them again because [ADR 0015](../decisions/0015-capability-module-map.md) moved `ActorId` to `io.memoryos.shared` and the session principal `IdentityContext` to the `io.memoryos.iam` root. V15 adds the protected Group/grant model and seeds existing memberships.

Flyway owns the schema under `core/src/main/resources/db/migration/`. Applied migrations are immutable.

There are 48 migrations in the merged layout. Historical local Basic/scoped-source V37–V42 map to current V43–V48; they must not be confused with main's published V37–V42. A database already carrying the old feature history cannot start this layout until deliberate data-preserving history/schema reconciliation; no automatic reset or checksum repair is authorized. See the [migration mapping and cutover warning](../../ARCHITECTURE.md#data-ownership-and-consistency). Prior verification is historical, not proof of the current merge.

## Binding lifecycle boundary

No generic account-linking endpoint, administrative binding endpoint, provisioning CLI or unauthenticated identity write surface exists. Initial Tenant bootstrap, authorized invitation acceptance, and explicitly trusted browser JIT admission inside IAM are the production binding writers; bearer authentication never creates an Actor.

## Local-Keycloak invitation provisioning

Keycloak is the fixed MemoryOS authentication plane and enterprise OIDC/SAML broker. The checked-in realm reconciliation configures application clients, the browser provider-note mapper, and local invitation provisioning; it does not configure or verify an upstream enterprise IdP. MEM-59 keeps simulator and actual Tasco acceptance separate. MemoryOS PostgreSQL remains authoritative for Actors and application authority. IAM owns the concrete Keycloak Admin Client used by invitation provisioning; no provider-neutral provisioning adapter exists, and the Users directory never administers provider accounts.

Invitation issue resolves one exact normalized email in the `memoryos` realm. An absent user is created enabled with email-as-username, `emailVerified=false`, minimal MemoryOS origin evidence, and bounded `VERIFY_EMAIL` plus `UPDATE_PASSWORD` required actions. A MemoryOS-created unverified user is reused on retry. An exact existing verified user is reused without required actions or password reset. An unrelated unverified or ambiguous account fails closed.

Realm reconciliation declares `memoryos.provisioned` as an optional, single-valued `true` attribute with admin-only view and edit permissions. This preserves provisioning provenance under Keycloak's managed user-profile policy without changing other attributes. Accounts created before this declaration may lack provenance; they remain fail-closed and are never automatically relabeled. A recipient can still complete an existing provider activation flow, after which the verified-account reuse rule applies.

The action email returns to the additional exact `/invite/activate` browser URI without an invitation secret, invitation ID, or parallel nonce. The API uses a dedicated realm-local `memoryos-user-provisioner` service account, explicit bounded HTTP-client timeouts, and managed credentials that are never logged or persisted. A Keycloak account alone grants no MemoryOS authority.

## Keycloak browser theme

The `memoryos` realm uses the repository-owned `memoryos` login theme for sign-in, password recovery, required password update, email verification, informational completion, and action-token error pages. It extends the pinned Keycloak 26.7 `keycloak.v2` theme using CSS, messages, and local images only; it does not copy or replace FreeMarker templates. Keycloak therefore remains authoritative for form targets, session and action-token state, validation, password visibility, provider-specific controls, and accessibility semantics.

The shared runtime mounts this theme read-only. Reconciliation fails unless the running server advertises the theme and the realm converges to `loginTheme=memoryos`. This selection applies only to the `memoryos` realm and changes no issuer, subject, client, callback, or application-authorization contract.

## Runtime configuration

The API requires the OIDC issuer/JWKS/audience, confidential browser client secret, datasource credentials, initial Tenant values, and Keycloak invitation-provisioner values listed in the [development runtime runbook](../runbooks/development-runtime.md). Keycloak reconciliation requires the exact browser callback plus the exact `/invite/activate` return URI and rejects wildcard redirect values. Missing or invalid values fail startup or reconciliation. Plain HTTP JWKS and activation URIs are accepted only for literal loopback test hosts; production uses HTTPS. Session cookies default to `HttpOnly`, `Secure`, and `SameSite=Lax`.
