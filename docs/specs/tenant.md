# Tenant lifecycle contract

## Purpose

Tenant is the hard customer boundary and durable authority root for the self-hosted MemoryOS deployment. One deployment owns exactly one Tenant. `Tasco AI Workspace` remains product language; no persisted Workspace aggregate or membership exists after V4.

Identity, Tenant membership, invitations, Users and Groups are one closed `io.memoryos.iam` capability under [ADR 0007](../decisions/0007-unified-jpa-iam-and-group-authorization.md). IAM publishes identifiers and narrow operation/read contracts from its root; JPA entities, repositories, SQL projections and lock mechanics remain internal. Connector, Document, Ingestion and Object Storage stay separate capabilities. In particular, Connector retains JDBC ownership of Sources and Tenant-qualified Source–Group associations as specified by the [Connector contract](connector.md).

## Deployment singleton

The deployment supplies one stable `MEMORYOS_TENANT_ID`. The configured UUID must equal both `tenants.id` and `tenant_bootstrap_state.tenant_id`.

`tenants.deployment_slot` is `NOT NULL`, uniquely constrained, and checked to equal `1`. The database therefore admits at most one Tenant row. No endpoint creates, switches or selects a Tenant.

The initial IAM aggregate contains:

- one active Tenant with the configured UUID, lowercase slug, display name, bootstrap change reference and deployment slot;
- one `STANDARD` Actor bound to the configured exact external identity;
- one active Tenant `OWNER` membership for that Actor;
- protected Admin and Basic system Groups, with the configured owner in both and `IAM_ADMIN` granted only by Admin; and
- one singleton bootstrap-state reference to the Tenant.

## Bootstrap

API startup invokes `InitialTenantBootstrapper` after Flyway migration. The bootstrap request contains the configured Tenant UUID, exact Keycloak owner subject and issuer, slug, display name and operator change reference.

`DefaultInitialTenantBootstrapper` uses the IAM JPA repositories. It pessimistically locks the migration-created `TenantBootstrapStateEntity` and, in one transaction:

1. verifies that no unpublished Tenant exists;
2. resolves or creates the exact `(issuer, subject)` Actor binding;
3. persists the configured Tenant;
4. persists its active `OWNER` membership;
5. idempotently provisions Admin and Basic and their configured-owner memberships; and
6. publishes the Tenant through the singleton state.

A concurrent replica waits on the singleton row and then verifies the published aggregate. Identical configuration returns the existing IDs with `created=false` and repairs missing system-Group bootstrap state idempotently. Configured UUID, owner identity, name, slug, lifecycle status, membership or change-reference drift fails with `TenantBootstrapConflictException`. Identity, Tenant, membership and Group writes roll back together when any bootstrap step fails.

## Request context and IAM authority

The API uses Arconia Web Fixed Tenant Resolution. Every HTTP request is bound to the configured deployment Tenant; `X-TenantId` is not a selection input. Tenant verification requires that fixed UUID to name the active Tenant.

Arconia context establishes request scope only. It does not authorize an Actor. Browser and bearer authentication still resolve an exact `(issuer, subject)` binding, while `JpaTenantAccessResolver` and `IamAuthorization` read current durable active Tenant/membership state. A bound Actor without active membership receives `tenant: null`, empty capability sets and authorization version `0` from `/api/identity/me`. Browser admission without active Tenant authority fails with `ACCESS_NOT_PROVISIONED`.

`OWNER` and `MEMBER` are durable membership and presentation semantics, not product authorization alternatives. Effective capabilities are the fresh union of the Actor's Group grants, expanded through the central IAM implications. The configured owner receives `IAM_ADMIN` through the protected Admin Group; `IAM_ADMIN` implies all implemented capabilities. The server requires global `USERS_MANAGE` for the Users directory, invitation administration and member activation/deactivation, and global `IAM_ADMIN` for protected administration such as changing Admin membership or replacing a User's ordinary Group memberships. Scoped managers receive neither authority. Within their managed ordinary Groups they may manage ordinary members subject to delegation checks, but they cannot manage system Groups, add or remove manager memberships, or change manager flags. Associated-Source scope remains governed by the capability-specific contracts.

