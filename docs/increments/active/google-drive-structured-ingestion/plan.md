# FILE–Drive integration plan

Status: **reusable Google Drive credentials, independent Source creation and per-Source Automatic interval are implemented.** Backend, frontend and controlled browser gates pass. The owner session is restored; live save/reload preserves the user's one-minute interval and three indexed Sheets, and a subsequent automatic synchronization is verified. Source action feedback is implemented; the final all-notification five-second expiry review is recorded below. The earlier reusable-credential scenario created, indexed and removed a second Source without consent. Branch `nhuxuanviet/google-drive-structured-ingestion`, based on main `09d738ed7e42d077d312e5c3ee184eb4ab5f8b11`. Approved decisions: [design.md](design.md). No PR, push, merge or shared application deployment was performed.

User-approved scope: acquisition, synchronization, extraction and current Document only. No source ACL collection/enforcement, reader identity linking or document-read API/UI. Preserve Tenant/Owner authorization and exclude Drive from FILE PUBLIC. This is not all MEM-10/MEM-60 acceptance. The user authorized selective port, a backed-up fresh local database, isolated local MinIO, reuse of the existing Drive corpus and UI verification in Orca's embedded browser. No PR, push, merge or shared deployment is authorized here.

## Owner-credential and explicit-link cutover

- [x] Inspect local Onyx reference and its OAuth/service-account setup docs: OAuth JSON belongs to a credential; explicit URL selections disable account-wide retrieval.
- [x] Replace shared server OAuth client settings with encrypted owner-supplied app credentials across consent, callback and worker refresh; migrate legacy grants to require explicit reauthorization.
- [x] Replace browser candidate listing with pasted file/folder links and server-side URL/metadata validation; remove candidate API and generated callers.
- [x] Restrict sync enumeration to saved roots and descendants, removing account-wide Changes replay while retaining resumable traversal, publication fencing and incomplete-generation safety.
- [x] Run credential/selection/recovery regressions, regenerate and drift-check OpenAPI/client, and run the full backend/frontend/browser gates.
- [x] Complete post-cutover owner-app consent and explicit-link ingestion in the actual shared-identity review session: delete the old Source at the user's request, create its replacement and index the three retained Sheets links.

Ownership: Main integrates root validation, scoped synchronization, docs and final verification. AuthBackend owns OAuth/core credential persistence/provider protocol and API contracts. DriveUi owns frontend setup/selection and affected browser tests. API request contracts are `oauthClientJson` (optional only when reusing a stored app) and root `links`; source configuration adds safe `oauthClientConfigured`. No plaintext client secret may enter persisted session state or returned configuration. All work stays in the target; no donor edit, PR, push or shared application deployment.

## Onyx interface-only alignment

- [x] Inspect the user-supplied read-only `E:/Project/onyx` checkout and clarify scope: all existing MemoryOS screens, not new Chat/Search/Agents capabilities.
- [x] Trace actual source scheduling: refresh intervals, separate pruning intervals, and historical modified-time cutoff; none is a wall-clock worker start time.
- [x] Align shared tokens/primitives, responsive shell, session/access and invitation screens with Onyx, retaining existing authorization and functionality.
- [x] Port source catalog, setup progression, detail/configuration and operational status presentation; retain owner JSON secrecy and explicit-link ingestion.
- [x] Remove the mistakenly introduced scheduling API/schema/editor and schedule-only tests, keeping fixed five-minute synchronization and manual commands.
- [x] Verify authorization/selection/processing boundaries and inspect desktop/mobile and light/dark browser evidence with controlled API responses. Real-provider evidence is recorded separately below.
- [x] Widen the shared wide-page token so Sources and Add a source align; allocate readable table columns and contain horizontal scrolling, including off-screen accessible column labels.
- [x] Port the actual Onyx setup sidebar, credential card/table, OAuth modal and compact upload/paste component, replacing the custom full-page form while retaining Source-owned OAuth semantics.
- [x] Verify modal dismissal/secret clearing, credential continuation, callback-to-Connector setup and desktop/mobile behavior; move redirect/app configuration into collapsed Setup instructions rather than an extra Source field.
- [x] Initial simplification removed UUIDs, timestamps, duplicate email and horizontal scrolling while disclosing lifecycle actions; the metadata removal was superseded by the four-field review below.
  - Verified the retained real credential in Orca: full account-label click selects it, Continue opens Connector setup, Manage reveals the one-Source count and disabled attached-credential Delete, and closing Manage restores the compact row. The live picker had no horizontal overflow; dark-theme screenshot was inspected. No Source or credential mutation was submitted.
  - Frontend formatting, lint and TypeScript passed. All four focused Google setup browser scenarios passed with one worker and zero retries, including keyboard Space selection and existing shared lifecycle/secret-clearing guards. Removed the obsolete UUID-display assertion rather than preserving metadata as a presentation requirement. Backend and dependency contracts are unchanged; no new throwaway script remains.
- [x] Restore the four Onyx metadata fields (ID, Name, Created, Last Updated), replacing the overly reduced picker; retain compact responsive rendering and disclosed lifecycle actions.
  - Inspected the populated dark-theme table in the actual Orca session: exactly four column headers, short ID, one account name and compact dates, with no horizontal overflow. Continue opened Connector setup with the same credential; returned to the table without submitting any mutation.
  - Inspected the 390px-wide browser capture: name/selection first, short ID and two labelled dates remain readable without forcing narrow desktop columns. Formatting, lint and TypeScript passed; all four focused Google setup browser scenarios passed with one worker and zero retries, including keyboard selection and shared lifecycle guards. No backend, dependency or credential data changes.
