# Persist run error messages and details

## Problem

MemoryOS stores only a stable error code (`SOURCE_EXTRACTION_TIMEOUT`, `SOURCE_GOOGLE_PERMISSION_DENIED`, …) in `source_run_errors`, `index_attempts`, and `source_sync_attempts`. The original exception message — "file is password-protected", "403 Insufficient Permission", "corrupted PDF" — is discarded. The UI can only render the generic code translation; there is no actionable detail for the user or operator.

Onyx persists `error_msg` (short summary) and `full_exception_trace` (stack trace) on `index_attempts`, then renders both in the UI. MemoryOS needs the same capability without copying Onyx's schema or trace verbosity.

## Contract

- `source_run_errors` gains `error_message` (bounded, human-readable summary) and `error_detail` (bounded, technical detail: exception class + message, or truncated stack trace).
- `index_attempts` gains `error_message` and `error_detail` so the per-attempt failure record carries the same evidence.
- `source_sync_attempts` gains `error_message` and `error_detail` for run-level failures.
- `ConnectorIndexingPort.fail` and `ConnectorSyncPort` terminal/retry paths accept an optional message/detail pair.
- `DefaultIngestionCoordinator` passes `exception.getMessage()` (and a bounded stack trace for unexpected `RuntimeException`) into the failure call.
- `DefaultConnectorSyncService` passes `GoogleDriveProviderException.getMessage()` and storage/ internal exception messages into `failedNode`, `terminal`, and `retry`.
- `SourceRunError` record and `SourceRunErrorResponse` expose `errorMessage` and `errorDetail` as nullable fields.
- `openapi.yml` schema updated; generated TypeScript types regenerated.
- UI run-history error rows show `errorMessage` inline; `errorDetail` renders behind an expandable row or modal, matching the existing list+detail pattern.

## Boundaries

- **Bounded length**: `error_message` ≤ 512 chars, `error_detail` ≤ 4096 chars. Truncate at the boundary; never store unbounded provider responses.
- **No secrets**: do not persist OAuth tokens, file content bytes, or internal endpoint URLs. The existing `WorkLeases.safeErrorCode` pattern is extended to sanitize messages.
- **No new failure taxonomy**: the same `code` values remain; `error_message`/`error_detail` are additive evidence, not a replacement for the stable code.
- **No backfill**: historical rows keep `NULL` message/detail; the UI must handle absent fields.
- **No Onyx trace verbatim**: MemoryOS stores a bounded detail string, not a full Python traceback.

## Runtime design

### Schema

```sql
ALTER TABLE source_run_errors
    ADD COLUMN error_message VARCHAR(512),
    ADD COLUMN error_detail VARCHAR(4096);

ALTER TABLE index_attempts
    ADD COLUMN error_message VARCHAR(512),
    ADD COLUMN error_detail VARCHAR(4096);

ALTER TABLE source_sync_attempts
    ADD COLUMN error_message VARCHAR(512),
    ADD COLUMN error_detail VARCHAR(4096);
```

The `source_run_index_transition` trigger copies `NEW.error_message`/`NEW.error_detail` into `source_run_errors` when it inserts an indexing failure row.

### Port changes

```java
// ConnectorIndexingPort
boolean fail(IndexWork work, String errorCode, @Nullable String errorMessage, @Nullable String errorDetail);
boolean retry(IndexWork work, String errorCode, @Nullable String errorMessage, @Nullable String errorDetail, int maxAttempts, Duration backoff);

// ConnectorSyncPort — terminal and retry gain the same pair
void terminal(Work work, String status, @Nullable String code, @Nullable String errorMessage, @Nullable String errorDetail);
void retry(Work work, String error, @Nullable String errorMessage, @Nullable String errorDetail);
void failedNode(Work work, Node node, String code, boolean unsupported, @Nullable String errorMessage, @Nullable String errorDetail);
```

### Capture points

| Location | Exception type | Message source | Detail source |
|---|---|---|---|
| `DefaultIngestionCoordinator` extraction catch | `ExtractionException` | `exception.getMessage()` | `exception.getCause()` class + message, or truncated stack trace |
| `DefaultIngestionCoordinator` runtime catch | `RuntimeException` | `exception.getMessage()` | `exception.getClass().getName()` + truncated stack trace |
| `DefaultConnectorSyncService` provider catch | `GoogleDriveProviderException` | `exception.getMessage()` | `exception.getCause()` class + message |
| `DefaultConnectorSyncService` storage catch | `ObjectStorageException` | `exception.getMessage()` | `exception.getClass().getName()` + truncated stack trace |
| `DefaultConnectorSyncService` internal catch | `RuntimeException` | `exception.getMessage()` | `exception.getClass().getName()` + truncated stack trace |
| `UserFileIngestionCoordinator` | `ExtractionException` / `Exception` | `exception.getMessage()` | bounded trace |

### Sanitization

Extend `WorkLeases.safeErrorCode` to a `safeErrorMessage`/`safeErrorDetail` pair:

- Strip control characters and newlines from `error_message` (single-line summary).
- Allow newlines in `error_detail` but bound to 4096 chars.
- Reject messages containing `Bearer `, `Authorization:`, or `access_token=` patterns (defense-in-depth; provider exceptions should not carry tokens).

### API

`SourceRunErrorResponse` gains:

```java
@Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
@Nullable String errorMessage,
@Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
@Nullable String errorDetail
```

`openapi.yml` updated; `pnpm generate` regenerates `web/src/lib/hey-api/types.gen.ts`.

### UI

- `source-run-history.tsx` error table gains an expandable row: summary line shows `fileName` + `errorMessage` (or `code` translation when `errorMessage` is null); expanded section shows `errorDetail` in a `<pre>` block plus existing `operationId`/`runId`/`currentItemStatus` fields.
- The Google Drive ACL panel originally listed here was removed (MEM-88, 2026-09-14); ACL snapshot `errorMessage` stays a server-side field.
- No new dependencies; reuse existing `<details>`/modal patterns.

## Non-goals

- No full stack-trace persistence (bounded detail only).
- No retry-budget changes.
- No new error codes.
- No backfill of historical rows.
- No Pause/Resume implementation (separate increment).

## Verification

- PostgreSQL test: `JdbcSourceRunHistoryRepository` and `JdbcIndexAttemptRepository` persist and return `errorMessage`/`errorDetail`.
- Worker test: `DefaultIngestionCoordinator` passes `ExtractionException.getMessage()` into `indexingPort.fail`.
- API contract test: `SourceRunErrorResponse` serializes the new fields.
- UI test: run-history error row renders `errorMessage` and expands to `errorDetail`.
- Browser verification: real local instance, trigger a Docling timeout on a PDF, confirm the UI shows the actual exception message.
