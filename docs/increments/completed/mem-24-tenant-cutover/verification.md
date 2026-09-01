# MEM-24 verification: Organization to Tenant cutover

Verified on 2026-08-29.

## Behavioral evidence

- `TenantSchemaMigrationTest` applies V1–V5 with existing actor, Organization, membership, invitation, connector, item, and document data, applies V6, and proves UUID preservation, Tenant naming, and the single deployment slot.
- `DefaultInitialTenantBootstrapperTest` and `PostgresInitialTenantBootstrapperConcurrencyTest` prove configured Tenant ID publication, replay, configuration-drift failure, singleton enforcement, and PostgreSQL concurrent bootstrap behavior.
- `PostgresInvitationAcceptanceConcurrencyTest` proves PostgreSQL invitation-row serialization under the single-Tenant invariant. `PostgresInvitationRepositoryReadConcurrencyTest` proves read lookup behavior while lifecycle mutation holds the row lock.
- `BearerAuthenticationIntegrationTest.ignoresTenantHeadersAndUsesTheFixedDeploymentTenant` exercises the real servlet request path with a conflicting `X-TenantId` header and proves that Arconia Fixed Tenant Resolution supplies the configured deployment Tenant.
- `SourceWorkerTest` proves scheduled processing clamps and delegates the configured batch. `WorkerFileProcessingIntegrationTest` proves durable work carries its Tenant identity through real PostgreSQL indexing and cleanup. `WorkerApplicationSmokeTest` proves worker composition starts without Arconia or fixed-resolution configuration.
- `ModulithArchitectureTest` and `CoreDependencyRulesTest` pass with `tenant` as the closed capability and no active `organization` module.
- `OpenApiContractTest` regenerated the committed Tenant contract. The Hey API drift gate regenerated the client without stale output.

## Tool gates

- JetBrains inspections ran with warnings enabled for the initial 81 changed or created Java, Kotlin DSL, YAML, properties, and XML files and for all 10 Java/YAML files in the first worker-context follow-up. No inspection errors remained at that gate; retained warnings were non-blocking existing conventions.
- During the final architecture correction, the previously mounted JetBrains MCP inspection, formatter, and build devices disappeared and returned `No such tool`; the harness inconsistency was reported. No final IDE-clean claim is made for that correction. The required fallback compilation, focused behavioral/architecture tests, dependency inspection, and repository-wide Gradle gate all passed.
- Focused H2 PostgreSQL-mode capability, migration, bootstrap, security, API, worker, OpenAPI, and architecture tests: successful.
- PostgreSQL Testcontainers gate:
  - `PostgresInitialTenantBootstrapperConcurrencyTest`
  - `PostgresInvitationAcceptanceConcurrencyTest`
  - `PostgresInvitationRepositoryReadConcurrencyTest`
  - Result: successful.
- `./gradlew.bat clean check --no-daemon`: successful after removing speculative worker context; 23 actionable tasks, 15 executed and 8 from cache.
- `pnpm check`: successful; generated-contract stability, CI policy, lint, formatting, TypeScript build, 10 Vitest files / 41 tests, route generation, production Vite build, and emitted-font validation passed.
- `pnpm test:e2e`: successful; all 15 Playwright browser scenarios passed.

## Independent review

The independent reviewer classified the implementation as correct with 0.92 confidence across migration safety, singleton bootstrap, Arconia context lifecycle, authorization, clean-cutover naming, architecture, API, worker, and verification coverage.

The reviewer reported two non-blocking P3 findings. Both were resolved and reverified:

1. `design.md` now names the implemented `InvitationAuthority` and `InvitationTarget` public contracts.
2. The FILE source operation summary now reads `Create a Tenant-owned FILE source`; `openapi.yml` and the generated TypeScript client were regenerated.

The JetBrains rebuild, Gradle repository gate, and frontend gate passed after the initial reviewer corrections.

## Architecture re-review

An advisor initially prompted moving worker context from a fixed batch identifier to each durable work record. The subsequent architecture reviewer inspected Arconia 0.30.0 source and found that programmatic `TenantContext.where(...).run(...)` publishes no Web filter events, drives no worker MDC enrichment, wraps no observed operation, and has no production reader. The per-work binding and its `TenantWorkRunner` port were therefore removed rather than retained as speculative infrastructure. Worker isolation remains the explicit `TenantId` carried by every durable record and enforced by JDBC predicates. API Fixed Tenant Resolution, the custom database-backed `TenantVerifier`, and API observability defaults remain unchanged.

The independent architecture reviewer returned the target verdict with 0.82 confidence: keep Arconia Web Fixed Tenant Resolution and the custom `TenantVerifier` in API; remove Arconia Core and ambient context from worker; keep Tenant Details service, Data JDBC, and JPA/Hibernate absent until a demonstrated consumer exists. `:worker:dependencyInsight` confirms no `arconia-multitenancy-core` dependency remains on the worker runtime classpath.