- [x] Separate credential selection from Name; reduce setup subtitles and move synchronization/selection guidance into keyboard- and touch-accessible help popovers. Verify the table, help disclosure and existing selection/lifecycle behavior.
- [x] Remove non-interactive file-row hover that masks the Reindex button in dark mode; verify Reindex/Remove hover without triggering either action and retain shared Button focus styles.
- [x] Place Save selection directly below the links editor, right-aligned without a separate footer; keep conditional Reload before it and preserve save/conflict guards.
  - Final frontend verification: formatting, lint and TypeScript passed; 55 unit tests and all 23 browser scenarios passed with one worker and zero retries. Both help popovers passed keyboard opening, Escape dismissal and focus restoration in the existing Google setup scenario.
  - Inspected the mobile selection-column/help captures and the live dark-theme Source. Following the user's alignment correction, Save and the editor share the same right edge, with no action-footer border; formatting, lint and TypeScript passed again. Live pointer hover gives Reindex `rgb(38, 38, 43)` and Remove `rgb(51, 11, 9)` while their row stays transparent; no real reindex, remove or save action was triggered.
  - These presentation changes did not alter the backend, credentials or saved Source selection. Existing save, refresh and revision-conflict coverage remains passing.
- [x] Correct XLSX admission for small streaming ZIP64 workbooks; retain CRC/size integrity and archive/XML bounds, add a generated regression fixture and verify the actual failed upload through worker reindex.
  - [x] Runtime recovery exposed historical FAILED attempts leaking into the Source error after INDEXED. Aggregate only current unresolved item failures; preserve another item's real failure while clearing recovered ones.
  - The synthetic ZIP64 regression and current-failure recovery regression both failed before their fixes. Final `clean check :api:bootJar :worker:bootJar --no-daemon` passed; the spreadsheet suite has 7 passing cases and the source lifecycle suite has 14, with no skips or failures.
  - Authorized reindex of the existing 3,899-byte XLSX retained its item, upload timestamp and SHA-256. Final operation `f6c4dc80-0610-42bf-b0cc-9c1e82934104` reached INDEXED / ACTIVE with one Document, no pending work and null item/Source errors.
  - The original Google Source still has three INDEXED items and three Documents. Its shared credential remains ACTIVE at revision 1 with one attached Source. No Google selection, credential, upload bytes or scheduling interval was changed.
  - Local API/worker artifacts were rebuilt and restarted; both readiness checks passed. JetBrains inspection and LSP were unavailable; compilation and the repository Gradle gate supplied verification, not an IDE-inspection claim.
- [x] Keep a visible credential disclosure chevron and Close label when expanded; verify pointer opening and keyboard collapse without credential mutations.
  - Frontend formatting, lint, TypeScript and 55 unit tests passed. All four Google setup browser scenarios passed with one worker and zero retries; the settled mobile capture visibly retains Close and the upward chevron. Pointer opening and Enter collapse passed.

- [x] Persist per-Source Google synchronization intervals with a data-preserving V19 migration, independent schedule revision, owner/CSRF-protected update and all scheduling branches using the saved value.
- [x] Replace the fixed Automatic interval label with persisted display and an accessible inline editor; preserve interval/link drafts and require explicit recovery from stale schedule edits.
- [x] Regenerate the Spring OpenAPI snapshot and browser client, run backend/frontend gates and verify save/reload/conflict plus a real automatic due cycle without changing Google roots or credentials.
  - OpenAPI/client generation, complete backend/frontend gates and browser save/reload/conflict scenarios pass. After owner login was restored, a live save retained the user's interval of one minute, schedule revision 3 and three roots across reload. Without a manual sync during observation, `lastSyncedAt` advanced from `2026-09-07T15:41:58.974239Z` to `2026-09-07T15:43:18.775658Z`; the Source returned to no pending work with three Documents. This verifies the configured interval and normal due-scan scheduling, not an exact sixty-second worker-start guarantee. No interval was changed through SQL.

The user has approved reusable Google Drive credentials for multiple Sources. The prior Source-owned UI adaptation is superseded by the independent credential lifecycle in the updated design. Read-only Onyx inspection confirmed independent credential creation, provider/owner filtering and guarded deletion. MemoryOS keeps its existing tables while replacing the Google singleton constraints, atomic credential-plus-Source OAuth creation and orphan-credential cleanup.

The earlier UI-only baseline passed `pnpm check` (54 unit tests) and all 21 browser tests. Current reusable-credential evidence is recorded below. The separate dependency audit reported four high and two moderate advisories, including transitive `fast-uri` paths under development dependency `shadcn` (patched in 3.1.6). Dependency remediation was not part of this change; no security-clean claim is made.

Integration ownership: Main owns design/docs, generated API/client integration and final verification. Core owns credential persistence, shared authority and atomic Source creation; API owns HTTP/session contracts and HTTP tests; web owns the Onyx credential workflow and browser coverage. All share explicit contracts and skip validation while sibling edits are in flight. Per-Source intervals are now approved; ACL and other advanced Onyx backend capabilities remain excluded.

## Reusable credential cutover

- [x] Separate credential identity/catalog and migrate Google singleton constraints without changing existing IDs or encrypted grants.
- [x] Implement shared reconnect/revoke/auth-failure fencing, guarded credential deletion and Source-only cleanup.
- [x] Create Source with an existing credential and validated links atomically; keep remote calls outside SQL transactions.
- [x] Cut over owner HTTP endpoints, encrypted consent state and callback routing to credential identity.
- [x] Bind Onyx Select credential/Create New/Connector steps to real metadata and independent Source creation; migrate all callers.
- [x] Verify migration preservation, tenant isolation, shared invalidation, concurrency boundaries and actual browser reuse; run backend/frontend gates after integration.

Local runtime preservation: retain Source `45a91ebf-c73d-47ba-95f6-04e96f7cf808` and its three indexed Sheets. Do not edit the frozen `D:/Capstone Project/MemoryOS` donor or read-only `E:/Project/onyx` reference. No PR, push, merge or deployment.

### Reusable credential verification

