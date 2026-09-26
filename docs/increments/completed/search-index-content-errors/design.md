# Search indexing: merge short blocks and report the real failure

## Status

Implemented on `nhuxuanviet/search-index-content-errors`: chunker merge, typed
codes, fail-fast coordinator, `searchErrorCode` on `SourceItem`, UI message
under the filename, English/Vietnamese strings. `openapi.yml` was updated by
hand because Docker was unavailable for `OpenApiContractTest`; CI regenerates
and compares it.

## Problem

A file whose extraction artifact holds more than 10,000 text blocks (the Tasco
annual report produced 11,003 paragraphs) can never be indexed:
`StructuredDocumentChunker` emits at least one chunk per non-empty block and
aborts at `MAX_CHUNKS` with a plain `IllegalArgumentException`. The failure is
deterministic, yet `SearchIngestionCoordinator` records the generic
`SEARCH_INDEX_FAILED`, retries it three times, and the projection reconcile
keeps retrying forever. The Source detail page shows only the "Search indexing
failed" badge: `documents.search_error_code` is never projected onto
`SourceItem`, so the UI cannot say what actually failed.

## Decisions

- **Merge adjacent text blocks before chunking.** Consecutive non-structural
  blocks (anything that is not a TABLE or IMAGE) under the same heading context
  are joined into one buffer, split on the existing `MAX_TOKENS` bound. A
  HEADING still flushes the buffer so the heading context never crosses a
  section boundary. Provenance arrays of merged blocks are concatenated; the
  chunk keeps the first block's index. `MAX_CHUNKS` stays as the safety bound
  for genuinely huge documents.
- **Typed content rejection.** The chunker throws
  `DocumentContentException` carrying a stable code instead of bare
  `IllegalArgumentException`:
  - `SEARCH_INDEX_ARTIFACT_INVALID` — wrong schema, malformed table cells or
    spans, unbounded chunk.
  - `SEARCH_INDEX_NO_TEXT` — the artifact contains no searchable text.
  - `SEARCH_INDEX_CONTENT_LIMIT` — chunk or table limits exceeded.
- **Fail fast and report the code.** `SearchIngestionCoordinator` treats
  `DocumentContentException` as permanent: the operation finishes FAILED on the
  first attempt and `documents.search_error_code` stores the specific code.
  Other failures keep the existing retry policy and `SEARCH_INDEX_FAILED`.
- **Surface the code.** `SourceItemView`/`SourceItemResponse` gain
  `searchErrorCode` (nullable), projected from `documents.search_error_code`.
  The file row renders `sourceStatusMessage(searchErrorCode)` under the
  filename when `searchStatus` is FAILED, so the user sees e.g. "The extracted
  document exceeds the supported indexing size" instead of a bare badge.

## Non-goals

- No change to reconcile cadence or the 15-minute repair window.
- No retry of permanently failed documents beyond the existing reconcile
  backstop; after the chunker fix the affected documents succeed on the next
  reconcile pass.
