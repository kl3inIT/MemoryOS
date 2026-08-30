# Tenant capability contract

## Purpose

Tenant is the hard customer boundary and durable authority root for the self-hosted MemoryOS deployment. One deployment owns exactly one Tenant. `Tasco AI Workspace` remains product language; no persisted Workspace aggregate or membership exists after V4.

The capability root is `io.memoryos.tenant`. Other capabilities depend only on its public identifiers and authority ports. Tenant application services own bootstrap orchestration and authorization; Tenant persistence repositories own SQL, row mapping, locking, and membership mechanics.

## Deployment singleton

The deployment supplies one stable `MEMORYOS_TENANT_ID`. The configured UUID must equal both `tenants.id` and `tenant_bootstrap_state.tenant_id`.

`tenants.deployment_slot` is `NOT NULL`, uniquely constrained, and checked to equal `1`. The database therefore admits at most one Tenant row. No endpoint creates, switches, or selects a Tenant.

The initial aggregate contains:

- one active Tenant with the configured UUID, lowercase slug, display name, bootstrap change reference, and deployment slot;
- one active Tenant `OWNER` membership for the configured exact external identity; and
- one singleton bootstrap-state reference to that Tenant.

## Bootstrap

API startup invokes `InitialTenantBootstrapper` after Flyway migration. The bootstrap request contains the configured Tenant UUID, exact Keycloak owner subject and issuer, slug, display name, and operator change reference.

`tenant_bootstrap_state` contains one migration-created row. Bootstrap locks it with `SELECT ... FOR UPDATE` inside the transaction that:

1. verifies there is no unpublished Tenant;
2. resolves or creates the exact `(issuer, subject)` Actor binding;
3. inserts the configured Tenant UUID;
4. grants Tenant `OWNER`; and
5. publishes the Tenant UUID in the singleton state row.

A concurrent replica waits on the singleton row, then verifies the published aggregate. Identical configuration returns the existing IDs with `created=false`. Configured UUID, owner identity, name, slug, lifecycle status, membership, or change-reference drift fails with `TenantBootstrapConflictException`. Partial writes roll back.

## Request context and authorization

The API uses Arconia Web Fixed Tenant Resolution. Every HTTP request is bound to the configured deployment Tenant; `X-TenantId` is not a selection input. `TenantVerifier` parses the fixed identifier as a UUID and requires the corresponding Tenant to remain active.

Arconia context establishes request scope only. It does not authorize an Actor. Browser and bearer authentication still resolve exact `(issuer, subject)` bindings, and protected operations still require durable active Tenant membership and capability checks.

`TenantAccessResolver` returns the active Tenant display name and current role (`OWNER` or `MEMBER`). A bound bearer Actor without membership receives a null Tenant projection. Browser admission without active Tenant authority fails with `ACCESS_NOT_PROVISIONED` and invalidates the partial session.

The API maps an owner projection to `INVITATIONS_MANAGE` and `SOURCES_MANAGE`; a member receives neither. Projection data is presentation only and is not copied into Spring Session.

## Invitation membership port

Tenant exposes a narrow public port for Invitation: resolve the active owner Tenant, verify that Tenant remains active, detect existing Tenant membership for a target Actor, and grant one Tenant `MEMBER` row. The grant requires an existing transaction so Invitation can coordinate identity binding and lifecycle acceptance atomically without importing Tenant persistence.

## Persistence boundary

V1–V5 are immutable historical migrations. V6 renames active Organization tables, columns, constraints, and indexes to Tenant while preserving UUIDs and business rows, then adds the database singleton constraint. Current runtime SQL uses `tenant_id` in every ownership predicate and composite association.

Rollback after V6 requires restoring the verified pre-cutover database backup and prior image. An Organization-era binary must never run against the Tenant schema.

## Exclusions

The capability does not implement multi-Tenant switching, self-service Tenant creation, owner transfer, billing/seat logic, Groups/SCIM, broker policy, source ACL policy, or audit history. Invitation depends on Tenant only through public authority and membership ports; Tenant never depends on Invitation.
