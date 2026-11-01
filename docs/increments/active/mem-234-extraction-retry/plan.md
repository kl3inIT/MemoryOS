# MEM-234: an unreachable extraction service is asked again — plan

Issue: [MEM-234](https://linear.app/memory-os/issue/MEM-234). See the [design](design.md).

## Steps

- [x] `ExtractionFailure.retryable()`: `CONNECTION_FAILED` only.
- [x] `IndexWork.attempt`, loaded with the claim as `processing_attempts - deferred_attempts`.
- [x] `DefaultIngestionCoordinator`: a retryable failure goes to `indexingPort.retry` with a budget of six and waits of 30 s, 2, 5, 10 and 15 min; other typed failures still go to `fail`. `ingestion.extraction.failed` carries `retryable`.
- [x] `PaddleOcrVlClient`: HTTP 429 and a connection broken mid-request are `CONNECTION_FAILED`.
- [x] `IngestionMetrics`: counter `memoryos.extraction.unavailable`, `outcome` `retry_scheduled` or `exhausted`, registered at zero.
- [x] Alert `MemoryOSExtractionUnavailable` in `infrastructure/observability/alerts.yaml`.
- [x] Tests: `DefaultIngestionCoordinatorTest`, `PaddleOcrVlClientTest`, the attempt number in `PostgresSourceLifecycleTest`.
- [x] Documents: ingestion spec, observability guideline, ingestion and observability test matrices, roadmap row.
- [x] `promtool check rules` on `alerts.yaml` in the staging Prometheus container.
  2026-10-28: 10 rules, success; staging's Prometheus has `MemoryOSExtractionUnavailable` loaded and two
  `memoryos_extraction_unavailable_total` series. Production runs release `f9ccabf5` but its observability stack is
  still `6ac59f77`, without the rule; bringing it moves five image versions, so it waits for the upgrade steps.
- [ ] Staging, after the deployment: stop `paddleocr-vl-api`, upload two scanned PDFs to a FILE Source, see the attempts wait with `SOURCE_EXTRACTION_CONNECTION_FAILED`, start the service again, see both files index without a manual reindex.
- [ ] Observability rollout on staging and production, a server action the application deployment does not perform ([observability README](../../../infrastructure/observability/README.md)): the rule is loaded and `memoryos_extraction_unavailable_total` shows two zero series.

## Verification

- `:memoryos-core:test --tests '*DefaultIngestionCoordinatorTest'` and `:sources:test --tests '*PaddleOcrVlClientTest'`.
- `:memoryos-core:test --tests '*PostgresSourceLifecycleTest'` for the attempt number on a real PostgreSQL.
- `clean check` in CI.

## Status

- 2026-10-09: increment opened and implemented. `DefaultIngestionCoordinatorTest` (20 cases), `PaddleOcrVlClientTest` (35 cases) and `PostgresSourceLifecycleTest.unexpectedProcessingFailureRetriesThenTerminatesDurably` passed locally, no failures or skips. Open: CI, `promtool`, the staging check and the observability rollout.

## Staging acceptance (2026-10-19)

`promtool check rules` passes on `alerts.yaml` from `main` (10 rules) inside the staging Prometheus, and `memoryos_extraction_unavailable_total` exists on both hosts, but neither stack loads the rule yet (both run older observability configuration). Stopping `paddleocr-vl-api` remains.

Staging rollout (2026-10-19): the stack now runs from `/apps/memoryos/observability-stack` at `5dadbc7d`, as production does, with Grafana's volume backed up first. Its containers had been created from `97663fcc` and `46540b18`, so Tempo still carried the 768 MiB limit that `e1365574` removed and was killed compacting 2.4 GB of blocks on every start. Tempo 3.1.0 now runs without a limit at about 780 MiB, with no restart; Prometheus loads the ten rules, including `MemoryOSExtractionUnavailable`, and `memoryos_extraction_unavailable_total` has two series. Production loads the rule at its next observability rollout; stopping `paddleocr-vl-api` remains.