The [Identity contract](identity.md) owns the current-identity wire shape and browser convergence rules. Sessions store only `ActorId`, never roles, capabilities, Group membership or the authorization revision. Every protected operation re-reads durable authority. After deactivation, the next protected browser or bearer request is denied; an existing Actor-keyed browser session can observe `tenant: null` and converge to the not-provisioned surface. A fresh OIDC admission while inactive invalidates that partial session. Reactivation restores authority from the unchanged Tenant and Group memberships; if the browser session was invalidated, the Actor authenticates again. No role-based grant is recreated during reactivation.

## Member lifecycle

`TenantMemberManagement.activate(administrator, target)` and `deactivate(administrator, target)` operate only on an existing membership in the administrator's Tenant. Each command obtains the exclusive Tenant authorization lock and requires current global `USERS_MANAGE`; it then locks the target membership. Unknown targets fail without creating an Actor or membership.

The configured `OWNER` cannot be activated or deactivated, even when the requested status already matches. Deactivation additionally refuses the configured owner and the final active `STANDARD` administrator. These guards protect both bootstrap ownership and Admin-Group continuity while allowing an authorized administrator to manage ordinary members. Admin and Basic system-Group rules and scoped-manager delegation remain IAM Group concerns rather than alternate Tenant-role gates.

Activation and deactivation are idempotent for an existing non-owner membership. They change only membership status and timestamps. They do not replace the membership row, Actor, exact identity bindings, account type, Group edges or invitation history, so durable identity and Group-derived authority return intact after reactivation.

## Invitation membership boundary

Invitation acceptance stays inside IAM but uses the narrow `TenantMembershipProvisioner` and `GroupProvisioner` contracts. It must run in the caller's transaction. The provisioner revalidates the active Tenant, checks the latest durable membership state for the locked Actor, and rejects any existing membership, including inactive history. A successful acceptance persists one active `MEMBER` row and one non-manager Basic membership; it never creates or restores Admin membership. Actor binding, Tenant membership, Basic membership and invitation consumption commit or roll back together. The [Invitation contract](invitation.md#acceptance-transaction) owns the complete acceptance flow.

## Persistence and migration boundary

IAM lifecycle persistence is JPA over the existing relational tables. `ActorEntity`, exact binding/profile entities, `TenantEntity`, `TenantMembershipEntity`, `InvitationEntity`, bootstrap state and Group entities live under `io.memoryos.iam.persistence`. Concrete SQL repositories remain appropriate for bounded Users/invitation projections, capability union/scope reads, bulk operations and explicit row locks. JPA and JDBC share the API's transaction manager and DataSource; Flyway owns DDL and Hibernate validates it.

V1–V6 remain immutable historical migrations. V6 renamed active Organization tables, columns, constraints and indexes to Tenant while preserving UUIDs and business rows, then added the database singleton constraint. V13 added profile provenance. V14 added `STANDARD` Account Type and `authorization_version`, restricted membership roles to `OWNER`/`MEMBER`, and deliberately deleted serialized Spring Sessions because the `ActorId` package cutover is not wire-compatible. V15 added Groups and migrated every existing membership to Basic and existing owners to Admin. V16 is Connector-owned and adds Source–Group associations without moving Source persistence into IAM.

Rollback across the V14 namespace/session cutover requires the verified pre-cutover backup and prior image. Old and new API binaries must not run together, and an Organization-era binary must never run against the Tenant schema.

## Exclusions

The lifecycle does not implement multi-Tenant switching, self-service Tenant creation, owner transfer, billing/seat logic, SCIM, broker policy or generic audit history. The admitted interactive account lifecycle is `STANDARD`; unsupported account types are not selectable or accepted. Tenant membership and IAM administration do not confer document-content access or bypass Connector/Source access checks.
