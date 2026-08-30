# MEM-42 verification: Redis execution runtime

Verified on 2026-08-30.

## Architecture gate

An independent reviewer returned `approve_with_changes`. The accepted changes keep PostgreSQL operation rows as durable dispatch intent and recovery authority, reject a generic one-to-one outbox, keep Redis as identifier-only at-least-once execution transport, require explicit durable `TenantId` in workers, and reserve epoch-fenced poller removal for MEM-51. The canonical Linear architecture documents and MEM-40–51 issue scopes were reconciled before implementation.

## Behavioral evidence

| Contract | Evidence |
| --- | --- |
| Worker creates fixed ingestion and cleanup streams/groups idempotently | `RedisExecutionTopologyIntegrationTest.createsGroupsAndAcknowledgesIdentifierOnlyDelivery` ran against Arconia-managed Redis 8.8 with `skipped="0"`, `failures="0"`, and `errors="0"`. The test calls topology convergence twice and verifies both groups. |
| Identifier-only delivery reaches the consumer-group PEL and can be acknowledged | The same test executes XADD → XREADGROUP → pending-count `1` → XACK → pending-count `0` against real Redis. |
| Redis availability participates in actual worker readiness | The available integration test starts the worker on a random HTTP port and receives `200 OK` from `/actuator/health/readiness`. |
| Redis outage is bounded and does not leak credentials | `RedisUnavailableReadinessIntegrationTest` points the worker at loopback port `1` with 100 ms timeouts, receives `503 Service Unavailable`, and asserts the configured secret is absent from the body. |
| Existing PostgreSQL business execution remains operable | `WorkerFileProcessingIntegrationTest.schedulerIndexesRemovesAndDeletesOneRealFile` passed against real PostgreSQL with one non-skipped test. No business publisher or Redis consumer exists in MEM-42. |

## Composition and artifact evidence

- `:api:dependencyInsight`, `:core:dependencyInsight`, and `:connector:dependencyInsight` each reported no `spring-data-redis` dependency on `runtimeClasspath`.
- `:worker:bootJar` completed successfully.
- Inspection of `worker-0.1.0-SNAPSHOT.jar!/BOOT-INF/lib` found Spring Data Redis and Lettuce, but no Arconia Dev Services or Testcontainers artifacts.
- `application-production.yaml` requires explicit Redis host, port, username, password, TLS, connect/command timeout, pool, and scheduler identity values. The committed staging example contains no Redis password.
- `docker compose -f infrastructure/deployment/compose.production.yaml --env-file infrastructure/deployment/staging.env.example config --quiet` completed successfully with validation-only required values.
- Arconia PostgreSQL Dev Services was evaluated and deliberately excluded. It would override datasource properties per application but offers no shared-service discovery for PostgreSQL; API and worker would receive separate authority databases. Local development therefore retains one external shared PostgreSQL datasource, while real-PostgreSQL tests retain explicit isolated containers.

## Static and repository gates

- JetBrains inspections with warnings enabled reported no findings in the changed Redis Java, Kotlin DSL, TOML, or YAML files.
- Final `./gradlew.bat clean check --no-daemon` completed successfully on the virtual-thread head: 23 actionable tasks, 14 executed, 8 from cache, and 1 up-to-date.
- All relevant real-container test reports record `skipped="0"`; Docker-backed verification was not silently bypassed.

## Deliberate boundary

Redis Streams currently contain topology only. PostgreSQL attempt/cleanup rows remain the business authority and the direct PostgreSQL claim loop remains the only business executor. MEM-43 relay, MEM-44 consumers/reclaim/lease renewal, and MEM-51 epoch-fenced drain/removal are one non-separable delivery increment even though Linear tracks their acceptance criteria separately.
