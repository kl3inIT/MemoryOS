# Organization capability contract

## Purpose

The Organization capability owns the hard customer tenant and operational authority required to admit a browser-authenticated actor. The current production flow bootstraps exactly one Organization and its initial owner. `Tasco AI Workspace` is product language; no persisted Workspace aggregate or membership exists after V4.

## Initial aggregate

The aggregate contains:

- one active Organization with a unique lowercase slug, display name, deployment change reference, and lifecycle status;
- one active Organization `OWNER` membership for the configured actor; and
- one singleton bootstrap-state reference to that Organization.

Organization membership role and status values are database constrained. Future source connections, credentials, ingestion state, knowledge, ACL snapshots, collections, Agents, actions, and conversations must carry `organization_id` directly with foreign-key enforcement.

## Singleton bootstrap

API startup invokes `InitialOrganizationBootstrapper` after Flyway migration. Deployment configuration supplies the exact Keycloak owner subject, Organization name/slug, and operator change reference. The owner issuer is the configured MemoryOS OIDC issuer.

`organization_bootstrap_state` contains one migration-created row. Bootstrap locks that row with `SELECT ... FOR UPDATE` inside the same transaction that:

1. verifies the database contains no unpublished Organization;
2. resolves or creates the exact `(issuer, subject)` Actor binding;
3. inserts the Organization;
4. grants Organization `OWNER`; and
5. publishes the Organization ID in the singleton state row.

A concurrent replica waits on the singleton row, then verifies the published aggregate. Identical configuration returns the existing IDs with `created=false`. Configuration, owner identity, status, owner authority, or change-reference drift fails with `OrganizationBootstrapConflictException`. Partial writes roll back.

## Browser admission and session projection

After OIDC callback validation, browser login resolves exact `(issuer, subject)`. Access is admitted only when that actor has an active membership in an active Organization. Unknown, unbound, or inactive actors receive `ACCESS_NOT_PROVISIONED`; the partial session is invalidated.

`OrganizationAccessResolver` returns the active Organization display name and current production role (`OWNER` or `MEMBER`). No active membership returns no Organization context. More than one active Organization remains an invariant failure until multi-Organization switching is explicitly implemented.

The API maps an owner projection to `INVITATIONS_MANAGE`; a member receives no invitation capability. The projection is presentation only. Durable Organization membership remains authoritative for each command, and neither roles nor capabilities are copied into Spring Session.

## Invitation membership port

Organization exposes one narrow port for Invitation: resolve one active owner Organization, verify that Organization remains active, detect any existing Organization membership for the target Actor, and grant one Organization `MEMBER` row. The grant requires an existing transaction so Invitation can coordinate identity binding and its own lifecycle row atomically without importing Organization persistence.

## Future Groups and source ACL

Groups, when introduced by a concrete product flow, aggregate Organization capabilities and resource grants. They never become tenant or operational ownership containers. Organization membership, Group grants, and Agent/collection grants do not imply document clearance; current source ACL remains the data-read ceiling.

## Migration boundary

V1–V3 remain immutable historical migrations. V4 removes `workspaces`, `workspace_memberships`, `organizations.default_workspace_id`, and `organization_invitations.default_workspace_id`. Runtime code contains no Workspace identifier, configuration, membership, API field, or compatibility shim.

## Exclusions

The capability does not own invitation lifecycle, member administration UI, multi-Organization switching, self-service Organization creation, Groups/SCIM, broker-specific policy, source ACL implementation, or audit history. Invitation depends on Organization only through the public invitation-authority/membership port; Organization never depends on Invitation.