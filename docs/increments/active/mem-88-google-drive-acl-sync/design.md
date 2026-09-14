# MEM-88 — Google Drive ACL synchronization

## Approved boundary

Collect provider permissions, hand off current synchronization evidence and expose the approved Source-authorized read-only inspector. Do not link reader identities, expand Google Group membership, calculate effective access, change Search/Chat/citations or widen PUBLIC/RESTRICTED policy. OAuth acquisition and Source-management authority are not reader authority.

## Provider evidence

Read-only staging probe on 2026-09-13 used the existing encrypted OAuth grant in memory, with `drive.readonly`. Google `permissions.list` succeeded for two already-selected financial-report files; each returned one owner permission, including permissionDetails. No credentials or permission emails were printed; no Google permissions, staging rows, or configuration were changed. This proves readable ACLs for those files, not all sharing patterns or a separate Tasco reader identity. Controlled fixtures must cover pagination and revocation.

## Runtime design

Extend the existing GoogleDriveProvider.Session with `List<Permission> permissions(String fileId)`. The real adapter fetches all pages within its existing request/time/byte budgets; rejects token cycles, duplicate IDs and malformed responses; returns no partial result. Preserve user/group/domain/anyone identity, role, optional email/domain/expiration/deleted/pendingOwner/allowFileDiscovery and permissionDetails. Pagination completion is not proof of a complete effective-access graph or group membership.

SOURCE_SYNC collects ACL after validating selection membership and before the unchanged-content shortcut. It includes file and folder nodes, avoids re-reading folder ACL on every child-list page, and does not stage content or enqueue indexing for ACL-only changes. Permission failures record safe typed failure evidence independently of successful content acquisition; authentication failure retains existing credential invalidation behavior. Existing source/credential/scope revision and operation-token fencing guards publication. A failed ACL request cannot overwrite the last complete permission snapshot.

Concrete connector persistence owns a new additive V54 table, keyed by Tenant/Source/provider file ID, with source-qualified foreign-key cleanup. Atomic snapshot replacement stores structured permission JSON, monotonically increasing observation revision, scope/credential revision, operation identity, last attempt/success times and last error. Reading returns explicit synchronization status and current-context evidence, and resolves Document mappings without treating Google permission IDs as Actor IDs. Missing, failed, stale and context-invalid observations are not interchangeable with a successful empty list. Source deletion, selection replacement, item removal and credential revocation must not leave a snapshot appearing current.

Recheck permissions on every scheduled/manual source traversal even when provider content version is unchanged. The schedule is an observation cadence, not a guaranteed revocation SLA: source runs can wait or fail. Consumers receive actual timestamps and context validity and must impose their own freshness policy. No effective-read grant is provided by this increment.

## Verification

Provider HTTP fixtures cover real request construction, OAuth reuse, page completion, malformed/cyclic/failed later pages and preserved fields. PostgreSQL tests cover atomic replacement, retained snapshot on failure, tenant isolation, source lifecycle/revision changes and metadata-to-Document binding. Worker integration proves an unchanged file refreshes ACL without acquiring bytes or adding index attempts. Compile and run the repository gate, then exercise the actual synchronization runtime against a controlled Google-compatible HTTP fixture. Live Google read-only probe is separate from controlled sharing/revocation evidence. No staging deployment, real-file permission changes or new broad OAuth scopes are authorized.

## Onyx comparison requested by the user

Reference revision: `20f442786a0c2da5d1c3da8705b0866000117559` (2026-09-01). This is the local checkout examined, not a claim about uninspected later upstream changes.

Read the local reference checkout at `D:/Capstone Project/MemoryOS/.tmp/onyx/`, not MemoryOS notes. `backend/ee/onyx/external_permissions/google_drive/doc_sync.py:66-82,375-420` drives a separate permission synchronization through `retrieve_all_slim_docs_perm_sync` and yields document and hierarchy-node external access. `backend/onyx/connectors/google_drive/doc_conversion.py:949-999` constructs SlimDocument from metadata and permissions without acquiring document bytes or invoking a parser. Adopt this separation of ACL observation from content extraction; ACL persistence must work before a Document exists.

