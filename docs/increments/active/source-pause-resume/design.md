# Source Pause and Resume

## Goal

Let an owner or Source manager pause one Source so MemoryOS stops starting new sync/index work for that Source, drains or cancels active work safely, and later resumes without forcing a full reindex. This is a resource-prioritization control, not deletion, failure suppression or Search authorization.

## Source evidence from Onyx

Onyx implements Pause as backend state plus cancellation, not just UI. Its status endpoint sets the connector to `PAUSED`, raises a stop fence, requests cancellation for active attempts and revokes queued Celery tasks. Workers and callbacks observe the fence; monitors settle attempts as canceled. Resume clears the fence, marks the connector `ACTIVE` and wakes the scheduler. Later attempts can reuse checkpoints and retained batches. The original process is not suspended in memory.

MemoryOS should adopt the same product semantics with its own primitives: database Source status, Redis dispatch guards, SOURCE_SYNC continuation rows, indexing claim fences and current-version publication checks.

## Product contract

- Pause is per Source and requires the same management authority as mutating Source configuration.
- Paused Sources stay readable in Source pages, file lists, ACL inspector and history.
- Pause never deletes indexed Documents, ACL snapshots or retained run history.
- Scheduled runs and manual reindex requests for that Source are blocked while paused.
- Active SOURCE_SYNC work stops at a safe boundary and records a canceled/paused terminal state rather than failure.
- Active item indexing stops before publication if the Source becomes paused; already-published current-version work remains valid.
- Resume re-enables scheduling and manually retriable work. Existing frontier/checkpoint state is reused where safe; no forced full reindex.
- Deletion remains stronger than pause. Removing a Source or file can cancel/cleanup even if paused.
- UI distinguishes `Pausing` from `Paused`: `Pausing` means active work still exists or remote parser work may still finish.

## Resource boundary

MemoryOS can reliably stop accepting new Redis deliveries and stop publishing work for the paused Source. It cannot honestly claim immediate CPU/RAM release for a Docling task already submitted to the external parser unless the Docling integration gains task cancellation and verifies that cancellation. First implementation should wait for current extraction to finish or fail, then block subsequent work. UI copy must say `Pausing — waiting for in-flight file processing` when applicable.

## Runtime design

### State model

Add a paused Source status or status field that does not overload `DELETING` or `FAILED`. Recommended model:

- `connector_credential_pairs.status`: add `PAUSED`.
- Derived Source summary reports `ACTIVE`, `INDEXING`, `PAUSING`, `PAUSED`, `FAILED`, `DELETING`.
- `PAUSING` is derived when the Source is requested paused but active `source_sync_attempts` or `index_attempts` still exist.

This keeps durable user intent (`PAUSED`) separate from operational drain state (`PAUSING`).

### Management API

Add `POST /api/sources/{sourceId}/pause` and `POST /api/sources/{sourceId}/resume`, or one explicit status endpoint with allowed values `ACTIVE|PAUSED`. Prefer explicit pause/resume endpoints for clean authorization and safer UI labels. Both require Source manage authority and reject deleting Sources.

Pause transaction:

1. Lock the authorized Source pair.
2. Set durable pause intent.
3. Cancel queued/not-started SOURCE_SYNC dispatch for that Source.
4. Mark active SOURCE_SYNC as cancellation requested or terminal `CANCELLED` only at a safe transaction boundary.
5. Mark queued/not-started item index attempts for that Source `CANCELLED`.
6. Prevent future dispatch relay from publishing paused Source work to Redis.
7. Return a summary including active in-flight counts so the UI can show Pausing versus Paused.

Resume transaction:

1. Lock the authorized Source pair.
2. Clear durable pause intent.
3. Leave canceled history immutable.
4. Re-enqueue due scheduled sync or allow user to run Sync now.
5. Return current summary.

### Dispatch and worker gates

Update `JdbcOperationDispatchRepository` candidate queries for `SOURCE_SYNC` and `INGESTION` so paused Source work is not claimed for Redis delivery. Existing cleanup and deletion paths must remain eligible.

Update `JdbcSourceSyncRepository.current` and `JdbcIndexAttemptRepository.isCurrent` so a pause makes active claims stale before further mutation/publication. Stale work should settle as canceled/paused, not failed.

Update `SourceSyncProcessor` and `DefaultIngestionCoordinator` to map paused/stale Source claims to a non-error outcome and acknowledge Redis delivery only after the database state is terminal or requeued safely.

### SOURCE_SYNC checkpointing

`google_drive_frontier` already contains pending nodes and page tokens. Pause should not delete them. A canceled/paused run may either:

- retain the same run and continue it after resume, or
- terminally cancel the run and enqueue a new run that reuses the frontier/checkpoint.

Choose the second option unless continuing the same operation can be made idempotent across Redis redelivery and UI history. It preserves simple history: each Pause creates a canceled run, Resume creates a new run that starts from retained frontier state.

### Item indexing

Do not reset current file state on pause. For queued attempts, mark `CANCELLED`. For active attempts, publication checks must fail once Source is paused; the coordinator records `CANCELLED` or `SUPERSEDED_BY_PAUSE` without increasing failure retry budget. On resume, a new attempt should be created only for current versions that still need indexing.

### UI

Source overview gets a Manage menu action:

- Active/Indexing -> `Pause source`.
- Paused -> `Resume source`.
- Pausing -> disabled `Pausing…` plus helper text.

Paused Source page should show a warning below Synchronization: automatic sync and indexing are paused; data and permissions may become stale. File table stays readable. Reindex and Sync now buttons are disabled with a paused tooltip. Delete/remove remains available if the actor has delete authority.

### Observability and history

Record pause/resume audit events. Add counters/log fields for paused dispatch skips and canceled-by-pause attempts. History should display `Canceled by pause`, not `Failed`, and should not count it as extraction/provider failure.

## Non-goals

- No global priority queue.
- No killing Docling containers.
- No guarantee of immediate external OCR cancellation.
- No Search/Chat authorization changes.
- No Google sharing changes.
- No background migration that rewrites old failures.

## Risks

- Acknowledging Redis deliveries too early can lose work. Require database terminal/requeue state before ack.
- Treating pause as failure will pollute retry budgets and Source error banners.
- Resuming a partial SOURCE_SYNC incorrectly can miss ACL revocations. Reuse frontier/page tokens only with source revision, credential revision and generation fencing.
- Claim guards must cover both SOURCE_SYNC and INGESTION; only guarding scheduler leaves already-published Redis deliveries running.
