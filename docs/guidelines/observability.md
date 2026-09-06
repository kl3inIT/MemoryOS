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

## Instrumentation conventions

- Instrument meaningful capability operations, not every method. Use Micrometer `Observation` when an operation needs coordinated traces and metrics; use `MeterRegistry` for specific counters and distributions. Reuse Boot's HTTP/JVM instrumentation. Do not stack annotations, manual observations and aspects around the same boundary. Annotation instrumentation requires the relevant aspect and a proxy invocation; self-invocation is not a reliable seam.
- Record explicit business outcomes where the capability decides them. A caught failure returned to the caller still counts as a failure; an `@AfterThrowing` aspect alone cannot detect it. Transport ACK and HTTP acceptance are separate from durable business success. Instrument before ACK, with no double count on ACK failure. Metrics never substitute for the durable operation ledger.
- Use one metric name and the same label keys for every outcome. Document each label's bounded vocabulary. Tenant, actor, operation, delivery, document and request IDs, filenames, URLs, arbitrary SKU values and exception messages are prohibited labels. Put permitted diagnostic identifiers in logs/spans only.
- Define each timer's start, end, clock domain, retry and duplicate semantics. Use a monotonic clock for in-process elapsed time; subtract persisted database timestamps for durable waits. Never infer queue wait from trace duration or mix unsynchronized application and database clocks. Document clock rollback behavior.
- Use histogram buckets that cover the operation's useful latency range. Bucket boundaries are measurement resolution, not an accepted service-level objective. Aggregate histograms across instances before computing percentiles; do not average per-instance percentiles. Empty traffic is no data, not zero latency.
- Export asynchronously with bounded resources; recording/export failures must not change business results or trigger retries. Metrics are best effort and can lose observations during crashes. Verify handled/unhandled errors, duplicate/retry behavior and exporter outage, not just happy-path emission.
- Verify exported names, units, labels and actual PromQL results after changing instrumentation. A configured exemplar or trace-to-log link is not evidence that matching telemetry exists; demonstrate correlation with a real request.

## Staging and future integrations

- Keep structured logs, redaction rules, owner-gated Grafana SSO, private backend listeners, persistent storage and bounded retention on staging. Full sampling is useful initially; relaxed traffic volume does not justify logging secrets. Never copy demo credentials, all-Actuator exposure or `show-values=ALWAYS`.
- Upgrade to the newest compatible, tested dependency train, not individual latest artifacts. Use current official documentation and the platform BOM; document required overrides and validate the complete OTLP path.
- When Spring AI is introduced, default to model/provider identifiers, operation outcome, latency and token usage. Prompt, completion, retrieved document and tool argument/result content must remain disabled by default. Enabling content capture requires a concrete redaction, access and retention decision.
- Grafana MCP is a separate integration: start with scoped read-only authority, separate credentials and an explicit decision about telemetry leaving the network for a model/provider. Do not reuse administrator credentials.
- Continuous profiling is an opt-in follow-up with measured overhead and a reviewed privilege boundary. Do not copy privileged, host-PID demo agents into staging. Frontend SDK adoption has its own issue and internal-egress design.

Reference reviewed for patterns, not staging defaults: [Spring Boot OpenTelemetry LGTM demo](https://github.com/timosalm/spring-boot-opentelemetry-lgtm/tree/099509cc75d4984a26e88de6547b933666494319). Metric APIs and histogram behavior follow the [Micrometer documentation](https://docs.micrometer.io/micrometer/reference/concepts/timers.html).
