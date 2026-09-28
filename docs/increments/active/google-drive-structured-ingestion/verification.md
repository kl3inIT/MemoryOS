# Verification — Google Drive structured ingestion

## Selection edits preserve retained indexes — 2026-09-22

**Problem.** Saving a Drive selection bumped the scope revision, cleared every `google_drive_membership.eligible` flag and invalidated every Document mapping. Retained files showed `WAITING` and the next sync reacquired and re-extracted them because `unchanged`/`sameContent` require the new scope revision.

**Change.** `JdbcGoogleDriveSourceRepository.replace` now performs the whole reconciliation atomically: `retainSelectionMembership` keeps eligibility only for files still covered by a retained root or a still-approved exact file, proven by eligible membership or a still-readable published mapping under the previous scope and same credential revision; `carryForwardIndexedVersions` advances scope authority only for current versions whose Document was actually published (INDEXED item, eligible mapping, matching content SHA, a SUCCEEDED attempt); `revokeUncoveredMappings` drops retrieval eligibility for everything else. `DefaultGoogleDriveSourceService.activate` passes the pinned credential revision and approvals into `replace` and no longer blanket-invalidates mappings.

**Evidence.**

- `gradlew.bat :core:test --tests "io.memoryos.connector.googledrive.PostgresGoogleDriveSyncTest" --no-daemon` — all tests pass, including new regressions:
  - `selectionShrinkKeepsIndexedRootsDescendantsAndRetainedApprovedLinksReady`: selection saved mid-run keeps retained direct root, folder descendant and retained approved link `READY` with unchanged `lastIndexedAt`, Documents and versions; removed root/link lose read access; old run superseded; next sync acquires nothing and creates no index attempt.
  - `selectionEditCancelsPendingInputWithoutLosingItsPreviousPublishedDocumentOrStrandingRetry`: edit during extraction keeps the previous published Document readable, cancels the pending attempt, and the next sync completes the replacement.
  - `selectionEditDoesNotRestoreStaleOrRevokedIndexedAuthority` (credential/scope/membership/mapping/excluded): no authority is restored and no version is carried forward.
  - `coveredTargetsAreDeduplicatedAndRemovingApprovalDoesNotExcludeFolderContent`: removing an approval from an indexed file still covered by a selected folder causes no re-extraction.
- `gradlew.bat :core:test --tests "io.memoryos.connector.googledrive.GoogleDriveCredentialAuthorityTest" --tests "io.memoryos.connector.googledrive.GoogleDriveSelectionOperationTest" --tests "io.memoryos.connector.googledrive.persistence.PostgresGoogleDriveAclRepositoryTest" --no-daemon` — pass after updating `replace` call sites for the new signature.
- Pre-fix RED run of the new tests failed at the expected assertions (retained items lost `READY`, previous published Document lost read access), confirming the tests pin the reported defect.

**Environment boundary.** PostgreSQL-backed integration tests with the deterministic provider fixture; no live Google calls. The reported symptom was additionally confirmed read-only against the development database (all mappings `retrieval_eligible=false`, repeated SUCCEEDED attempts per scope revision).

**Remaining risk.** A file whose approval is removed while its covering root set also changed is conservatively revalidated by the next traversal rather than carried forward.

**Gate.** `gradlew.bat clean check` cannot run on this workstation: `clean` fails because the running development API/worker holds `core`/`connector` JARs (documented Windows limitation), and a parallel `check` run exhausted JVM memory in test executors. Equivalent evidence was collected serially: `:core:test --tests "io.memoryos.connector.*"`, `:connector:test`, `:worker:test` and `:api:test` each pass with `--max-workers=1`. The unfiltered `:core:test` executor still hits Java heap space on this host; no failure is attributable to this change.
