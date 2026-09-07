# ADR 0007: Unified JPA IAM and group-based authorization

## Status

Accepted. Implementation began in the combined MEM-55/MEM-36 change after explicit user approval of the clean early-project target over migration effort.

## Context

Users management adds profile provenance and membership lifecycle to Identity, Tenant and Invitation. Groups adds member-manager edges, capability grants, administrator survival and onboarding baseline. These operations evolve and transact together. Preserving three small closed modules solely to minimize migration would require increasingly repetitive contracts and mappings, or cross-module entity navigation that violates their declared boundaries.

The user approved a cohesive IAM model, JPA lifecycle persistence, Account Type classification and Onyx-aligned Users/Groups interactions. Existing MEM-55 work must be retained, and both issues are delivered on one branch. Sources, documents, object storage and worker ingestion remain separate capabilities.

## Decision

Consolidate Identity, Tenant, Invitation, Users and Groups under one closed `io.memoryos.iam` capability. Publish identifiers and narrow operation/read contracts from its root. Keep entities, ORM repositories, SQL projections and lock mechanics internal. Remove old package implementations and aliases and migrate every consumer.

Use JPA for IAM lifecycle entities, including Actor, external binding, profile, Tenant, Tenant membership, invitation, bootstrap state, Group, explicit Group membership and Group capability grant. Keep existing UUIDs and exact binding/provenance constraints. Do not manufacture a second User identity, a domain-model copy of every entity, or an interface/Default pair for every list. Use bounded projections and native queries where they express the database operation more directly.

Account Type classifies the Actor; it is not an administrator role. The implemented interactive account lifecycle is STANDARD. Unsupported bot, anonymous, external-permission and service-account creation/credential flows are not exposed as working capabilities.

Product authority is the union of explicit Group grants plus documented baseline authority, expanded centrally for implemented implications. Manager status belongs to a specific Group membership. GLOBAL, SCOPED and NONE remain distinct; scoped resource checks are enforced before pagination and inside write transactions. The protected Admin Group supplies administration; Basic adds no administration or Source clearance. OWNER/MEMBER remain bootstrap/membership semantics, not a parallel product permission system.

Do not introduce Onyx's materialized permission cache initially. Resolve current durable authority, including active Tenant/application membership. IAM permission mutations take an exclusive Tenant row lock; protected Source writes take a shared lock before their current authority/scope check and hold it through database mutation. Source association changes take the exclusive lock. Provider IO occurs outside these locks and the commit operation reauthorizes.

Connector owns Tenant-qualified Source–Group associations. A Group grant is not document ACL, and neither administrator authority nor manager scope bypasses source ACL, freshness, principal mapping or active resource/connection checks. Group deletion removes associations, not Source/document content or Actor identity.

API and worker explicitly compose JPA support for IAM repositories and JDBC access to the same PostgreSQL authority. API owns Flyway and Hibernate validates, rather than changes, the schema. Worker claims, leases, fencing and bulk persistence stay JDBC-first under deliberate transaction-manager composition. Worker loads only IAM beans needed by its real services, not Keycloak or invitation application workflows. Disable open-in-view and permission caches.

## Consequences

- IAM relationships can use real ORM associations inside a coherent boundary without leaking entities into other capabilities, REST or sessions.
- Existing identity, profile, invitation and membership contracts require behavioral migration, not a read-only ORM facade over parallel lifecycle writers.
- Relocating ActorId changes the Java-serialized principal class name. The cutover intentionally invalidates existing Spring Session rows and requires login again; stable Actor IDs/bindings are retained. Do not deploy mixed old/new API versions during this namespace cutover.
- Group grant and membership changes must preserve union semantics, owner/last-administrator protection and transaction ordering under concurrency.
- Onyx informs the interaction model and permission semantics, not speculative product controls, legacy roles, upstream authentication storage or unmeasured caching.
- Source/provider/ingestion choices from ADR 0006 remain in force. This decision supersedes only the earlier policy of keeping Identity/Tenant/Invitation persistence separated and unchanged for MEM-36.

## References

- [Combined implementation design](../increments/active/mem-36-iam-jpa/design.md)
- [Implementation plan](../increments/active/mem-36-iam-jpa/plan.md)
- [Source persistence decision](0006-shared-connector-bundle-and-jdbc-source-persistence.md)
