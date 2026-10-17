# 30. A request's Tenant comes from its Actor's membership, without Arconia

Date: 2026-10-17

## Status

Accepted on 2026-10-17 and implemented in the same change. Supersedes the Arconia part of the
[MEM-24 design](../increments/completed/mem-24-tenant-cutover/design.md#arconia-integration).

## Context

MEM-24 added Arconia for three things. Its Web Fixed Tenant Resolution bound every HTTP request to the configured
deployment Tenant, and our `TenantVerifier` rejected the request when that Tenant was not active. Its bootstrap mode
activated the `development` profile for local runs. Its Dev Services started Redis for API tests.

None of them carries weight now:

- No code reads Arconia's tenant context. Every service takes the Tenant from the Actor's active membership
  (`TenantAccessResolver.findActiveTenant`), and a membership in an inactive Tenant is not active, so an inactive
  Tenant already fails closed without the verifier.
- Local development runs against the persistent Compose PostgreSQL and Redis; both Dev Services were disabled in the
  `development` profile, and the worker never had them.
- The worker and channels tests already own their Redis containers through Testcontainers.

Onyx, the first reference, does the same: its middleware takes the tenant from the signed-in user, not from a
deployment setting, and it has no tenancy framework. A hosted multi-Tenant version needs the Tenant per Actor,
which membership already gives.

## Decision

- Remove Arconia: the BOM, `arconia-multitenancy-web-spring-boot-starter`, `arconia-spring-boot`, both Dev Services,
  the `arconia-bootstrap.properties` files and the `arconia.*` configuration.
- A request's Tenant is the active Tenant of its Actor's active membership. `X-TenantId` and similar headers are
  ignored, as before.
- `bootRun` activates `development`; the IntelliJ configurations set `SPRING_PROFILES_ACTIVE=development`.
- An API test that needs Redis registers a Testcontainers Redis through `@DynamicPropertySource`, as its PostgreSQL.

## Consequences

- One framework and its release train fewer, and no filter on every request.
- `MEMORYOS_TENANT_ID` still names the Tenant the deployment bootstraps; it no longer gates requests.
- The local Compose database keeps its `arconia` name and credentials, so existing developer volumes keep working.
