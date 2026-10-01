# Docling fallback alert: plan

Scope approved by the product owner on 2026-10-01: keep the fallback, add an alert (option A). See [design](design.md).

## Steps

- [x] `DoclingFallbackMetrics` in `io.memoryos.ingestion.extraction`: counter `memoryos.extraction.docling.fallback`, labels `reason` and `outcome`, every series registered at zero.
- [x] `DoclingSourceContentExtractor` takes a `MeterRegistry` and records each fallback decision: `read`, `refused`, `failed`.
- [x] `FileProviderAutoConfiguration` passes the worker's `MeterRegistry`.
- [x] `DoclingFallbackTest` asserts the counter for a native read, a refusal and a Docling success that records nothing; other test call sites pass a `SimpleMeterRegistry`.
- [x] Alert `MemoryOSDoclingFallback` in `infrastructure/observability/alerts.yaml`.
- [x] Panel "Docling fallbacks" on `staging.json`.
- [x] Documents: ingestion spec (fallback paragraph), observability guideline (extraction metric), ingestion and observability test matrices, roadmap row.

## Verification

- `:sources:compileTestJava` (every constructor call site), then `:sources:test` for the changed test classes.
- `promtool check rules` on `alerts.yaml` inside the staging Prometheus container (written to its `/tmp` only).
- After the application deploy, which recreates the worker: `memoryos_extraction_docling_fallback_total` exists in Prometheus with twelve zero series.
- After the observability rollout, a separate server action the application deploy never performs: pull the merged commit into the checkout the stack runs from and recreate `prometheus grafana` ([observability README](../../../../infrastructure/observability/README.md)). Then the rule is loaded and the panel renders.

## Status

- 2026-10-01: increment opened.
- 2026-10-01: implemented. `:sources:compileTestJava` passed; `BoundedDoclingClientTest` (13), `DoclingFallbackTest` (7), `DoclingSourceContentExtractorTest` (16) and `PaddleOcrVlRoutingTest` (19) passed, 55 cases, no failures or skips. `promtool check rules` in the staging Prometheus container: 8 rules, success. Open: CI, the application deploy and the observability rollout.