- Backed up the existing review database to ignored `.tmp/drive-review-private/before-reusable-credentials.dump` and verified its restore catalog before V18. The migrated Source ID, Connector ID, credential ID, ACTIVE state, authority revision, encrypted OAuth-app fingerprint, three root IDs, three item IDs and three Document IDs matched the private baseline exactly.
- Final backend gate: `gradlew.bat clean check :api:bootJar :worker:bootJar --continue --no-daemon --max-workers=2` passed with JDK 25 and real Docling at `http://127.0.0.1:15062`. Core/connector test results were reused from the preceding successful tasks with unchanged inputs; API/worker tests executed. The API fixture was corrected to include the required email scope. Existing JDK/dependency and fixture-shutdown JDBC/OTLP warnings remain.
- Final frontend `pnpm check` passed: generated API/route stability, lint, formatting, TypeScript, production build and 55 tests in 14 unit files. `pnpm test:e2e --workers=1 --retries=0` passed all 23 scenarios after the modal-guidance correction. Desktop modal/card captures were visually inspected; the setup URI is hidden until instructions are expanded.
- In the real authenticated Orca browser, select the retained credential → Continue → submit a separately named Source with one retained Sheet link. Source `dd6a698c-3ba3-42b7-b8ec-c5f5d1dd0e6d` reached ACTIVE with one INDEXED item/current Document, no pending work and no error. Both Sources reported credential `76bf3016-c10b-4672-a597-8419296169a9`; catalog attachment count was two and authority revision remained one. No JSON upload or Google consent occurred.
- Delete the temporary Source through its real confirmation dialog. Its API then returned 404; the credential remained ACTIVE at revision one with one attachment. Original Source remained ACTIVE with three Documents, the same three item IDs, no pending work and no error. Provider files were neither edited nor deleted. The built API/worker and local web remain available for review.
- No JetBrains MCP or LSP was configured; compilation and repository gates are the available static evidence. Live shared revoke/reconnect was not exercised against the retained grant to avoid interrupting the user's original Source; deterministic PostgreSQL/HTTP tests cover those transitions. Broader format/provider-outage/ACL acceptance remains outside this bounded reuse verification.

## Approved sequence and delivery state

- [x] Read the Linear FILE architecture and MEM-9/10/60/63 requirements against current main/V12; trace upload, storage, Redis, Document and access boundaries.
- [x] Freeze the donor and create the dedicated target worktree. Preserve existing local databases and operator configuration privately before creating the isolated database.
- [x] Fix public input/write/sync contracts and numeric admission bounds without a new Gradle module, provider registry or ACL schema.
- [x] Implement owner-bound Google consent, encrypted grants, revision/CAS semantics, reconnect/disconnect and revision-checked selected roots.
- [x] Implement tracked raw writes, offline native snapshots/readers, Java XLSX/CSV routing and existing Docling/Tika reuse.
- [x] Implement durable SOURCE_SYNC, page/frontier checkpointing, incomplete-run recovery, input adoption and publication fencing through the existing worker topology.
- [x] Implement Google source management and generated API/browser contracts while retaining FILE and the closed document-read boundary.
- [x] Run controlled regression suites, real FILE/MinIO/Docling scenarios, and embedded-browser setup/upload/indexing checks; correct failures rather than suppressing them.
- [x] Finish the final repository gate and FILE cleanup/restart scenario after the last pending-work correction.
- [x] Complete fresh Google OAuth → real root picker → native snapshots → worker → current Documents/private artifacts and durable sync recovery after worker restart.
- [ ] Complete remaining live format, provider-change/outage and Drive disconnect/removal acceptance.

## File-level implementation map

Target: `C:/Users/adim/orca/workspaces/MemoryOS/google-drive-structured-ingestion`. Frozen donor: `D:/Capstone Project/MemoryOS`. All edits belong to the target. This is the actual implemented map, replacing the earlier candidate filenames.

| Owner/path | Implemented entry points and responsibility | Reuse/cutover |
| --- | --- | --- |
| `api/.../source` | `GoogleDriveCredentialController`, `GoogleDriveSourceController`, `GoogleDriveOAuthCallbackController`, `GoogleDriveAuthorizationSessionState`, `GoogleDriveAccountClient`, `GoogleDriveOAuthProperties`, request/response records | Independent credential catalog/consent/lifecycle and explicit Source creation; owner-bound state/PKCE/nonce and dedicated acquisition callback |
| `api/.../security` | `GoogleDriveCallbackSecurityConfiguration` | Exact callback chain; main Keycloak/invitation login and Actor-only session remain intact |
| `core/.../connector/application` | `DefaultGoogleDriveAuthorizationService`, `DefaultGoogleDriveConnectionService`, `DefaultGoogleDriveSourceService` | Owner/account pinning, shared credential authority versus payload CAS, atomic Source creation, root validation/revisions and scheduling |
| `core/.../connector/persistence` | `GoogleDriveCredentialCipher`, `GoogleDriveCredentialConfiguration`, `JdbcGoogleDriveCredentialRepository`, `JdbcGoogleDriveSourceRepository` | Tenant/credential AAD, encrypted grant storage, selected roots and atomic authority transitions; no manifests or ACL/Groups tables |
| `core/.../connector` | `GoogleDriveProvider`, `ConnectorSyncPort`, `SourceInputFormat`, `SourceInputDescriptor`, extended `IndexWork` | Provider-neutral network/session and stored-input contracts; no SDK/Jackson type or live fetch instruction crosses into ingestion work |
| `core/.../connector/application` and `persistence` | `DefaultConnectorSyncService`, `JdbcSourceSyncRepository` | Resumable selected-root reconciliation, complete-generation pruning, confirmed-input release, raw adoption and shared credential fencing; no Changes feed |
| Existing Connector repositories/services | `JdbcSourceItemRepository`, `JdbcIndexAttemptRepository`, `JdbcSourceQueryRepository`, `JdbcSourceDocumentRepository`, source/cleanup services and repositories | Keep FILE hash dedup; add Drive remote identity, current-input publication and reconciliation deferral; include cleanup in pending-work projection; explicitly keep Drive outside FILE PUBLIC |
| `core/.../objectstorage` | `ObjectWriteService`, `DefaultObjectWriteService`, `JdbcObjectWriteRepository`, extended stored-object persistence | Reservation before PUT, verified completion, transaction-bound adoption/release, uncertain-write tombstones; reuse S3 IO without browser receipt or artifact-lifecycle substitution |
| `connector/.../provider/google` | `RestGoogleDriveProvider`, properties/auto-configuration, `NativeSnapshot`, `GoogleSheetsSourceContentExtractor`, `GoogleDocsSourceContentExtractor` | Bounded native acquisition and offline structural extraction; replace donor aggregate Changes and manifest control flow |
| `connector/.../provider` and `provider/file` | `SourceContentExtractorRouter`, `SourceContentExtractorAutoConfiguration`, `StructuredContent`, `SpreadsheetSourceContentExtractor` | Native descriptor first, detected binary format second; XLSX/CSV Java tables; retained Docling PDF/DOCX/PPTX and Tika UTF-8 text leaves |
| `core/.../ingestion` | `SourceSyncProcessor`, `DefaultIngestionCoordinator`, workload/dispatch/metrics integration | SOURCE_SYNC joins the existing PostgreSQL → Redis → fenced processing path; INGESTION opens adopted MinIO input without Google access |
| `worker` | Existing composition, control-plane, topology, relay and consumer classes | Due-source enqueue, third workload and abandoned raw-write cleanup; no Google-specific executor or alternate polling mode |
| `core/.../db/migration` | V13 tracked writes; V14 credentials; V15 durable sync; V16 owner apps; V17 explicit roots; V18 reusable credentials | Separate authority/payload revisions, selected-root progress and preservation-safe credential reuse; applied migrations remain unchanged |
| Gradle/runtime/MinIO composition | Shared `connector` dependency in API, provider configuration, explicit worker extractor composition, worker `raw/*` PUT policy | Four modules remain; private storage and existing secret delivery remain; no staging rollout performed |
| `web/src/features/sources` and routes | Google credential catalog, OAuth modal, Connector explicit-link form and management panel; FILE CSV/XLSX admission; generated route/API clients | Real owner management only, visible RESTRICTED boundary, no document viewer or donor approval/manifest UI |

