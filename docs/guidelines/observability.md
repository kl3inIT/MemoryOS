# Observability and logging

API and worker use Boot's Micrometer/OpenTelemetry autoconfiguration with one
aligned SDK/instrumentation train. Logback remains the SLF4J implementation.
Shared resources live in `config/observability` and are packaged in both images.
The explicit SDK version override is required by the newer Logback instrumentation;
do not upgrade the appender independently of its SDK/API/incubator dependencies.

- Local console output is readable text. Staging uses Boot Logstash JSON plus one
  asynchronous OTLP log appender. Never also tail stdout into Loki.
- Application logs use SLF4J fluent key/value fields: stable `event`, and relevant
  `operation_id`, `delivery_id`, `workload`, typed `error_code` or `error_type`.
  Use INFO for lifecycle transitions, DEBUG for stale/no-op details, WARN for
  handled retries and ERROR for unhandled processing/transport failures. Do not
  append arbitrary provider exception messages or object/request content.
- Use INFO by default. `MEMORYOS_LOG_LEVEL` may enable scoped `io.memoryos` DEBUG
  during staging diagnosis. Do not enable broad Spring Security, HTTP wire, JDBC
  bind-value or provider payload DEBUG. JSON structure and credential protection
  still apply on staging.
- Trace/span IDs belong to the active scope. Durable operations persist optional
  validated origin IDs; each publication/processing attempt starts a new bounded
  root span linked to that origin. Operation IDs are log/span attributes, never
  metric labels or authority for claim, retry, tenant resolution or authorization.
- Processing spans expose `processing.outcome` (COMPLETED, SKIPPED, FAILED).
  Handled business failures set span ERROR even when the delivery is ACKed:
  PostgreSQL owns the retry policy, while ACK describes Redis transport handling.
- The Collector strips raw HTTP URL/path/query fields and exception event text
  from traces because invitation paths and OAuth callbacks can carry secrets.
  Route templates, error types and status codes remain available. This is not a
  general redactor for arbitrary log message content; application authors must
  still follow the log-content rules above.
- Staging samples all traces initially; `MEMORYOS_TRACE_SAMPLING` can reduce this.
  Export timeouts, queues and batches are bounded. Collector/backend outages are
  never application readiness dependencies. Memory queues may drop telemetry on
  sustained outage or abrupt termination; rotated stdout remains a fallback.
- Metric labels are bounded technical dimensions such as service, route template,
  status, workload and outcome. OTLP duration series use milliseconds in this
  stack; verify names and units through Prometheus after dependency upgrades.

Deployment, SSO, retention, health checks and rollback are described in the
[staging observability runbook](../../infrastructure/observability/README.md).
