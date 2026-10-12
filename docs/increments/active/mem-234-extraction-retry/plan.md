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
- [ ] `promtool check rules` on `alerts.yaml` in the staging Prometheus container.
- [ ] Staging, after the deployment: stop `paddleocr-vl-api`, upload two scanned PDFs to a FILE Source, see the attempts wait with `SOURCE_EXTRACTION_CONNECTION_FAILED`, start the service again, see both files index without a manual reindex.
- [ ] Observability rollout on staging and production, a server action the application deployment does not perform ([observability README](../../../infrastructure/observability/README.md)): the rule is loaded and `memoryos_extraction_unavailable_total` shows two zero series.

## Verification

- `:core:test --tests '*DefaultIngestionCoordinatorTest'` and `:sources:test --tests '*PaddleOcrVlClientTest'`.
- `:core:test --tests '*PostgresSourceLifecycleTest'` for the attempt number on a real PostgreSQL.
- `clean check` in CI.

## Status

- 2026-10-09: increment opened and implemented. `DefaultIngestionCoordinatorTest` (20 cases), `PaddleOcrVlClientTest` (35 cases) and `PostgresSourceLifecycleTest.unexpectedProcessingFailureRetriesThenTerminatesDurably` passed locally, no failures or skips. Open: CI, `promtool`, the staging check and the observability rollout.
