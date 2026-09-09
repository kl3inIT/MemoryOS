# MEM-64 verification

## Local contracts and runtime

- `gradlew.bat clean check --no-daemon`: passed on the final tree: 155 tests, zero failures/errors/skips (core 86, connector 6, API 40, worker 23). The final run re-executed worker tests including ACK outage and reused unchanged module results from the preceding complete run.
- `DefaultIngestionCoordinatorTest` checks handled cleanup/extraction failures, successful cleanup, missing claims for both workloads, propagation of unhandled claim errors, recording failure isolation, omitted retry samples and negative-clock clamping. All outcome counters retain the same two bounded label keys.
- `PostgresSourceLifecycleTest` exercises the real PostgreSQL schema: initial claims have a duration, subsequent processing retries and expired-lease reclaims do not. No migration or scheduling/claim policy changed.
- `WorkerFileProcessingIntegrationTest` runs real PostgreSQL, Redis, MinIO and the isolated extractor. Creation-to-first-claim duration equals the persisted database timestamp difference within one microsecond. Redis rediscovery/reclaim followed by a duplicate delivery yields one INGESTION COMPLETED, one INGESTION SKIPPED and only one initial-wait sample. Item removal and source deletion yield two CLEANUP COMPLETED and two cleanup wait samples.
- The worker fixture captures actual OTLP HTTP metrics; the first full run forwarded those exports using `MEMORYOS_TEST_OTLP_FORWARD=http://127.0.0.1:24318` through the pinned Collector and Prometheus images in an isolated local Compose project. Queries returned all eight bounded outcome series, counts of 1 ingestion completion / 1 skip / 2 cleanup completions, and wait counts of 1 ingestion / 2 cleanup. No UUID labels were present.
- Both checked-in Grafana panel expressions executed successfully against those exported metrics. Initial-wait p95 returned 4800 ms for ingestion and 955 ms for cleanup in this small fixture. These are histogram interpolations from test traffic, not performance targets or staging SLOs.
- The disposable `memoryos-mem64-smoke` Compose project, its volumes and networks were removed after query verification.
- `git diff --check` passes. JetBrains static analysis tooling is unavailable in this session; compiler, module boundary checks, database/runtime tests and real backend queries are the available evidence.

## Delivery boundary

MEM-57 lifecycle is reconciled in this substantive code change. Its post-merge runtime evidence remains in Linear. MEM-64 does not change Grafana SSO, network exposure, retention, sampling or dependency versions; Micrometer core is explicitly declared using the existing Boot-managed version.

New metrics are best-effort invocation diagnostics, not a durable accounting ledger. Initial wait excludes later retry/backoff/reclaim waits and can lose a sample on a crash after claim. New staging rollout and PR evidence are separate from this local verification.
