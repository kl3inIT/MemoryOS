# Phase 3 — de-duplication and hot paths

## Requirement

Owner decisions 2026-09-25 (group 3 of [owner-decisions.md](../audit-quality-fixes/owner-decisions.md)); best practice even where logic, schema or contracts change; one pull request.

## Decisions

- **Integration bundle.** The Gradle module `connector/` becomes `sources/` (owner's choice). Its Java packages follow their owners: the Google Drive and SharePoint adapters move to `io.memoryos.connector.adapter.googledrive` / `.sharepoint`; content extraction (Tika, Docling, PaddleOCR-VL, the extractor router) moves to `io.memoryos.ingestion.extraction`. Recorded in ADR 0016.
- **One sync engine, Onyx semantics.** Google Drive and SharePoint share a provider-neutral engine in `connector.sync` (attempt lifecycle, fencing, settle, acquire/adopt/remove, retry with `Retry-After`); each provider supplies only its traversal. Failure semantics follow Onyx `run_docfetching`/`docprocessing`: a failure isolated to one item is recorded as a `SourceRunError` and the run continues, ending `COMPLETED_WITH_ERRORS`; the item is retried by the next run and its error resolved when it succeeds; more than 3 failures that are also more than 10% of processed items fail the run (`FAILED`, attempt retried with backoff); an exception not isolated to an item fails the run; pause or delete cancels it (`CANCELLED`). The sync error code moves to `connector_credential_pairs` so the Source summary reads one place. Selection keeps two provider tables behind one repository.
- **Hot paths.** Search index ensured once per generation per process (re-ensured on a 404 write); no alias HEAD per search; row-value reconcile cursor; bulk projection maintenance; gateway failures logged. Chat send loads the persona and capabilities once and reads files in bulk; branch copy and persona reorder in bulk. SharePoint resolves its scope once per run; fewer lock queries per item; Google Drive membership reuses the run's own rows.
- **Meeting.** One job runner for the claim → run → fail pattern, batched writes, streamed recordings, and a sweep that retires adopted recording uploads of finished meetings.
- **Shared helpers.** `PdfText`, SHA-256 hex and LIKE-escape live in `shared` as technical utilities without domain; provider-connection administration (image, web, voice) shares a helper in `ai`; voice synthesis cancels per request on a shared HTTP client.

## Verification

Each change is tested at the narrowest boundary; efficiency changes assert query/HTTP counts rather than timings. The full gate runs in CI.
