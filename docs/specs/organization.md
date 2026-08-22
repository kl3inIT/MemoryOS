# Organization capability contract

## Purpose

The organization capability owns the initial tenant authority required to admit a browser-authenticated actor. It currently implements one production flow: deployment-configured creation and replay verification of exactly one Organization, one default Workspace, and the initial owner's memberships.

## Initial aggregate

The aggregate contains:

- one active Organization with a unique lowercase slug, display name, deployment change reference, and default Workspace reference;
- one active default Workspace belonging to that Organization;
- one active Organization `OWNER` membership for the configured actor; and
- one active Workspace `ADMIN` membership for the same actor.

Composite foreign keys prevent a default Workspace or Workspace membership from crossing Organization boundaries. Membership role and status values are constrained by the database.

## Singleton bootstrap

API startup always invokes `InitialOrganizationBootstrapper` after Flyway migration. Deployment configuration supplies the exact Keycloak owner subject, Organization and Workspace names/slugs, and an operator change reference. The owner issuer is the configured MemoryOS OIDC issuer.

`organization_bootstrap_state` contains one migration-created row. Bootstrap locks that row with `SELECT ... FOR UPDATE` inside the same transaction that:

1. verifies the database contains no unpublished Organization;
2. resolves or creates the exact `(issuer, subject)` actor binding;
3. inserts the Organization and default Workspace;
4. publishes the default Workspace reference;
5. grants Organization `OWNER` and Workspace `ADMIN`; and
6. publishes the Organization ID in the singleton state row.

A concurrent replica waits on the singleton row, then verifies the published aggregate. The same configuration returns the existing IDs with `created=false`. Configuration, owner identity, published default-Workspace, status, or initial owner/admin authority drift fails startup with `OrganizationBootstrapConflictException`; additional valid Organizations, Workspaces, and memberships created after bootstrap do not. Partial writes roll back.

Before first API startup, the deployment operator runs the Keycloak reconciliation script with a managed username and one-time temporary password. The script creates or reuses the local user, assigns no Keycloak administration role, and reports its stable subject for API deployment configuration. MemoryOS never receives Keycloak administrator credentials and never stores the user's password.

## Browser admission

After OIDC callback validation, browser login resolves exact `(issuer, subject)`. Access is admitted only when that actor has an active membership in an active Organization. Unknown, unbound, or inactive actors receive `ACCESS_NOT_PROVISIONED`; the partial session is invalidated.

The persisted Spring Security principal contains only `ActorId`. Provider access, refresh, and raw ID-token state is discarded.

## Invitation membership port

Organization exposes one narrow port for Invitation: resolve an active owner and default Workspace, verify the target remains active, detect any existing actor memberships, and grant fixed Organization/default-Workspace `MEMBER` memberships. The grant method requires an existing transaction so Invitation can coordinate identity binding and its own lifecycle row atomically without importing Organization persistence.

## Exclusions

The capability does not own invitation lifecycle, member administration UI, Organization or Workspace switching, self-service Organization creation, broker-specific policy, SCIM, IdP-group provisioning, domain-based JIT access, source ACLs, or audit history. The top-level `invitation` capability may depend on Organization only through the public invitation-authority/membership port; Organization never depends on Invitation. Evidence policy follows [ADR 0003](../decisions/0003-defer-audit-until-evidence-consumer.md).