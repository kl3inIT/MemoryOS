# MEM-8 design: initial Organization owner browser session

## Outcome

A deployment-configured initial owner can start MemoryOS, create or replay exactly one Organization and default Workspace, authenticate through confidential Keycloak Authorization Code + S256 PKCE, and receive a durable server-side session scoped by active Organization authority.

This increment proves one production flow only:

```text
exact Keycloak issuer + subject
  -> singleton Organization bootstrap
  -> Organization OWNER + default-Workspace ADMIN
  -> browser Authorization Code + PKCE
  -> exact ActorId resolution + active-membership gate
  -> ActorId-only JDBC session
  -> authenticated product root
```

Invitation onboarding is excluded and tracked by MEM-12.

## Invariants

- `ActorId` is the only application identity; email, username, realm role, and broker claims never grant access.
- The owner is a named Keycloak user whose stable subject is supplied by deployment configuration, not whichever user logs in first.
- MemoryOS never stores or receives the user's password and never receives Keycloak administrator credentials.
- Exactly one initial Organization aggregate is published per installation database.
- Organization, default Workspace, Organization membership, Workspace membership, actor, and exact external binding are committed atomically.
- The singleton state row is locked before empty-store validation; concurrent replicas cannot create competing aggregates.
- Replay with exact configuration succeeds without mutation; drift or incomplete state fails startup.
- Browser admission requires an active Organization membership in an active Organization.
- The durable browser principal contains only `ActorId`; provider access, refresh, and raw ID-token state is discarded.
- Successful login rotates the session identifier and explicitly saves the replaced security context.

## Bootstrap design

Flyway creates `organization_bootstrap_state` with one row whose `initial_organization_id` begins `NULL`. The Spring-managed `InitialOrganizationBootstrapper` is transactional. An API `ApplicationRunner` always invokes it after configuration binding and Flyway migration.

Inside one transaction it:

1. locks row `id = 1` with `SELECT ... FOR UPDATE`;
2. if unpublished, requires an empty Organization store;
3. resolves or creates the exact owner actor binding;
4. inserts the active Organization and default Workspace;
5. publishes `default_workspace_id`;
6. inserts active Organization `OWNER` and Workspace `ADMIN` memberships; and
7. publishes `initial_organization_id` in the singleton row.

If already published, it loads the aggregate by the stored ID and verifies every configured value, identity binding, status, relationship, and membership cardinality. It never mutates drift. Any insert or verification failure rolls back the transaction.

## Browser design

The deployment script creates or reuses the named local initial owner with a temporary first-login password and no Keycloak administration role. It reports the stable subject and reconciles the `memoryos-web` secret from managed environment values. Passwords and secrets use environment/stdin channels and are never printed or placed in command arguments.

The browser security chain always exists in production. The OAuth2 authorization resolver attaches a fresh PKCE verifier and S256 challenge. The success handler resolves exact OIDC issuer/subject, gates on active Organization membership, replaces the provider token with `ActorSessionAuthenticationToken`, rotates the session through Spring Security's configured fixation strategy, explicitly persists the new context, and removes provider authorization state. Failure invalidates the partial session and redirects to `/access-not-provisioned`.

## Deployment design

The production proof uses one immutable, commit-labelled `memoryos-api` image built as a layered Spring Boot JAR. The runtime is non-root, read-only apart from bounded `/tmp`, capability-free, protected by `no-new-privileges`, health-checked, log-rotated, resource-bounded, and attached only to the existing `shared-infra` and `proxy-network` Docker networks. A loopback-only host port supports operator health checks and the browser-verification tunnel without publishing plaintext traffic.

The API trusts framework-processed forwarding headers so OAuth2 callback generation uses the HTTPS origin supplied by the reverse proxy. The browser client redirect allowlist must contain the exact verification or production origin; wildcard redirects remain forbidden.

## Evidence boundary

MEM-8 does not ship a generic audit module. Bootstrap publication is proven by singleton state, exact aggregate state, binding, memberships, and deployment change reference. Invitation/admin/context-switch evidence belongs to the increments that expose those mutations. See [ADR 0003](../../../decisions/0003-defer-audit-until-evidence-consumer.md).

## Exclusions

No invitation URLs, invitation database state, member administration, tenant switcher, self-service Organization creation, broker-specific authorization, source ACL, audit reader/export, worker deployment, generic deployment platform, or public DNS/proxy automation is part of MEM-8.