`permission_retrieval.py:33-41` uses paginated `permissions.list` with `supportsAllDrives=True`. Adopt explicit complete paging and Shared Drive support. Do not copy its continue-on-403/404 behavior into a successful snapshot: this increment preserves failure evidence and the prior complete snapshot instead.

`doc_sync.py:209-265` retains inherited folder IDs and Shared Drive IDs as group-like references; it also explicitly notes an initial-unshared-folder/later-sharing inheritance limitation at lines 220-224. Preserve provider inheritance details rather than claim effective group expansion. Folder observations belong in the handoff; a Google Group is not a MemoryOS Actor.

Do not copy Onyx's fallback granting access to the retriever when ACL retrieval fails (`doc_sync.py:187-207`), its user impersonation/admin retry strategy, or its conversion of `anyone` into an internal public flag. Those depend on Onyx authorization policy and enterprise identity authority absent from this approved scope. Keep `allowFileDiscovery`, role, expiration, view and inherited-permission restrictions explicit instead of flattening provider semantics.

Google files.version is not a content-only revision. Binary content reuse must compare verified bytes and retain immutable acquisition provenance; metadata-only version changes must not enqueue OCR simply because ACL synchronization observed them. Native document handling must likewise avoid claiming that unchanged files.version is the only possible ACL-only path.

MemoryOS reuses its existing fenced SOURCE_SYNC traversal instead of adding Onyx's separate permission scheduler. The adopted separation is from content extraction and reader authorization, not a separate scheduling guarantee. Per-file ACL success/failure is recorded before content reuse; the actual traversal cadence and errors remain visible in the handoff provenance.

## Approved Source management interface expansion

The user requested an ACL inspector and a clearer Source page after reviewing screenshots of the existing scattered sections and unhelpful run table. Keep the existing theme/components and source management authority; do not introduce document-read enforcement or edit Google sharing.

- Group Source status, latest successful indexing and sync actions into one overview. Present Content, Google Drive permissions, History and Connection/settings as clearly separated, keyboard-accessible sections/tabs instead of an unbounded sequence of headings.
- Remove the exact Google Drive access-configuration disclaimer from the summary.
- Make run history explain the result using retained counters; never fabricate a corpus total or percentage from overlapping counters. Move per-file errors into a dedicated selected-run detail panel with readable filenames, actionable provider-error guidance and existing pagination.
- Add a read-only source-scoped ACL browser, including files without successful observations. Show principal, provider role, inheritance/expiry and independent snapshot status/context/timestamps. Missing, failed-only, stale and successful-empty results must remain distinguishable.
- Add actor-authorized GET endpoints under `/api/sources/{sourceId}/google-drive/acl`, following Source read authority (global SOURCES_READ or managed-Group source scope). A bounded searched list returns summaries; a selected-file endpoint returns the complete retained snapshot. No provider calls occur in these HTTP reads.
- Generate OpenAPI/client types from the API contract. Verify cross-source denial and snapshot edge states, then verify the actual browser at desktop and narrow widths. Run a local instance without replacing unrelated retained runtime data or changing staging.

