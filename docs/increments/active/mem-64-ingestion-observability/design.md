# MEM-64 — Ingestion outcome metrics and observability conventions

Issue: https://linear.app/memory-os/issue/MEM-64

## Problem and scope

Redis acknowledgement does not indicate business success: the coordinator can return FAILED after persisting a retry and the worker correctly acknowledges that delivery. Operators need processing outcome counts and initial queue latency, with durable instrumentation conventions for future capabilities.

## Runtime contract

- The ingestion coordinator records `memoryos.operation.outcomes` once per invocation with bounded `workload` (INGESTION, CLEANUP) and `outcome` (COMPLETED, SKIPPED, FAILED, UNHANDLED). UNHANDLED means a runtime exception escaped the coordinator. ACK failures cannot change or double count the recorded processing outcome.
- Counts describe processing invocations, not an exactly-once operation ledger. A retry can produce another outcome; duplicate or stale deliveries produce SKIPPED.
- `memoryos.operation.initial.queue.wait` records only a successful first claim (`processing_attempts = 1`). Duration is PostgreSQL `started_at - created_at`, both database timestamps; it includes dispatch delay and time without a worker. Retry backoff, later reclaims, processing and ACK time are excluded. Negative clock adjustments clamp to zero. A process crash after claim can lose a sample; metrics are best-effort diagnostics, not durable accounting.
- Connector persistence maps the duration into the claimed work. No SQL enters ingestion or worker code and no new schema is needed.
- Concrete ingestion instrumentation uses Micrometer counters and timers; bounded recording failures must not alter business processing. Export remains asynchronous through the existing OTLP path.
- Grafana displays processing outcomes and initial queue wait separately from transport panels, using OTLP millisecond units.

## Boundaries and decisions

Connector owns operation timestamps and token claims; ingestion owns processing outcomes; worker remains the Redis transport composition root. The existing dependency direction is unchanged. No new capability, abstraction, endpoint or runtime mode is introduced.

Canonical policy belongs in `docs/guidelines/observability.md`, linked from the repository map. This substantive change can also reconcile merged MEM-57 lifecycle documents. Grafana MCP, profiling, frontend SDKs and Spring AI runtime integration remain outside this increment.
