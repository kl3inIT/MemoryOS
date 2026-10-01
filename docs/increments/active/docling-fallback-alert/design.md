# Docling fallback alert

## Problem

When Docling fails for a reason of its own (`CONNECTION_FAILED`, `TIMEOUT`, `INTERNAL`, `MALFORMED`), the worker reads text PDFs, DOCX and PPTX with the bounded Tika child instead ([MEM-191](../../completed/mem-191-extraction-fallback/design.md)). The document is published without table structure or bounding boxes, and the Source reports success. The only trace is an `extraction.fell_back` INFO log line.

On staging this hid a broken Docling path. On 2026-10-01 every Docling submission from the worker failed (HTTP 404 from 09:13 to 09:31 UTC, 413 at 09:24, 10:19 and 10:20, 503 at 10:54), and 14 Documents were published from Tika. Loki retains worker logs from 2026-09-30 and holds no accepted Docling task; the last Document Docling produced on staging is from 2026-09-25. Nobody noticed until the logs were read by hand.

## Decision

Keep the fallback exactly as it is: the same failures fall back, the same density refusal applies, and the same metadata is recorded. Make it visible to operators instead:

1. **Counter** `memoryos.extraction.docling.fallback`, recorded where the fallback is decided, once per Docling failure that reaches it. Labels, both bounded:
   - `reason`: the Docling failure, `connection_failed`, `timeout`, `internal` or `malformed`.
   - `outcome`: `read` (the native reader stood in and the Document is published from it), `refused` (the native text was too thin, Docling's failure stands) or `failed` (the native reader failed too, Docling's failure stands).

   All twelve series are registered at zero on startup, so `increase()` sees the first fallback rather than a series appearing from nothing.
2. **Alert** `MemoryOSDoclingFallback` in `infrastructure/observability/alerts.yaml`: any increase of the counter in the last 15 minutes. It fires on the first fallback and resolves 15 minutes after the last one. Its summary tells the operator that Documents are being published without tables or boxes and must be reindexed once Docling is fixed.
3. **Panel** "Docling fallbacks" on the overview dashboard, by `reason` and `outcome`.

## Not in scope

- Changing which failures fall back. Failing on HTTP 4xx instead of falling back was considered and rejected by the product owner on 2026-10-01: users keep searchable text while Docling is broken, and the alert is what was missing.
- A notification destination. Prometheus evaluates the repository rules and Grafana shows firing alerts; no contact point is configured ([observability README](../../../../infrastructure/observability/README.md)). Paging is a separate decision.
- Automatic reindex of fallback Documents. They keep `fallback_from=docling` in their metadata and are reindexed by a Source manager.
- The cause of the staging 404/413/503 responses, which is investigated separately.

## Placement

The counter lives in `sources` next to the decision, in `io.memoryos.ingestion.extraction`, and follows `SharePointProviderMetrics`: a package-private class that registers every series up front. `DoclingSourceContentExtractor` receives the `MeterRegistry` through its constructor; `FileProviderAutoConfiguration` passes the worker's registry. The API excludes this auto-configuration, so only the worker records it.
