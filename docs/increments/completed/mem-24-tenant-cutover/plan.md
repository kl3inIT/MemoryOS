# MEM-24 implementation plan

## Foundation

- [x] Align Linear MEM-24 and canonical architecture with one self-hosted Tenant, Arconia Web Fixed Tenant Resolution, and no Organization compatibility path.
- [x] Inventory Organization symbols, persistence schema, API/OpenAPI/web contract, worker context, tests, configuration, and current-runtime documentation.
- [x] Verify Arconia 0.30.0 compatibility and fixed resolver/Web filter behavior against current source and documentation.
- [x] Record the clean migration, bootstrap, authorization, worker propagation, rollback, and verification contracts.

## Persistence and capability cutover

- [x] Add Flyway V6 to rename Organization tables, columns, constraints, and indexes while preserving UUIDs and composite ownership.
- [x] Enforce one Tenant with a checked unique deployment slot and configured bootstrap Tenant ID.
- [x] Rename the Organization capability, public contracts, implementations, repositories, and tests to Tenant.
- [x] Migrate invitation, connector, document, ingestion, API, and worker callers without aliases or deprecated paths.
- [x] Update Spring Modulith and ArchUnit boundaries to the `tenant` capability.

## Arconia and external contract

- [x] Add the Arconia BOM/version catalog entry and API Web starter.
- [x] Configure required API Fixed Tenant Resolution from `MEMORYOS_TENANT_ID` with no fallback.
- [x] Verify the fixed API Tenant against bootstrap authority and preserve membership/capability authorization.
- [x] Keep worker processing on durable `TenantId` values and explicit JDBC predicates without ambient Arconia context.
- [x] Clean-cut identity/session JSON, OpenAPI, generated client, web state, query keys, and product copy to Tenant.

## Verification and consolidation

- [x] Add migration/bootstrap/singleton/ownership persistence tests.
- [x] Add HTTP tests for missing/conflicting tenant headers, fixed context lifecycle, and unchanged authorization behavior.
- [x] Verify worker durable-Tenant delegation and update architecture tests.
- [x] Inspect every changed Java, Kotlin DSL, YAML, properties, and XML file through JetBrains with warnings enabled.
- [x] Run focused compilation, migration, capability, security, architecture, OpenAPI, and frontend checks.
- [x] Exercise the actual API fixed-Tenant request path and worker startup.
- [x] Obtain independent reviewer approval and resolve every material finding.
- [x] Run the repository-wide gate and record exact evidence in `verification.md`.
- [x] Consolidate current runtime facts into README, architecture, specifications, test matrices, roadmap, and active-increment maps.