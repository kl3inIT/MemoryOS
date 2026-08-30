# MEM-24 design: self-hosted single-Tenant backend cutover

## Outcome

MemoryOS clean-cuts the implemented Organization backend to the canonical Tenant model while preserving every existing UUID, membership, invitation, Connector, Document, operation, and session authority. One self-hosted deployment owns exactly one Tenant. The browser does not select a Tenant; Arconia Web binds the deployment Tenant to every HTTP request through Fixed Tenant Resolution, while durable membership and capability checks remain authorization authority.

The current V1–V5 migrations remain immutable historical evidence. V6 migrates the active schema and the same release migrates all Java, API, worker, OpenAPI, frontend, test, and current-runtime documentation references. No Organization alias, compatibility view, dual-read/write path, or deprecated API survives the cutover.

## Current defects

The implemented runtime still exposes Organization as the hard tenant through package names, Java types, property names, JSON fields, tables, columns, indexes, foreign keys, OpenAPI, React session state, worker records, and documentation. That conflicts with the accepted single-Tenant backend contract and leaves Arconia request propagation absent.

A configuration-only rename would be unsafe because tenant identity participates in authorization, bootstrap serialization, invitation acceptance, every Connector/Document association, and worker claim fencing. A clean migration must preserve IDs and constraints while preventing old binaries from running against the Tenant schema.

## Deployment contract

Each deployment has one required stable `MEMORYOS_TENANT_ID`. The same UUID must be present in:

```text
MEMORYOS_TENANT_ID
        =
tenants.id
        =
tenant_bootstrap_state.tenant_id
```

`tenants.deployment_slot` is constrained to the singleton value `1` and uniquely identifies the only Tenant row. There is no runtime Tenant creation endpoint, switching, route segment, request header selection, or implicit default UUID.

Startup behavior:

1. Flyway applies V6 before application bootstrap.
2. Deployment configuration supplies Tenant ID, display name, slug, exact owner subject, and change reference.
3. Bootstrap locks the singleton state, creates or verifies the exact Tenant and OWNER membership atomically, and publishes the configured ID.
4. Missing configuration, an extra Tenant row, ID/name/owner/status drift, or incomplete state fails startup.

The cutover uses a maintenance window. After V6 begins, rollback requires restoring the verified pre-cutover backup; an Organization binary never runs on the Tenant schema.

## Persistence migration

V6 renames the active schema without recreating business rows:

```text
organizations                  -> tenants
organization_bootstrap_state   -> tenant_bootstrap_state
organization_memberships       -> tenant_memberships
organization_invitations       -> tenant_invitations
organization_id                -> tenant_id
```

All active Organization-named constraints and indexes are renamed to Tenant terminology. Composite ownership constraints remain structurally equivalent and keep the same UUID values. V6 adds and populates `tenants.deployment_slot`, enforces `CHECK (deployment_slot = 1)`, `NOT NULL`, and uniqueness, and verifies at most one migrated Tenant before enabling the constraint.

Historical migration filenames and SQL remain unchanged. Current schema tests must prove V6 in PostgreSQL-mode H2 and PostgreSQL Testcontainers.

## Capability cutover

The closed Spring Modulith capability becomes `io.memoryos.tenant`.

Primary public language:

```text
TenantId
TenantAccessResolver
TenantSessionAuthority
TenantMembershipProvisioner
TenantMembershipRole
InvitationAuthority
InvitationTarget
InitialTenantBootstrapper
InitialTenantBootstrapRequest
InitialTenantBootstrapResult
TenantBootstrapConflictException
```

Persistence/application implementations use the same Tenant language. Every caller in identity-session composition, invitation, connector, document, ingestion, API, worker, tests, generated OpenAPI, and web session state migrates in the same change. Spring Modulith allowed-dependency declarations name `tenant`; architecture tests reject the removed `organization` module.

Repositories retain explicit `TenantId` parameters even though the deployment has one Tenant. SQL uses `tenant_id` in every tenant-owned predicate and composite join. This prevents the fixed resolver from becoming hidden persistence authority.

## Arconia integration

The version catalog owns Arconia 0.30.0, compatible with Spring Boot 4.1 and Java 25.

API uses:

```text
arconia-multitenancy-web-spring-boot-starter
```

with:

```yaml
arconia:
  multitenancy:
    resolution:
      fixed:
        enabled: true
        tenant-identifier: ${MEMORYOS_TENANT_ID}
```

Arconia Web supplies `TenantContextFilter`. Fixed resolution is configured before the default header resolver, so a missing or conflicting `X-TenantId` cannot select another Tenant. A MemoryOS `TenantVerifier` checks that the resolved UUID is the active bootstrap Tenant. The filter binds and closes `TenantContext`; Spring Security and application services still authenticate the Actor and verify durable membership/capability before protected effects or disclosure.

Worker does not compose Arconia. Each durable `IndexWork` and `CleanupWork` carries its explicit `TenantId`; coordinator and repository operations consume that identifier directly in tenant-owned predicates. This is the worker's actual isolation boundary and avoids an ambient context with no downstream consumer.

Arconia remains in API composition. Core capability packages, the connector integration bundle, and worker do not import Arconia. `arconia-multitenancy-data-jdbc` is excluded because MemoryOS uses one DataSource and one shared schema; that module routes database-per-Tenant connections rather than adding shared-schema predicates.

## External contract

The current identity/session API clean-cuts from Organization fields to Tenant fields. JSON, generated OpenAPI, generated browser types, route query keys, authority fingerprints, and user-visible administration copy migrate together. There is no compatibility JSON property.

A request without `X-TenantId` behaves according to its authentication contract. A conflicting `X-TenantId` cannot change the bound Tenant or reveal another Tenant. Public endpoints may execute inside the fixed context but gain no membership or capability from it.

## Verification

Behavioral verification must prove:

- V6 preserves all migrated IDs and changes only active schema language;
- one deployment Tenant is database-enforced;
- every active ownership column is `tenant_id`, while the singleton constraint prevents cross-Tenant state;
- bootstrap creates/verifies the configured UUID and fails on mismatch;
- Arconia binds and closes the fixed Tenant for HTTP requests;
- missing/conflicting `X-TenantId` cannot select Tenant state;
- authorization still requires Actor membership/capability;
- worker records retain explicit Tenant identity and every repository operation keeps its `tenant_id` predicate;
- Modulith/ArchUnit see `tenant` and reject `organization`;
- OpenAPI and web session contracts contain Tenant only;
- API and worker start against migrated H2/PostgreSQL test schemas.

## Non-goals

- Multi-Tenant runtime or Tenant switching.
- Tenant creation/provisioning API.
- Schema/database-per-Tenant or Arconia Data JDBC.
- Redis/db-scheduler execution implementation from MEM-40.
- OpenSearch or object-store implementation.
- Compatibility aliases, old JSON fields, dual schema, or rollback binary mode.