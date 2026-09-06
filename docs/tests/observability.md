# Observability verification matrix

| Contract | Verification |
| --- | --- |
| Business results are counted once independently of ACK, including handled failure, skipped and unhandled invocations | `DefaultIngestionCoordinatorTest`, ACK-outage contract in `RedisStreamWorkerTracingTest`, real Redis processing in `WorkerFileProcessingIntegrationTest` |
| First-claim wait uses database timestamps and excludes retry/reclaim samples | `PostgresSourceLifecycleTest`, `WorkerFileProcessingIntegrationTest` |
| Metric recording failure preserves processing result; negative clock adjustment clamps to zero | `DefaultIngestionCoordinatorTest` |
| Boot staging logs, traces and metrics share service identity | `StagingTelemetryIntegrationTest` captures actual OTLP HTTP exports |
| Logback emits JSON with event and active trace ID, no duplicate application log | `StagingTelemetryIntegrationTest` checks captured console output and exporter payload |
| Observation-enabled HTTP client sends W3C context | `StagingTelemetryIntegrationTest` checks the receiving HTTP server's traceparent |
| Collector rejection does not remove application health; new exports recover | `StagingTelemetryIntegrationTest` serves HTTP 503 then restores OTLP receiver |
| Malformed/missing durable trace metadata never blocks work | `SourceOperationTraceContextTest` |
| Worker retry spans are independent roots linked to the same origin | `OperationTracingTest` |
| Handled extraction/cleanup failures produce ERROR processing spans while retaining database retry and Redis ACK semantics | `DefaultIngestionCoordinatorTest`, `RedisStreamWorkerTracingTest` |
| Keycloak token and admin requests reject redirects without forwarding credentials | `test_configure_grafana_sso.py` exercises HTTP 301/302/303/307/308 against two loopback servers; runs in CI |
| PostgreSQL origin survives Redis rediscovery and processing of a real FILE | `WorkerFileProcessingIntegrationTest` |
| Nullable trace columns migrate on PostgreSQL | `SchedulerSchemaMigrationTest`, existing source lifecycle tests |
| Pinned backend images accept configurations and transport three signals | Image validators and local Compose smoke evidence in the active increment |
| Grafana role gate accepts inspector and rejects ordinary user | Real Keycloak Authorization Code + PKCE local smoke evidence in the active increment |
| HTTPS proxy, deployed owner authorization, retention/capacity | Mandatory staging acceptance in the observability runbook; record runtime results in Linear after rollout |

The local test uses an observation-enabled RestClient. Existing provider SDKs do
not become fully instrumented HTTP clients merely by adding the Boot starter.
