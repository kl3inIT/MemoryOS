# MEM-57 verification

## Local checks — 2026-09-06

- Updated the worktree from origin/main to 6a9e1e0, retaining MEM-56 MinIO SSO and Redis fixes. OrgMemory at db999fab was inspected read-only.
- `gradlew.bat clean check --no-daemon`: passed, including 144 tests (core 79, connector 6, API 40, worker 19), zero failures/errors/skips. The final run also forwarded the staging telemetry fixture through the real local Collector.
- `StagingTelemetryIntegrationTest`: actual Boot staging JSON, matching trace IDs in OTLP logs/spans, one application log, HTTP client W3C propagation, HTTP server/JVM metrics, health during Collector HTTP 503 and export recovery passed. The receiver intentionally stops before cached Spring contexts during test teardown; shutdown exporter warnings are test-fixture cleanup noise.
- Durable trace tests: malformed/null/oversized metadata is ignored; retry spans remain independent roots linked to the same origin. The real PostgreSQL/Redis/MinIO FILE integration test persists the origin, recreates the stream, rediscovers delivery and verifies the worker causal link.
- A runtime test caught an SDK/API mismatch hidden by compilation. Fixed by aligning SDK/API 1.65.0 with instrumentation 2.31.1-alpha. A second runtime check corrected the staging Logback include to Boot's structured console appender.
- `docker build --target api --tag memoryos-api:mem57-verify .` and the corresponding worker build passed. Shared observability resources are explicitly copied by Dockerfile and packaged in both deployables.
- Base + staging Compose configuration validated with synthetic environment values. The independent observability Compose config and pinned Collector, Loki, Tempo and Prometheus configurations validated in their actual images. Collector privacy transform also passed image validation.
- Secret provisioning ran twice in a Linux container, preserving existing values; file ownership/mode verified as 472:472 / 0400. ShellCheck passed. These permissions match Grafana's explicit runtime user and Compose bind-secret behavior.

## Real local stack checks

An isolated Docker project used the checked-in configuration with loopback-only test ports and a disposable Keycloak 26.7.0. Public/staging ingest ports remain absent. HTTP-only loopback overrides disabled secure Grafana cookies; the HTTP test harness treated Keycloak's localhost secure cookie like a browser on a trustworthy loopback origin. This does not substitute for deployed HTTPS acceptance.

- `configure-grafana-sso.py` reconciled the dedicated client twice successfully without granting any user role. Two temporary normal users used Authorization Code + PKCE: inspector accepted, ordinary user denied, accepted user had no Grafana Server Admin. Temporary Keycloak users were removed after each scenario.
- Native Grafana OAuth used strict role mapping and ID-token validation. The separate local administrator authenticated for datasource/dashboard checks.
- Synthetic OTLP logs, traces and metrics sent through Collector were queried from Loki, Tempo and Prometheus. Secret sentinels in raw URL, span status and exception event text were absent from the stored trace.
- All three signals remained queryable after restarting Loki, Tempo and Prometheus. Grafana retained data sources and loaded the repository dashboard after restart.
- Grafana API returned all three provisioned data sources and the twelve-panel staging dashboard. Every panel query was accepted by its backend. Actual API metrics confirmed duration series use milliseconds, including http_server_requests_milliseconds_bucket/count.
- The provisioned Tempo-to-Loki template was interpolated with a real trace and returned its matching log using structured trace_id metadata. Using the default line-text trace filter would not have matched OTLP log bodies; this was corrected.
- Readiness endpoints for all five services returned success. No app health dependency on Collector was added.

## CodeRabbit follow-up

- Reject all redirects from both Keycloak token and admin requests. A standard-library unittest exercises HTTP 301, 302, 303, 307 and 308 against separate loopback origins, asserting the destination receives no request. The test passes locally and runs in CI.
- Correct each dashboard legend to use labels retained by that panel's query.
- Return explicit coordinator outcomes. Focused extraction-failure and cleanup-retry tests verify persisted failure/retry effects; parameterized worker tests verify ERROR only for FAILED, with ACK/delete retained for all handled outcomes.
- The full local run exposed a 10-second FILE convergence deadline that interrupted the real extractor before its 90-second runtime budget. Only the FILE-processing wait now allows 120 seconds; other convergence waits remain 10 seconds. The complete gate is rerun on the revised fixture before merge.

## Pre-merge delivery boundary

The implementation is ready for review and staging rollout through infrastructure/observability/README.md. No staging mutation, public proxy change or production secret was performed in this session. Host capacity, HTTPS owner/member acceptance, deployed traffic, retention growth and operational notification routing remain rollout acceptance, not inferred from local tests. Alert rules are viewable in the metrics backend; no external paging destination is configured.

JetBrains static analysis tooling was unavailable. The compiler, repository contracts, ShellCheck, image/configuration validation and runtime tests provide the recorded local evidence. Frontend SDK remains MEM-58; no frontend or Spring AI runtime was added.