The interface extension is implemented and verified; [verification](verification.md#approved-interface-verification) records backend/frontend gates, controlled browser states and the real local readiness/login boundary. This increment remains active until merge.

## Real-runtime feedback: history diagnostics and extraction

The user reported duplicate generic timeout messages in the Source header, file list and run details, and failed PDF/DOCX extraction in the real Orca browser. Keep historical run outcomes immutable while showing each retained error's current item status alongside it, so recovery is not mistaken for a still-failing file. Expose the already-retained error code and operation/run identifiers for log correlation, with technical details progressively disclosed. Do not infer recovery for removed/unmatched items.

Distinguish inability to connect to the extraction service from a processing timeout using the actual transport exception, without leaking endpoints, credentials or exception messages into the API. Move the Source-level error below the synchronization overview as requested. Preserve bounded pagination and tenant/source authorization. Verify the existing real files through Orca after restoring a reachable Docling runtime; worker readiness alone is not extraction proof.

The subsequent UI requests remove the redundant provider subtitle and separate Search-readiness line from the file table, and render current file status with the existing badge component and state-specific icons. The API still exposes downstream Search readiness; an indexed-content badge does not establish Search readiness or financial correctness.

### Additional Onyx source evidence: error details and pause

The same pinned local checkout was inspected, expanding its sparse checkout for `web/src/sections/modals/PreviewModal`. `IndexAttemptsTable.tsx:64-68,194-211` renders the short `error_msg` and opens `ExceptionTraceModal` only when `full_exception_trace` exists. The modal renders the actual trace through `CodePreview` and provides a copy button. `backend/onyx/db/index_attempt.py:430-449` persists the summary and trace separately; `background/celery/tasks/docfetching/tasks.py:701-727` captures the exception and avoids overwriting an already-terminal attempt's cause. MemoryOS's retained codes and correlation IDs are not equivalent to this trace contract; historical causes cannot be reconstructed from a generic code.

At `backend/onyx/server/documents/cc_pair.py:470-561`, Pause sets the connector to `PAUSED`, raises a Redis stop fence, requests durable cancellation of queued/running index attempts and revokes queued Celery tasks. The response is asynchronous. `docprocessing/tasks.py:487-490` settles cancellation, fetch/processing callbacks observe the fence, and the fetch watchdog terminates a still-running subprocess after its attempt becomes terminal. Resume clears the fence, sets `ACTIVE` and triggers the scheduler; `run_docfetching.py:599-648` can reuse a valid checkpoint and retained batches in a new attempt. This is cancellation with resumable work, not suspension of the original process.

## Acceptance gap closure — approved 2026-09-14

An audit of the Linear acceptance list against this increment found four gaps. The user approved closing them before the Source access-type work (PUBLIC/PRIVATE rename, inspector removal) and before a separate Auto Sync issue.

- **Distinct 403 classification.** `httpFailure` discards the Google `errors[0].reason` for 403 and reports `NOT_FOUND`, so a missing OAuth scope is indistinguishable from an unavailable file. Add `SCOPE_INSUFFICIENT` for reason `insufficientPermissions` or an `ACCESS_TOKEN_SCOPE_INSUFFICIENT` ErrorInfo detail on any Drive call; every Drive flow that stops or invalidates the credential for `AUTHENTICATION` does the same for it (`requiresReconnect()`), and SOURCE_SYNC ends the run with its own code. Add `ACCESS_DENIED` for any other non-quota 403 returned by `permissions.list` only; it is recorded on that file's ACL snapshot while content acquisition continues. Content, selection-tree and linked-discovery 403 handling stays `NOT_FOUND` because those paths depend on it. Quota reasons keep precedence.
- **Role-change evidence.** The SOURCE_SYNC runtime test covers same-ID reader→writer and an added permission: revision increments, the stored role changes and `GoogleDriveAclChanged` is published. An identical re-observation increments the successful-observation revision without publishing an event.
- **Handoff example.** The connector spec gains a concrete snapshot example and the successful, failed-with-retained, unobserved, revoked and stale states with their fail-closed interpretation. Reconciliation with the enforcement owner is a user handoff; this increment records it as pending, not done.
- **Live Google fixture.** Using a user-owned test folder, observe owner-only, added Viewer, Viewer→Editor, removed share, and a file shared *to* the connected account as Viewer (whether `permissions.list` is readable). The user performs every sharing change; evidence is read through the inspector and database without recording email addresses.

The requested Pause investigation is not a Pause implementation. MemoryOS currently has no paused Source state; its scheduler and publication guards exclude deletion but not pause. A future implementation must address queued dispatch, active claims, checkpoint retention and actual resource release, not just disable the automatic schedule. A Docling request already submitted to another service is a separate resource owner: stopping a MemoryOS worker alone does not prove remote OCR stopped. ACL observation currently shares Source traversal, so a pause policy must also state its effect on permission freshness without inventing a revocation SLA.
