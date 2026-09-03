# MEM-43/MEM-44/MEM-51 verification matrix: Redis operation execution cutover

Verified locally on 2026-09-03. Exact pull-request head CI and merge-SHA evidence remain the final delivery gate.

| Requirement | Evidence |
| --- | --- |
| API operation creation commits without contacting Redis | Transaction integration test with Redis unavailable |
| Operation rows are the sole durable dispatch intent | Migration and repository test; no generic outbox table |
| Concurrent relay replicas publish through bounded conditional dispatch claims | Real PostgreSQL and Redis concurrency test |
| Publish success followed by evidence failure may duplicate but cannot lose work | Controlled failure integration test |
| Redis outage defers transport without fabricating processing failure | Relay integration test with unavailable Redis |
| Redis restart, trim, or complete loss repairs nonterminal work from PostgreSQL | Real Redis loss and rediscovery test |
| Inactive-Tenant indexing is cancelled while cleanup remains dispatchable | PostgreSQL relay eligibility test |
| Messages contain only Tenant, workload, operation, and delivery identifiers | Redis record assertion and log/secret scan |
| Consumers reload authoritative PostgreSQL state before side effects | Controlled stale/missing/terminal delivery tests |
| One operation is claimed by identifier with a fresh token and lease | Multi-worker PostgreSQL concurrency test |
| Long processing renews only the current token's lease | Two-worker stale-renewal and stale-completion test |
| ACK occurs only after durable terminal completion or authoritative obsolete proof | Controlled completion/ACK boundary test |
| Completion followed by ACK failure converges on terminal redelivery | Real Redis redelivery test |
| Pending reclaim requires both Redis idle and PostgreSQL lease expiry | Real PEL claim test with live and expired leases |
| Unexpected failures retry with bounded backoff and poison work terminates safely | Durable processing-counter test |
| Ingestion and cleanup have independent bounded capacity | Saturation/isolation integration test |
| Graceful shutdown stops reads and leaves incomplete work reclaimable | Actual worker termination/restart scenario |
| Direct PostgreSQL polling and all compatibility configuration are absent | Source/configuration review plus runtime topology assertion |
| Command-to-completion production path uses relay and Redis consumer groups | PostgreSQL/Redis-backed worker integration and runtime smoke |
| Logs and bounded-label metrics expose no Tenant, filename, content, secret, or raw exception | `RedisOperationRelayTest.metricsExposeOnlyBoundedWorkloadAndOutcomeLabels` plus safe-log inspection |

## Implemented evidence

- `PostgresSourceLifecycleTest` runs the production V1–V8 migration sequence against PostgreSQL and proves concurrent relay exclusion, rediscovery, transport deferral, separately bounded inactive-Tenant cancellation, token-fenced renewal/completion, reclaim lease evidence, and bounded retry exhaustion.
- `RedisOperationRelayTest` proves identifier-only records, bounded per-workload pressure, cleanup isolation from ingestion saturation, Redis transport deferral, and the publish-succeeded/evidence-write-failed duplicate boundary.
- `WorkerFileProcessingIntegrationTest.redisStreamsIndexRemoveAndDeleteOneRealFile` starts the worker composition against real PostgreSQL and Redis, deletes and rebuilds the ingestion stream, republishes from durable dispatch intent, reclaims an abandoned PEL entry, durably indexes/removes/deletes, completes cleanup after Tenant deactivation, and consumes a duplicate terminal delivery without reprocessing.
- `RedisExecutionTopologyIntegrationTest`, `RedisUnavailableReadinessIntegrationTest`, `ControlPlaneIntegrationTest`, and `WorkerApplicationSmokeTest` cover topology, fail-closed readiness, persistent recurring control tasks, and the production composition boundary.
- `SchedulerSchemaMigrationTest` executes all eight Flyway migrations against PostgreSQL and verifies the scheduler schema plus V8 dispatch/processing columns.
- IntelliJ inspections ran with warnings enabled for every changed Java, SQL, YAML, properties, XML, and Kotlin DSL file. Remaining warnings are limited to fixed internal table-name SQL that the IDE cannot resolve across the enum-controlled repository boundary, datasource-less migration inspection, and the conventional public Spring Boot launcher.
- Manual CodeRabbit review completed with eight findings. Applied renewal exception containment, a separate bounded cancellation task, redundant dispatch reload removal, the complete migration fixture, and cleanup row-lock fencing; retained the transactional cross-dialect index migration, sanitized logs, and unique-stream Arconia Redis test isolation with explicit rationale.
- `gradlew clean check --no-daemon` passed across all server modules.
- `docker build --target worker --tag memoryos-worker:mem43-cutover .` built the layered production worker image; local manifest list `sha256:fc3295033bca7965f0ca98ed0462d33d0a1330423756f3d866ccb0403d69a6a1`.
- Source and configuration search finds no `IngestionWorker`, `WorkerProperties`, direct batch-claim coordinator, `MEMORYOS_WORKER_POLL_DELAY`, or `MEMORYOS_WORKER_BATCH_SIZE` path.
