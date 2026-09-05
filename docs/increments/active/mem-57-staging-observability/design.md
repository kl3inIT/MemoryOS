# MEM-57 — Staging observability

Status: implemented and locally verified; pull request and staging rollout are separate gates.

## Outcome and scope

Operators can diagnose API requests and durable ingestion through correlated structured logs, metrics, and traces in owner-only Grafana. API and worker use Boot 4.1 managed Micrometer/OpenTelemetry and Logback. Frontend SDK work is deferred to MEM-58; no Spring AI dependency is introduced before an AI capability exists.

The coordinator returns a bounded processing outcome so handled extraction and cleanup failures mark the consumer span ERROR. Redis acknowledgement remains independent: PostgreSQL retains ownership of business retries. Grafana's Keycloak provisioning rejects token/admin redirects to keep administrator credentials at the configured origin.

## Runtime

Deploy Grafana, Loki, Tempo, Prometheus, and OpenTelemetry Collector as persistent staging services with private ingest/backend networks. Grafana uses the existing Keycloak owner inspector boundary. Application deployment and health do not depend on telemetry availability. Each source has exactly one ingest route: application OTLP logs are not collected again from stdout.

Prometheus receives cumulative OTLP metrics, Loki receives OTLP logs, and Tempo receives OTLP spans. Collector memory, queues, retries, and batches are bounded. Logs/traces initially retain seven days and metrics fifteen days; resource limits and disk budget require staging capacity verification. Image versions and configuration validation are recorded before deployment.

## Logging

All existing application-authored logging is audited across core, API, worker, and connector. Use stable event names, structured operation/workload/outcome fields, INFO baseline and scoped MemoryOS DEBUG, with readable local output and Boot Logstash JSON on staging. Trace/span IDs are included only in the active scope. Credentials, object content and provider exception messages are not logged. Expected retry/stale outcomes are distinguished from terminal failures, and errors are not redundantly emitted at every layer. Container stdout rotation remains enabled.

## Durable tracing

Request spans end with the request. Durable processing and retries use separate bounded spans, causally linked to accepted work rather than a long-lived request scope. The existing delivery payload contains only identifiers. Any trace metadata added to durable operations/delivery must be nullable, bounded, validated, and unable to change tenant authorization, claim/fencing, retries or ACK semantics. Existing/invalid/missing telemetry context must not block work. Operation identifiers can be log/span attributes but never metric labels. Origin IDs are captured by the connector persistence repositories when accepting an operation; nullable V10 columns and optional delivery metadata preserve compatibility with existing rows/messages.

## Verification

Verify JSON/correlation and credential exclusion, HTTP and durable-worker trace structure, existing execution/fencing contracts, Collector outage behavior, configuration parsing, real three-signal delivery and Grafana datasource queries. Run the wrapper clean check gate and available static analysis. Runtime acceptance includes owner/ordinary-user access, persistence after restart, retention and bounded resources. Do not equate local tests, PR merge, CI, and deployed runtime acceptance.

## References

- [Linear MEM-57](https://linear.app/memory-os/issue/MEM-57)
- [Frontend follow-up MEM-58](https://linear.app/memory-os/issue/MEM-58)
- [Boot logging](https://docs.spring.io/spring-boot/reference/features/logging.html)
- [Boot tracing](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)
- [Prometheus OTLP](https://prometheus.io/docs/guides/opentelemetry/)
- [OpenTelemetry messaging spans](https://opentelemetry.io/docs/specs/semconv/messaging/messaging-spans/)

## Current research and reference comparison (2026-09-06)

Reviewed D:/OrgMemory at db999fab read-only: infrastructure/observability/{README.md,compose.observability.yaml,alloy/config.alloy,tempo/tempo.yml}, ARCHITECTURE.md observability section, and ADR 0021. Reuse its independently managed stack, actual metric-name/unit verification, bounded histogram ranges, Tempo 3 monolithic configuration, and strict Keycloak role gate. Do not copy its shared-all-services network, uncapped containers, or old image pins. OrgMemory uses Docker json-file tailing instead of OTLP logs; MemoryOS selects bounded OTLP logging to avoid granting the collector Docker socket/host-log access. In-flight application batches may be lost on abrupt process death; local rotated stdout remains the diagnostic fallback, not a second Loki ingestion path. This trade-off must be exercised, not described as lossless.

Latest stable upstream releases were verified through GitHub release APIs: Grafana 13.2.1, Loki 3.7.7, Tempo 3.0.3, Prometheus 3.14.0, Collector 0.160.0 and Boot 4.1.1. All five container images are pinned to digests verified by local pulls. The OpenTelemetry Java instrumentation stable release is 2.31.1; its Logback appender artifact is still named 2.31.1-alpha upstream (not a stable API guarantee). Runtime tests exposed incompatibility with the older Boot-managed SDK. API and worker therefore explicitly align OpenTelemetry SDK/API 1.65.0 with instrumentation BOM 2.31.1-alpha while retaining Boot autoconfiguration and a single SDK instance. The staging export test verifies this combination.