### Runtime call chain

```text
Reusable credential + separately named Source with explicit roots
  → GoogleDriveSourceController / DefaultGoogleDriveSourceService
  → durable SOURCE_SYNC / JdbcSourceSyncRepository
  → existing relay / RedisStreamWorker / SourceSyncProcessor
  → DefaultConnectorSyncService / GoogleDriveProvider
  → tracked ObjectWriteService raw snapshot
  → atomic input/version/index-attempt adoption
  → existing INGESTION / SourceContentExtractorRouter
  → offline native, spreadsheet, Docling or Tika reader
  → extraction-artifact staging
  → fenced current Document + index completion transaction
```

## Correctness decisions confirmed during implementation

- Normal refresh rotation increments `payload_revision`, not `credential_revision`. A losing stale authentication failure cannot revoke the winning rotation; reauthorization/revoke still advance authority and fence old publication.
- Synchronization uses selected-root reconciliation, not account-wide Changes. Unrelated account items are neither enumerated nor imported; only complete traversal of accepted roots permits pruning.
- A failed enumeration releases successfully adopted, confirmed current-generation inputs but withholds final cursor advancement and pruning. Failed files remain retryable.
- Reconciliation can temporarily clear eligibility while an index is running. An otherwise-current authorized input is deferred and later redispatched rather than permanently superseded; `deferred_attempts` does not consume its extraction retry budget. Changed/revoked/excluded inputs cannot use this path.
- Raw reservation/adoption/cleanup and extracted-artifact ownership remain distinct. Uncertain PUT completion retains a durable late-writer tombstone; referenced objects are never generic-cleanup candidates.
- The real FILE UI exposed two gaps: its old extension allowlist rejected supported CSV/XLSX, and source `pendingWork` omitted nonterminal item cleanup. Both were corrected at their owning admission/read-model boundary, retaining the existing browser polling mechanism.
- Current-schema H2 fixtures could not execute the PostgreSQL-specific new migrations. They now use the shared fresh-PostgreSQL fixture. Historical bounded migration tests remain separate. Removed the migration-count-only `SchedulerSchemaMigrationTest`; current-data and real control-plane behavior tests remain.
- Worker integration contexts used the same fixed Redis host port. They now request `arconia.dev.services.redis.port=0`; normal development port 56379 remains unchanged. The isolated manual E2E Redis uses 16379.

## Verification ledger — 2026-09-07

