# Observability dashboards

## Goal

Replace the single "MemoryOS staging" dashboard with a small provisioned set that answers the questions an operator
asks first: is the service healthy, are the workers keeping up, and how are Chat, the models and the guardrails
behaving. Everything stays on the MEM-57 self-hosted stack (Collector, Prometheus, Loki, Tempo, Grafana); no new
service is added and no prompt, answer or document content is recorded.

## What the stack had (staging, 2026-09-26)

- One dashboard of 14 panels (HTTP, JVM, Hikari, Redis workers, ingestion events, error logs, alert state) and five
  alerts.
- Spring AI already exports `gen_ai_client_operation_milliseconds_*` and `gen_ai_client_token_usage_total` with
  `gen_ai_operation_name`, `gen_ai_request_model`, `gen_ai_response_model`, `gen_ai_token_type` and `error`, from the
  API and the worker.
- Only `http.server.requests` and `memoryos.redis.operation.processing` publish histogram buckets. Every other timer,
  including `gen_ai.client.operation`, search, the Chat tool timers and `memoryos.operation.initial.queue.wait`, has a
  single `+Inf` bucket, so no percentile can be computed; the existing "Initial queue wait p95" panel is empty for this
  reason.
- No metric describes a Chat turn as a whole: its duration, time to first text, outcome or refusal reason; nor the
  MEM-195 guardrail check.
- Tempo writes no span metrics; Prometheus has no remote-write receiver.

## Decisions

1. **Dashboards** (provisioned from `infrastructure/observability/grafana/dashboards`, folder MemoryOS, linked to each
   other):
   - *Overview* keeps the existing `staging.json` (uid unchanged) as the landing page.
   - *Service* follows Grafana dashboard 20352 "OpenTelemetry JVM Micrometer", which targets exactly this path
     (Micrometer over OTLP, `_milliseconds` names, `service_name`): RED by route, JVM memory and GC, threads, Hikari,
     Lettuce and a Loki error panel. Written for our labels (`service_name`, `deployment_environment_name`); its
     `service_version`/`host_name` variables do not exist here.
   - *Chat & AI*: turns by outcome and refusal, turn and first-text latency, guardrail checks, model calls by model
     (rate, error ratio, p95), tokens by model and type, Chat tools (Web, image, voice, MCP, research) and search
     stages.
   - *Traces*: Tempo span metrics by service and span name, and the service graph.
2. **Histograms** for the timers above, each with an explicit expected range so the bucket count stays bounded
   (`memoryos-observability.yaml`).
3. **Turn and guardrail metrics** in `ChatTurnService`, tags limited to bounded values:
   - `memoryos.chat.turn` (timer, from admission to the terminal outcome): `status`, `failure` (the typed failure code
     or `none`), `refusal` (`none`, `no_evidence`, `uncited`, `blocked_topic`), `grounded`, `research`.
   - `memoryos.chat.turn.first.text` (timer, admission to the first text the person sees, a refusal included):
     `grounded`.
   - `memoryos.chat.guardrail.check` (timer around the MEM-195 Check 1): `kind` (`conversational`, `question`,
     `blocked`, `unavailable`).
4. **Tempo metrics-generator** with `span-metrics` and `service-graphs`, remote-writing to Prometheus
   (`--web.enable-remote-write-receiver`). Span metrics carry `service`, `span_name`, `span_kind`, `status_code`.
5. **Alerts** added for a rising model-call error ratio and for failed Chat turns.

## References

- Grafana dashboard [20352](https://grafana.com/grafana/dashboards/20352-opentelemetry-jvm-micrometer/) for the
  Service layout; [18812](https://grafana.com/grafana/dashboards/18812-jvm-overview-opentelemetry/) was reviewed and
  rejected (OTel-agent conventions, `job`/`instance` variables).
- [Spring AI observability](https://docs.spring.io/spring-ai/reference/observability/index.html) for the `gen_ai`
  meters and their tags.
- Tempo `example/docker-compose/single-binary` for the metrics-generator block.
- Grafana dashboard best practices: RED for services, USE for resources, units and descriptions on every panel,
  provisioned JSON only.

## Excluded

- GPU, host and container metrics (nvidia_gpu_exporter, node-exporter, cAdvisor) and TEI/vLLM scraping: they need new
  exporters on the production serving node and are a separate change.
- OpenSearch metrics, which need an exporter nobody has chosen.
- Prompt or answer content in telemetry (observability guideline).

## Found on the way

**Production exported no telemetry.** Its stack (`observability.vadan.app`) has run since the first promotion on
2026-09-22 and is healthy, but its Prometheus held no application series and Tempo received no trace (checked
2026-09-27): the metrics export, the structured console, the OTLP log appender and its installer were all switched
on by the `staging` profile alone, and production runs `production`. They now switch on for
`staging | production`; `DeployedTelemetryProfilesTest` holds the rule. The fix reaches production with its next
promotion, since it ships in the application image.

The environment label was fixed to `staging` as well. The compose overlays now set `MEMORYOS_ENVIRONMENT` for the API
and the worker, and the resource attribute and the JSON log field read it (`local` when unset). Profiles cannot carry
it: the staging worker runs the `production` profile too.

Production's stack serves the same provisioned dashboards once its checkout is updated and the changed services are
recreated; that step is run on the production node separately, with the owner's approval.
