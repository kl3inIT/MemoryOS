# MEM-45 implementation plan: db-scheduler control plane

## Contract and architecture

- [x] Approve db-scheduler as the PostgreSQL control plane through independent architecture review.
- [x] Remove outbox and worker ambient TenantContext assumptions from Linear architecture and issue scopes.
- [x] Add MEM-45 to the active repository roadmap.
- [x] Keep scheduler composition in `:worker` and Flyway ownership in `:api`/core migrations.
- [x] Register only real bounded control tasks; no per-operation scheduler rows or long processing.

## Dependency and persistence

- [x] Pin the Spring Boot 4 db-scheduler starter version in the Gradle catalog.
- [x] Add the starter only to worker runtime.
- [x] Add Flyway V7 with the PostgreSQL `scheduled_tasks` table and execution/heartbeat indexes.
- [x] Disable library-owned schema initialization.
- [x] Configure scheduler identity, polling, threads, heartbeat, missed-heartbeat recovery, context-ready startup, shutdown bound, and fetch strategy.
- [x] Enable Spring Boot virtual threads and keep-alive for both deployables while preserving datasource, Redis, and scheduler concurrency bounds.
- [x] Run db-scheduler task executions on named virtual threads without replacing its due-polling or housekeeping executors.

## Control task

- [x] Replace Spring `@Scheduled` Redis topology refresh with one idempotent db-scheduler recurring task.
- [x] Preserve safe Redis-unavailable behavior and readiness separation.
- [x] Keep MEM-43 business relay/reconciliation tasks absent.

## Verification

- [x] Inspect every changed Java, Kotlin DSL, YAML, SQL, properties, and XML file with warnings enabled where supported.
- [x] Compile affected modules.
- [x] Run Flyway V7 against real PostgreSQL.
- [x] Prove recurring registration and one-owner execution across two scheduler instances.
- [x] Prove stale/dead execution recovery under the configured heartbeat bound.
- [x] Prove the real Redis topology task records success and remains idempotent.
- [x] Prove scheduler startup/shutdown and PostgreSQL/Redis failure separation.
- [x] Prove API/worker application task executors and the real db-scheduler topology task execute on virtual threads.
- [x] Run focused worker/architecture tests and the repository gate once.

## Consolidation

- [x] Update architecture, runtime runbook, roadmap and verification matrices with verified scheduler runtime facts.
- [x] Record exact verification evidence and keep MEM-45 active until its PR merges.