The checks and live scenarios below predate the owner-app/explicit-link/interface cutover unless explicitly identified in [Owner-app and interface verification](#owner-app-and-interface-verification). They are retained as historical evidence, not a substitute for current acceptance.

### Environment and data safety

- Existing local database dumps and original operator/configuration data were preserved under ignored private `.tmp/drive-e2e-private/`; no staging reset or deployment was performed.
- Fresh local database `memoryos_drive_structured` runs in `memoryos-postgres-local` on loopback 15555. API applied V1–V15 and became healthy.
- Isolated MinIO bucket `memoryos-drive-e2e` on 19000/19001; Redis on 16379; local Keycloak realm `memoryos-drive-test` on 18180; API 18080, worker 8081, browser origin 8080, pinned real Docling 15062.
- The exact Google callback `http://127.0.0.1:8080/login/oauth2/code/google-drive` was already registered in the actual Google Cloud client. Docs API was observed enabled. No credentials, grants or callback secrets are recorded here.
- After the clean gate, API and worker were restarted and both health endpoints returned `UP`. Orca's fresh Google setup tab is prepared with source name `Google Drive structured E2E`; authorization has not been restarted while the user is unavailable. The expired Google tab is preserved separately. The local runtime remains available for the human approval step.

### Executed checks

| Check | Observed result |
| --- | --- |
| Main compilation and focused authority/sync/storage/OpenAPI checks | Passed; committed OpenAPI and browser clients generated from actual controllers |
| Full backend gate before final runtime correction | Core, connector and API passed; fixed worker Redis context collision, then the complete `:worker:test` passed with real Docling configured |
| FILE pending-work regression | Extended `SourceApiIntegrationTest.indexesAndCleansUpOneFileThroughTheAuthorizedApi` failed before the SQL fix at the pending-work assertion, then passed after it; tests pending=true before cleanup and false after terminal cleanup |
| Final frontend `format` + `check` | Passed: generated API/route stability, lint without warnings, formatting, TypeScript, 14 files / 53 unit tests, production build, four emitted WOFF2 assets and no inline font URLs |
| Affected routed browser regressions | `file-source-setup.spec.ts` and `identity-shell.spec.ts`: all 19 Chromium scenarios passed, including updated FILE format admission and existing source/authentication behavior |
| Final full backend `clean check --no-daemon` | Passed in 3m12s with JDK 25 and real Docling configured. All four module test tasks executed; eight compile tasks reused cache. Dependency/JDK warnings and test-fixture shutdown JDBC/OTLP log noise remain; this is not a warning-free claim |
| IDE/static inspection | No LSP or JetBrains MCP configured; no IDE-clean claim. Compilation, ArchUnit/Modulith and repository gates are the available evidence |
| Documentation references | All 155 local Markdown file links across the 14 updated canonical/increment documents resolve |

### Actual embedded-browser and storage proof

- Orca owner login through local Keycloak reached the authenticated MemoryOS shell, source catalog and Google setup. Screenshots were saved and visually inspected; this is real UI, not a component fixture.
- Real FILE CSV creation used browser-direct MinIO PUT, API finalize and Redis/worker indexing. UI reached INDEXED. Private artifact read showed a 4×3 table retaining the quoted comma, quoted multiline value and `Việt Nam`. CSV reindex returned to INDEXED.
- Source-detail XLSX upload reached INDEXED. Its artifact retained the `Metrics` sheet, 4×2 sparse grid, `A1:B1` merge, Unicode title, and inert `SUM(B2:B3)` formula with cached numeric value 30.
- CSV removal deleted its Document/raw ownership. The stale DELETING UI exposed the pending-work defect described above. After the fix, XLSX removal while the worker was stopped reported source ACTIVE, item DELETING, `pendingWork=true`. Restarting the worker completed the durable cleanup, and the existing page automatically changed to “No files yet” without navigation or reload.
- A final real TXT upload indexed successfully through the retained text path. Deleting that nonempty source via its actual confirmation dialog navigated to the empty `/admin` source list. PostgreSQL reached zero source, item, Document, stored-object, upload, source-upload and extraction-artifact rows; both item removals and source deletion were SUCCEEDED. Authenticated GETs for the recorded XLSX and TXT raw keys returned 404. This combines real UI, worker restart, relational convergence and targeted storage evidence; it is not a full bucket inventory.
- The three temporary FILE fixtures were removed after verification. Ignored backups, runtime configuration and screenshots remain for continued acceptance work.
- An isolated worker-credential IAM probe returned raw PUT 200, exact-byte GET 200, anonymous GET 403, DELETE 204 and subsequent GET 404. Its probe object was removed. This proves the new local raw-write policy, not the complete Google acquisition lifecycle or a staging rollout.
- Real PostgreSQL/Redis/MinIO worker integration also exercised DOCX through Docling, Redis loss/redelivery, item/source cleanup and deactivated-Tenant cleanup. Deterministic provider/native/authority/sync tests remain distinct from live Google acceptance.

### Live Google acceptance after fresh authorization

The first MFA session expired. After the user returned, the local stack was restored and fresh OAuth completed through the real owner/browser callback, creating source `8e37ecb5-a8a7-4d09-8546-88de91a10f34`. No old callback, encrypted donor grant or database-injected credential was used. The earlier retained-grant decryption failure is historical and does not imply provider revocation.

- The actual picker listed My Drive content and saved four selected files: the retained VETC, SAVICO and TASCO AUTO KPI Sheets plus `MemoryOS Google Drive Acceptance Document`. Source access remained RESTRICTED; the UI says document access is not configured.
- All four files acquired into tracked private raw objects and reached INDEXED with four current Documents and four active extraction artifacts.
- Every represented Sheet cell was compared by coordinate, complete native cell payload and exact formatted text between the live snapshot and its extraction artifact. VETC has 504 formatted cells over 42×12; SAVICO and TASCO AUTO each have 492 over 41×12: **1,488 exact matches**. All three retain the allocated sparse 1000×26 grid. The historical 1,476-cell expectation is not the current corpus: VETC now has one additional represented row.
- Native Docs paragraph text matched the provider snapshot exactly. This fixture contains a simple paragraph; rich Docs structures are covered by deterministic fixtures, not established by this live document.
- With the real worker stopped, the owner requested synchronization. The API reported INDEXING and `pendingWork=true`, retaining all four documents. After worker restart, it converged to ACTIVE, `pendingWork=false`, four INDEXED items, the same four Document IDs, and still exactly four raw objects/artifacts.
- Removing the direct Docs root through the real selection UI converged to three INDEXED items/Documents. Authenticated GETs of that known Doc's raw object and extraction artifact both returned 404; its cleanup completed. Selecting the existing acceptance folder instead reacquired the Doc through folder traversal and restored ACTIVE/four INDEXED items with no pending work. The provider file was not edited or deleted. Screenshot `.tmp/drive-e2e-private/google-live-four-indexed.png` was visually inspected.
- Private comparison evidence is retained at ignored `.tmp/drive-e2e-private/live-native-evidence.json`. No provider content or credentials are published by that ledger.

Remaining live acceptance: Slides/binary corpus, richer native Docs, provider-side change/outage recovery, and full Drive disconnect/source-deletion cleanup. Current live evidence must not be generalized to those unexercised paths or used to close full MEM-10/MEM-60 scope. MFA is no longer the blocker.

### Requested developer review runtime

The user requested the implemented branch with the shared development Keycloak and existing development MinIO; PostgreSQL, Redis, Docling, API and web remain local. Preserve the isolated E2E data/evidence rather than changing its tenant or copying grants into the development tenant.

Shared realm discovery and authentication of the supplied `memoryos-web` client succeeded. The user supplied the [service directory](https://linear.app/memory-os/document/memoryos-danh-ba-dich-vu-e481f92219a3), which distinguishes server-loopback `127.0.0.1:19000` from the workstation-accessible S3 endpoint `https://memoryos-objects.72-62-193-33.nip.io`. The directory's deployment uses bucket `memoryos`, confirmed from managed staging configuration. Its provisioner secret was retrieved privately from Infisical staging and successfully authenticated; the dev lookup's `*not found*` sentinel is not a usable credential.

After the user authorized SSH inspection, the actual service secrets were read privately from the API/worker secret mounts on the shared server. Both `memoryos-api` and `memoryos-worker` authenticated to the public S3 endpoint and returned HTTP 200 with the exact readiness sentinel. The user need not supply additional credentials. No secret values are recorded here.

Read-only inspection initially found worker raw GET/DELETE without PUT, and CORS restricted to staging. The user then explicitly approved updating the existing shared identities rather than creating local-review accounts. The worker policy now additionally permits `s3:PutObject` on `arn:aws:s3:::memoryos/raw/*`; all other statements were preserved. `.env.staging` now retains the staging origin and adds `http://127.0.0.1:8080`. Before applying, the original policy and environment were backed up under `/apps/memoryos/local-review-backup-20260907T032711Z`. Resolved Compose configuration differed only in the MinIO CORS value; the image was unchanged. Only MinIO was recreated, with `--no-deps` and a successful health wait.

The real shared worker credential subsequently returned raw PUT 200, exact-byte GET 200, DELETE 204 and GET 404 for one uniquely named smoke object, which was removed. Browser preflight returned the matching allowed origin for both localhost and staging. Shared MinIO, API and worker were healthy afterward. This policy change affects every process using the shared worker identity, including the deployed worker and the configured local worker; no deployed API/worker restart or application deployment was performed.

The local review API, worker and web are running against shared Keycloak/MinIO, with fresh local database `memoryos_drive_review`, separate Redis on 16380, and local Docling on 15062. API health and worker readiness returned HTTP 200/UP. Private runtime configuration is under ignored `.tmp/drive-review-private/`; the original E2E database, Redis and object storage remain separate and preserved.

Actual sign-in initially reached shared Keycloak but was rejected with HTTP 400 and `Invalid parameter: redirect_uri`. The user explicitly approved adding `http://127.0.0.1:8080/login/oauth2/code/memoryos` to the shared `memoryos-web` client. The original client representation was backed up as `keycloak-memoryos-web-before.json` in the same private server backup directory. Admin update returned 204; subsequent inspection confirmed only the redirect-URI set changed, with both staging callbacks preserved and no wildcard, client-secret, origin or role change. No Keycloak restart was required.

Afterward, the local sign-in route returned 302 to shared Keycloak, whose login form returned HTTP 200. Orca's actual browser showed the Email/Password/Sign In form. API health, worker readiness and Docling are UP. The review runtime is ready for the user to sign in at `http://127.0.0.1:8080`; authenticated callback/session and Google-source verification under that real user still await the user's sign-in. No authenticated-login completion is claimed yet.

### Owner-app and interface verification

At this earlier review, the user limited Onyx to **presentation only**. The unapproved temporary schedule API and migration were removed before the local review migration; owner OAuth JSON, explicit links, the five-minute cadence, manual commands and server-backed read-only access filtering were retained. This is historical baseline evidence: the later reusable-credential V18 and explicitly approved per-Source interval V19 supersede its scheduling scope.

| Check | Observed result |
| --- | --- |
| Backend `./gradlew.bat clean check :api:bootJar :worker:bootJar --no-daemon` | Passed in 6m29s with `DOCLING_TEST_ENDPOINT=http://127.0.0.1:15062`. API, core, connector and worker tests ran; OpenAPI drift check used normal non-write mode. Both executable artifacts were built |
| Frontend `pnpm check` after integration corrections | Passed: generated API stability, CI-image check, warning-denying lint, formatting, TypeScript, 14 unit files / 54 tests, route stability and production build; four emitted WOFF2 assets, no inline fonts |
| Browser `pnpm test:e2e --workers=1 --retries=0` | Passed all 21 tests, including owner-app upload/paste, secret clearing/non-echo, explicit-link selection, FILE lifecycle, session/access and invitation flows with routed responses |
| Visual inspection | FILE setup desktop/mobile/light/dark and Google credential/saved-link mobile captures inspected. Narrow-layout overflow checks passed. A settled CSS probe confirmed enabled dark secondary-button text is white at 85% opacity over `rgb(25,25,30)`; the initially dim capture was a transition frame, not a token defect |
| Actual review runtime | Verified boot JARs started with retained private environment and bounded local heap; API and worker readiness returned `UP`. V16/V17 applied to the existing backed-up review database |
| Local preservation before requested replacement | Read-only SQL reported schema V17, one Source, three selected roots, five recorded sync attempts and zero unfinished syncs. No new schedule migration was applied |
| Actual Orca browser | User sign-in was renewed and the existing private owner Web OAuth JSON was uploaded through the real setup form. Google consent completed for the same account; callback created a new Source with an ACTIVE credential. No password, callback or grant was fabricated |
| Requested Source replacement and live ingestion | Old Source deletion completed in the UI and its API returned 404. Three retained file links were saved on the new Source; manual synchronization reached ACTIVE with three documents, all three items INDEXED, no pending work and no error code. The last successful index timestamp observed was `2026-09-07T06:05:45.703695Z`. Original Google files remained available and were reacquired |
| Shared page alignment | Sources and Add a source both measured a 1280px outer container at the same desktop viewport, with identical left alignment, 16px horizontal padding and 52px top padding. Statistics and Total docs headings fit on one line. A narrow real-browser frame exposed an absolutely positioned accessible-label overflow; containing it within the table scroll region removed document-level horizontal overflow |
| Inspection limits | No LSP/JetBrains MCP was available. JDK/dependency warnings and fixture-shutdown JDBC/OTLP noise remain; no warning-free or IDE-clean claim |

The UTF-8 byte-limit regression failed before the parser correction and passed afterward, including the exact 16 KiB boundary and over-limit Unicode. Integration also corrected typed invitation navigation and kept malformed JSON errors generic/non-echoing. Obsolete fake-composer and fixed-viewport-height assertions were removed; navigation, authorization, recovery and secret-isolation contracts remain exercised.

Browser captures are ignored outputs under `web/test-results/`. The isolated color probe was closed without touching the user sign-in tab. Private review configuration, owner-client JSON and the pre-cutover database backup remain outside version control for continued acceptance; no credentials or provider contents are published here. A PowerShell startup hit Windows paging-file pressure during parallel verification; running the same built applications directly with bounded heap restored readiness without changing machine settings or unrelated services.

**Remaining acceptance limits:** the fresh owner-app/explicit-link path is now verified against the three existing Sheets, not every provider format or failure mode. The older four-document Google run remains separate evidence. Remaining format/provider-outage/full-cleanup cases listed above are not claimed complete. Do not close the full linked issues or merge this increment on the strength of this bounded corpus and controlled fixtures alone.

## Observable acceptance references

- Owner/OAuth/state/nonce/account/credential/root revision and FILE lifecycle: [Connector matrix](../../../tests/connector.md).
- Reservation-before-PUT, integrity, crash/adoption/late-write cleanup and binary/native admission: [Object storage matrix](../../../tests/object-storage.md).
- Offline native/table routing, bounded acquisition, partial-run retry/pruning, leases, Redis recovery and publication deferral: [Ingestion matrix](../../../tests/ingestion.md).
- Stable current Document, transactional artifact replacement, stale authority and orphan cleanup: [Document matrix](../../../tests/document.md).

Fixtures prove deterministic boundaries; actual service/browser runs prove only the path and corpus exercised. Google live acceptance remains mandatory, not replaced by FILE success or donor history.

## Per-Source interval verification — 2026-09-07

- Preserved a matching PostgreSQL 18 custom-format database backup and both previous runtime jars under ignored `.tmp/drive-review-private/` before V19. No credentials, selected links or private uploaded bytes were copied into tracked files.
- Final backend `./gradlew.bat clean check :api:bootJar :worker:bootJar --no-daemon` passed in 2m34s with JDK 25 and `DOCLING_TEST_ENDPOINT=http://127.0.0.1:15062`: 28 tasks, 17 executed and 11 from cache. The shared core fixture now applies V19; the in-flight regression uses non-invoking Mockito restubbing. The CSRF case exercises the existing non-simple-header guard rather than expecting an Origin-only rejection that the browser contract does not implement.
- Frontend `corepack pnpm check` passed generated API/route stability, CI-image validation, warning-denying lint, formatting, TypeScript, all 60 unit tests in 14 files and the production build. Four emitted WOFF2 assets were verified.
- `corepack pnpm test:e2e tests/e2e/google-drive-source-setup.spec.ts` passed all four Chromium scenarios. Mobile interval save/reload, independent Source values, desktop keyboard cancellation and retained credential controls were exercised. The desktop/mobile `google-automatic-interval-*.png` captures under ignored `web/test-results/` were visually inspected.
- The local API and worker were rebuilt/restarted and both readiness checks passed. V19 exposes the retained Source at interval 5, schedule revision 1 and unchanged scope revision 2. Real automatic operations completed successfully at `2026-09-07T14:09:30.294Z` and `2026-09-07T14:15:35.577Z`; the latter set the next due time to `14:20:35.577Z`, with no error. Read-only checks matched all original root/item IDs, three INDEXED items, three Documents and the ACTIVE credential at authority revision 1.
- The owner session was subsequently restored. The current live Source is saved at one minute and schedule revision 3, retained across reload with all three roots. The successful automatic-cycle timestamps above supersede the earlier login blocker and five-minute-only evidence; stale-conflict recovery remains controlled-browser evidence rather than a conflicting write to the user's live Source.
- JetBrains inspection and LSP were unavailable. Compilation, the repository gate, browser verification and live runtime evidence are the checks performed; this is not an IDE-inspection claim. No PR, push, merge or shared deployment was performed.

## Source action feedback review

- [x] Add visible Reindex acceptance and operation-specific completion/failure feedback; preserve concurrent-item tracking and abort observers when leaving the Source.
- [x] Replace the saved-selection inline acknowledgement with an accessible, transient success notification.
- [x] Replace persistent automatic-interval acknowledgements with transient notifications, retaining validation, conflict recovery and focus restoration.
- [x] Add Synchronize now acceptance and terminal acquisition feedback using the same notification lifecycle, without claiming downstream indexing completion.
- [x] Verify successful, failed, superseded and unavailable operation outcomes plus notification dismissal on the actual browser surface; retain the user's current interval and three selected Sheets.

The user-reported missing feedback and permanently displayed interval acknowledgement are the reproduction evidence. Source detail now uses the existing Radix dependency for page-scoped notifications and a shared abortable observer for the exact returned operation. The live Source announced Synchronization requested and Synchronization complete, explicitly allowing indexing to continue; a same-value interval save announced Automatic interval saved for one minute. Onyx setup remains paused; its General/Specific controls were inspected read-only to answer the user's scope question.

- [x] Apply the user's final timing requirement: every notification, including errors, disappears after five seconds without pausing for hover, keyboard focus or an unfocused Orca pane.
- [x] Verify that expiry regression and consolidate the final frontend and live-browser evidence.

The original Radix timer paused when Orca's visible browser pane lost focus; direct inspection observed `document.hasFocus() = false` with no notification hover. The updated regression also reproduced the old persistent-error behavior: Reindex failed remained after its eight-second removal deadline. Each toast now owns a five-second timer cleaned up on dismissal/unmount; Radix still supplies presentation, announcements and manual dismissal, not expiry timing. No API, backend or dependency contract changed in this feedback pass.

Final feedback verification:

- `corepack pnpm check` passed after the final timer change: generated API/route stability, CI-image check, warning-denying lint, formatting, TypeScript, 60 unit tests in 14 files and the production/font build. `corepack pnpm test:e2e --workers=2 --retries=0` passed all 26 Chromium scenarios. The formerly failing expiry regression now passes with a hovered/focused toast and a window-blur event; it covers success, informational and error expiry plus early keyboard dismissal.
- In the actual Orca Source page, Synchronize now reached Synchronization complete. At `2026-09-07T15:54:26.036Z`, that notice was visible with `document.hasFocus() = false`; at `15:54:31.054Z`, the notification list was empty with focus still false. No reload, focus restoration or manual dismissal was used. The user's one-minute interval and three Sheets were retained.
- Desktop reindex/synchronization and mobile selection/interval captures under ignored `web/test-results/` were visually inspected. The canonical Connector contract and verification matrix now describe operation-specific outcomes and the fixed five-second policy. No new runtime dependency or throwaway script remains.
- The separate dependency audit still reports four high and two moderate advisories, including transitive `fast-uri` under development dependency `shadcn`. This feedback change does not remediate dependencies and makes no security-clean claim.

## Approved General / Specific implementation

- [x] Define General/My Drive and Specific/explicit-link contracts and preserve original Source scope.
- [x] Persist scopeMode with a preserving V20 migration; validate and fence create/scope changes.
- [x] Reuse durable traversal for the verified My Drive root; retain retry, incomplete-generation and pruning safety.
- [x] Update required HTTP request/response mode, generated clients and all affected callers.
- [x] Add General/Specific creation and selection controls while preserving drafts, interval and five-second feedback.
- [x] Verify scope-switching, root identity, incomplete traversal, authorization and migration boundaries.
- [x] Run backend/frontend gates and exercise both modes on the real runtime without expanding the original Source.

Ownership: DriveScopeCore owns core persistence, application and sync changes plus core/connector/worker regression callers. DriveScopeWeb owns frontend source creation/selection and all web fixtures. Main owns API contracts/tests, OpenAPI/client regeneration, integration, live runtime verification and canonical/increment consolidation. During concurrent edits, workers skip builds, tests, linters and formatters; Main runs the final gates after integration.

Scope verification:

- JDK 25 `./gradlew.bat clean check :api:bootJar :worker:bootJar --no-daemon --no-parallel --max-workers=1` passed: 28 tasks, 4m35s. The serialized rerun replaced a transient Windows JDBC socket `BindException` in the preceding concurrent gate; no assertion or implementation change was used to hide that failure.
- `corepack pnpm check` passed: API/route generation checks, Oxlint, Oxfmt, TypeScript, 64 unit tests and production build. `corepack pnpm test:e2e --workers=2 --retries=0` passed all 27 scenarios. Mobile Specific-empty and General-created/saved screenshots were inspected; no empty Saved scope or horizontal overflow.
- Live Flyway applied V20 from V19. Original Source `45a91ebf-c73d-47ba-95f6-04e96f7cf808` retained credential `76bf3016-c10b-4672-a597-8419296169a9`, all three root/item identities and Documents, `SPECIFIC`, scope revision 6, interval one minute and schedule revision 3, matching the saved pre-migration configuration.
- The authenticated UI created temporary Source `d349dd83-7817-4562-bd69-5482fbe3c006` as `GENERAL`, with `roots: []`, scope revision 1 and default interval five minutes. This exercised real credential-bound Google root resolution. Before worker startup, the UI saved `SPECIFIC` with one existing Sheet link, yielding scope revision 2 without changing interval/schedule revision. Worker acquisition/indexing reached one `INDEXED` Item, one Document and `pendingWork: false`.
- No live whole-My-Drive traversal was run: the old General generation was superseded before worker startup. Durable General traversal, root revalidation, pagination, incomplete-generation no-prune and mode-switch publication fences are covered by the backend regressions; the live test deliberately bounded acquired content to one Sheet.
- JetBrains inspection and LSP diagnostics were unavailable: no Java/TypeScript language servers or JetBrains tools were configured. Compilers, static checks and runtime evidence above are the verification used.

## Final action feedback and branch delivery

- [x] Complete accurate notifications for source creation, upload/finalization, sync, reindex, remove/delete, selection/interval saves, credential management and explicit refresh/reload, retaining accessible dialog errors.
- [x] Verify five-second expiry, operation outcomes, partial-success recovery and navigation feedback in the integrated frontend gates and real runtime.
- [x] Delete the temporary scope-verification Source and reconfirm the original Source, three Sheets/Documents and reusable credential remain unchanged.
- [x] Consolidate canonical contracts and verification for the approved branch delivery.

Delivery authorization: create focused commits and push `nhuxuanviet/google-drive-structured-ingestion` to origin. Do not open a PR, merge, close the broader linked issues or deploy the shared runtime. Git history and the remote branch record delivery; the increment remains active until a later authorized merge.

### Final feedback verification

- Final frontend integration passed `corepack pnpm check` (14 unit files, 68 tests, generated API/routes, Oxlint/Oxfmt, TypeScript and production build) and `corepack pnpm test:e2e --workers=2 --retries=0` (35 browser scenarios). Mobile creation/saved-scope captures cover the actual scope controls; credential captions wrap words normally instead of splitting them mid-word.
- In the authenticated runtime, Reindex announced acceptance and the temporary Sheet returned to `INDEXED` with a new successful index timestamp. Synchronize now announced acceptance and completion; its completion explicitly did not claim downstream indexing was complete.
- Live Remove announced acceptance and completion, closed its confirmation dialog, and left zero Items/Documents with no pending work. Live Delete Source announced acceptance, closed its confirmation dialog, then navigated to `/admin` with a named deletion-success notice; a subsequent GET returned 404 for the temporary Source.
- DOM observations measured sync acceptance/completion notices at 5.004/5.007 seconds and the deletion-success notice at 5.004 seconds across navigation. The ordinary deletion-acceptance notice did not leak onto the destination route.
- After cleanup, the original Source retained exactly its saved credential, root and Item identities, `SPECIFIC`, public configuration `revision: 6`, `credentialRevision: 1`, `credentialStatus: ACTIVE`, one-minute interval and `scheduleRevision: 3`. All three Items remained `INDEXED`, all three Documents remained present, and the reusable credential returned to one attached Source.
- Removed the temporary browser observer after verification. Private screenshots/configuration backups remain ignored; no test Source, credential mutation, PR, merge or shared deployment is part of this delivery. Whole-My-Drive live traversal and the broader provider acceptance recorded above remain unclaimed. JetBrains IDE inspection was unavailable; the checked-in Gradle gate, compiler checks and exercised runtime are the verification evidence.
