# Ingestion verification matrix

MEM-81 resource measurement (2026-09-12): opt-in Windows `ChatFileResourceMeasurementTest` reuses the valid large XLSX fixture, then invokes the production extractor/chunker in fresh 512 MiB-heap JVMs. One near-100 MiB, one near-250 MiB and three concurrent callers passed; OS working set/private commit, sampled Java heap, temp and per-caller wait/processing times are recorded in [measurement evidence](../increments/active/chat-attachments-production/verification.md#live-vision-và-đo-tài-nguyên--2026-09-12). Fixture creation is excluded; these are parser-process measurements, not whole API/worker/production sizing. Tika currently makes a second temporary detection copy; all temp files are reclaimed. Repeat with `MEMORYOS_CHAT_RESOURCE_TEST=true` and `--rerun-tasks` to avoid cached measurement evidence.

MEM-81 local regression adds private PNG signed PUT/finalize, lost Redis delivery/consumer restart, canonical publication and raw deletion to `WorkerFileProcessingIntegrationTest`, using its real PostgreSQL/Redis/MinIO fixtures. `TikaSourceContentExtractorTest` covers isolated Chat HTML and PNG/JPEG/WebP decode, malformed header-only PNG and child timeout. Service-backed OCR/model and large-corpus measurements remain separately open in [MEM-81 verification](../increments/active/chat-attachments-production/verification.md).

MEM-81 reuse checkpoint (2026-09-12): `SpreadsheetSourceContentExtractorTest` compares actual Source and Chat canonical output, including BOM/quoted CSV, dates, cached values, merges and visibility; `GoogleNativeExtractionTest` connects native extraction to chunking. `BoundedDoclingClientTest.sourceAndMultipartUseIdenticalSdkOptionsAndPrivateAuthentication` exercises both HTTP transports against a local server, not live OCR. `UserFileIngestionCoordinatorTest` proves failed/lost renewal prevents staging/publication. `LargeChatSpreadsheetExtractionTest` parses valid image-heavy XLSX within 2 MiB below 100/250 MiB, while Source rejects the same input above 10 MiB. Resource/SLO and real Docling acceptance remain separate; commands/results and fixture correction are in the [MEM-81 verification ledger](../increments/active/chat-attachments-production/verification.md#reuse-source--spring-ai--docling--local-2026-09-12).

Integrated verification — 2026-09-09: `gradlew.bat clean check --continue --no-daemon --console=plain --no-parallel --max-workers=2` passed in 7m29s with `CI=true`, `ARCONIA_DEV_SERVICES_ENABLED=false`, `MEMORYOS_OPENAPI_WRITE=false`, and real Docling at `DOCLING_TEST_ENDPOINT=http://127.0.0.1:15062`. JUnit results report **424 tests, zero failures/errors/skips**: core 271, connector 55, API 72, worker 26. Gradle reports 24 tasks: 18 executed and 6 restored from cache; this is not an entirely uncached claim. Redis-dependent worker fixtures own their containers and mapped ports. Existing JVM/dependency, OTLP fixture-shutdown and Bean Validation informational messages remain; no JetBrains MCP/LSP was available, so compiler/project gates are the fallback, not IDE-clean evidence.

Main V1–V17 has no diff against `origin/main`. A disposable PostgreSQL upgrade applied those exact SQL scripts, seeded 10 witness rows across 10 tables, then transactionally applied V18–V29; every original row value survived. This verifies the SQL upgrade, not an upgrade of an existing Flyway history table. Fresh API fixtures and the isolated API runtime also applied all 29 migrations through Flyway. Frontend/browser evidence is in the [Connector matrix](connector.md); exact runtime boundaries and cleanup are tracked in the [integration ledger](../increments/active/google-drive-structured-ingestion/plan.md#isolated-main-integration--2026-09-09).

Historical boundary: the records below predate this isolated integration. Their original commands, migration numbers and scenario names are retained; they are not additional integrated runtime results. Branch V13–V24 maps to integrated V18–V29 (+5), while main V1–V17 stays unchanged. Historical Owner-only Google authorization predates the global `SOURCES_MANAGE` cutover.

This matrix identifies implemented coverage and the exact exercised boundary, not every possible service-backed scenario. Current-schema core/API fixtures use PostgreSQL; historical migration tests stay separate. The final post-pagination MEM-76 backend gate executed every test task, including worker Redis and real Docling integration, with zero failures or skips. Frontend/browser, isolated runtime and separate root/history/corpus measurements are recorded in the [MEM-76 ledger](../increments/active/google-drive-structured-ingestion/plan.md#mem-76-verification--2026-09-08). No JetBrains MCP/LSP inspection was available; compiler/project gates are the fallback, not an IDE-clean claim.

`WorkerFileProcessingIntegrationTest` exercises real PostgreSQL, Redis and MinIO, including extraction-artifact bytes, redelivery and cleanup. With `DOCLING_TEST_ENDPOINT`, it indexes DOCX through the real service; otherwise it uses the TXT compatibility case. `DoclingServeIntegrationTest` also requires that endpoint and verifies Vietnamese DOCX table values. Record optional-service runs explicitly: a skipped service test is not runtime evidence.

Final command: `gradlew.bat clean check --rerun-tasks --no-daemon --no-parallel --max-workers=2`, JDK 25.0.3, `CI=true`, `ARCONIA_DEV_SERVICES_ENABLED=false`, `ARCONIA_DEV_SERVICES_REDIS_ENABLED=false`, `MEMORYOS_OPENAPI_WRITE=false`, `DOCLING_TEST_ENDPOINT=http://127.0.0.1:15062`. **PASS in 4m28s; 24/24 tasks executed, 367 tests, zero failures and zero skips**: core 217, API 69, connector 55, worker 26. All four test tasks executed rather than restoring cache results. The endpoint-enabled Docling and FILE worker cases were included. Existing unchecked/dependency/JDK warnings and API fixture-shutdown session/JDBC cleanup errors remain in logs; this is not a warning-free claim.

Fixture corrections made this gate independent of ambient services and connection churn: core fixtures own/close bounded Hikari pools, and Redis-dependent worker tests own their containers and mapped ports. A Windows/JDK versus Docker/PostgreSQL clock probe reproduced a deliberately advanced `CURRENT_TIMESTAMP` fixture still being ineligible to the immediate JVM-clock dispatcher in 776/1,000 samples (maximum observed skew 1,664,200 ns); backdating the fixture by one second gave 0/1,000. Four existing forced-due statements now use the established past-time convention, without a production scheduler change, sleep or retry. The two affected sync/history classes passed in 1m22s before the complete uncached gate. Sanitized clock evidence is `.tmp/mem76-clock-race.json`; ownership policy is in the [testing guidelines](../guidelines/testing.md#fixture-ownership).

MEM-76 selection admission/checkpoint/recovery and exact run-attribution/retention regressions are listed in the [Connector matrix](connector.md#mem-76-selection-and-run-history-boundaries). They exercise real PostgreSQL persistence with controlled collaborators, including stale claims, owner/credential fences, duplicate publication, owned-child cancellation, membership deferral and safe storage failure. The [selection/history probes](connector.md#measured-selection-and-history-capacity--2026-09-08), [100k-file no-change traversal](#controlled-100k-file-traversal) and [100k-item Files UI fixture](connector.md#real-ui-files-pagination-over-100k-items) have separate measured boundaries; none establishes live Google throughput or 100,000-file acquisition/indexing.

| Contract | Evidence |
| --- | --- |
| An idle stream read using the production 2-second BLOCK returns normally within the default 5-second client timeout | `RedisExecutionTopologyIntegrationTest.idleStreamReadReturnsNormallyWithProductionBlockDuration` |
| UTF-8 TXT/Markdown extraction detects content despite misleading extensions; timeout terminates the bounded child JVM and close remains bounded | `TikaSourceContentExtractorTest.extractsUtf8TextAndMarkdownWithoutTrustingTheExtension` and `timeoutTerminatesTheChildAndCloseRemainsBounded`; direct Tika PDF/DOCX tests do not define production routing |
| PDF/DOCX/PPTX use Docling JSON; semantic text/table ordering and provenance survive without binary image data in normalized text; image-only, empty-table, over-limit and partial output are rejected; text bypasses Docling and service failure has no Tika fallback | `DoclingSourceContentExtractorTest`, including `excludesEmbeddedImagesAndPreservesSemanticContentAndProvenance`, `rendersTableOnlyDocumentInRowAndColumnOrderWithoutTextExport`, `rejectsImageOnlyDocumentEvenWhenTextExportContainsImageMarkup`, `rejectsTableWithoutSemanticCellText` and `rejectsSemanticTextOverCharacterLimitWithoutTextExport`; `SourceContentExtractorRouter` routing contract |
| CSV retains BOM-aware UTF-8, quoted newlines, escaped quotes and inert formula-like cells; malformed encoding/quotes and normalized overflow fail | `SpreadsheetSourceContentExtractorTest.csvRouterParsesBomQuotedNewlinesEscapedQuotesAndFormulaLikeTextAsCells` and `csvRejectsMalformedUtf8UnclosedQuotesAndNormalizedOutputOverflow` |
| XLSX retains sparse coordinates, zero/false, merges and cached formulas without evaluation; ZIP bombs and excessive sparse dimensions fail | `SpreadsheetSourceContentExtractorTest.xlsxRetainsZeroFalseSparseCoordinatesMergeAndCachedFormulaWithoutEvaluation` and `xlsxRejectsDecompressionBombAndHugeSparseDimensions` |
| Small XLSX files with streaming ZIP64 descriptors extract correctly; corrupted directory CRC or size metadata is rejected | `SpreadsheetSourceContentExtractorTest.xlsxReadsSmallWorkbookWithStreamingZip64DataDescriptors` and `xlsxRejectsCorruptedCentralDirectoryChecksumOrSize`; `streaming-zip64.xlsx` is synthetic and contains no uploaded user data |
| Sheets retain all accepted tabs, sparse/default/zero/false values, inert formulas, merges and coordinates; incomplete windows, excessive represented cells and wrong snapshot versions fail | `GoogleNativeExtractionTest.sheetsPreserveSparseDefaultsZeroFalseFormulaMergesAndAllTabs`, `rejectsIncompleteSheetWindowsAndOversizedRepresentedGrid`, and `rejectsWrongSnapshotVersionAndNormalizedOutputOverflow` |
| Docs retain ordered tab headings, lists, nested table content and footnotes offline; native output overflow fails | `GoogleNativeExtractionTest.docsPreserveOrderedTabHeadingsListsTablesAndFootnotesOffline` and `rejectsWrongSnapshotVersionAndNormalizedOutputOverflow` |
| Native acquisition retrieves complete Sheet windows and Docs tabs, exports Slides as PPTX, rejects provider revision races and enforces explicit bounded-response/request failures; extraction works after the provider fixture stops | `RestGoogleDriveProviderTest.sheetsAcquireAllWindowsAndExtractAfterGoogleIsUnavailable`, `docsUseNativeTabsAndSlidesExportPptx`, `rejectsDriveVersionAndDocsRevisionRaces`, and `boundedResponsesRequestCountsAndTypedProviderFailuresAreExplicit` |
| PostgreSQL `SKIP LOCKED` dispatch claims exclude concurrent relays and rediscover nonterminal operations; a separate bounded cancellation transaction terminates inactive-Tenant indexing; transport failures never change business state | `PostgresSourceLifecycleTest.concurrentRelayClaimsOnceAndRediscoveryRepublishesFromPostgres`, `transportFailureDefersWithoutFailingTheOperation`, and `inactiveTenantCancelsPendingIndexWorkWithoutPublishing` |
| SOURCE_SYNC checkpoints selected-root pages, resumes with a new claim without restarting or duplicate adoption, and rejects stale execution | `PostgresGoogleDriveSyncTest.checkpointsPagesAndResumesWithoutRepublishingOrRestartingTheTraversal` |
| General uses the same frontier through nested, paginated and resumed My Drive enumeration without touching unrelated shared collections | `PostgresGoogleDriveSyncTest.generalTraversesOnlyMyDriveThroughNestedPaginatedAndResumedFrontiers`; `RestGoogleDriveProviderTest.myDriveAliasResolvesToARealParentAndIncompleteSearchCannotCompleteItsTraversal` |
| Unverifiable or lost General roots cannot turn into an empty complete generation; incomplete traversal retains unseen Documents, and Specific link shrink prunes only after complete reconciliation under the new revision | `PostgresGoogleDriveSyncTest.resumedGeneralTraversalCannotPruneWhenCurrentMyDriveRootCannotBeVerified`, `generalRootLossDuringEnumerationDoesNotTurnIntoAnEmptyCompletedGeneration`, `incompleteGeneralEnumerationRetainsUnseenDocumentsUntilACompleteGeneration`, and `replacingSpecificLinksFencesOldWorkAndPrunesOnlyAfterTheNewSelectionCompletes` |
| An incomplete run releases confirmed adopted inputs for indexing but retains failed files for retry and does not prune unseen items | `PostgresGoogleDriveSyncTest.itemFailureReleasesConfirmedSnapshotsAndRetainsTheFailedFileForRetry` and `failedAcquisitionDoesNotPruneUnseenItems` |
| Folder reconciliation clears membership eligibility; otherwise-current in-flight indexing is deferred and later completes without spending its extraction retry budget | `PostgresGoogleDriveSyncTest.reconciliationDefersInFlightIndexingUntilTheUnchangedInputIsConfirmed` |
| Trashed selected folders reconcile descendants; unrelated files are never read; old scope work and explicit removal cannot resurrect content | `PostgresGoogleDriveSyncTest.trashingASelectedFolderReconcilesItsPreviouslyIndexedDescendants`, `synchronizesOnlyExplicitRootsWithoutReadingUnrelatedFiles`, and `scopeReplacementAndExplicitRemovalCannotBeResurrectedByOldWork` |
| Specific Shared Drive folder descendants are accepted, but unconfigured Specific sources or legacy whole-drive roots stored as Specific cannot enumerate/acquire the account | `PostgresGoogleDriveSyncTest.acceptsAnExplicitSharedDriveFolderAndIndexesOnlyItsChildren`, `unconfiguredSourceCannotScheduleAccountWideSynchronization`, and `legacyPhysicalDriveRootsFailClosedBeforeEnumerationOrAcquisition` |
| Enqueue, unavailable-grant postponement, terminal item failure and retry exhaustion use the saved Source interval without changing short retry backoff | `PostgresGoogleDriveSyncTest.enqueueUsesTheSavedIntervalAndUnavailableGrantsPostponeInsteadOfEnqueueing`; `terminalItemFailureReschedulesUsingTheSavedInterval`; `retryExhaustionUsesAnIntervalSavedBetweenAttemptsWithoutChangingRetryBackoff` |
| A schedule change during acquisition does not supersede work or replace current Documents; completion applies the latest interval | `PostgresGoogleDriveSyncTest.inFlightSyncCompletesWithTheLatestIntervalAndKeepsPublishedDocuments` |
| Adopted bytes index without Google access; changed credential authority rolls back Document/artifact replacement, and Google mappings are excluded from FILE PUBLIC access | `PostgresGoogleDriveSyncTest.indexesAdoptedBytesOfflineAndRollsBackPublicationAfterCredentialRevisionChanges` |
| Refresh-token rotation preserves publication authority; the authority lock excludes concurrent reauthorization through commit | `GoogleDriveCredentialAuthorityTest.rotatedRefreshPreservesPublicationAuthorityAndPersistsTheNewToken` and `publicationAuthorityLockExcludesConcurrentReauthorizationUntilCommit`; authorization details are in the [Connector matrix](connector.md) |
| Server input writes are reserved before PUT, adoption is Tenant/token/metadata fenced and atomic with acceptance, late writes retain tombstones, and referenced input objects survive cleanup | `ObjectWriteLifecycleIntegrationTest`; storage-specific cases are in the [Object Storage matrix](object-storage.md) |
| Identifier-scoped index and cleanup claims renew only the current token, reject stale completion, expose lease state to reclaim, and terminal-fail exhausted unexpected retries | `DefaultIngestionCoordinatorTest.renewsAndCancelsTheCleanupLeaseWhileProcessing`, `PostgresSourceLifecycleTest.staleWorkerTokenCannotCompleteAfterLeaseReclaim`, and `unexpectedProcessingFailureRetriesThenTerminatesDurably` |
| Relay messages contain only Tenant/workload/operation/delivery identifiers; stream pressure is bounded independently by workload; Redis and evidence-write failures leave durable work deferred | `RedisOperationRelayTest` |
| Redis topology is idempotent and supports identifier-only XADD → consumer-group delivery → PEL → XACK for all four workloads, including GOOGLE_DRIVE_SELECTION_VALIDATION | `RedisExecutionTopologyIntegrationTest.createsGroupsAndAcknowledgesIdentifierOnlyDelivery` |
| The real worker repairs a deleted stream from PostgreSQL rediscovery, reclaims an abandoned pending delivery, streams one file from MinIO, indexes it, deletes provider bytes and relational ownership on remove/delete, handles cleanup after Tenant deactivation, and ACKs terminal duplicates without reprocessing | `WorkerFileProcessingIntegrationTest.redisStreamsIndexRemoveAndDeleteOneRealFile` |
| PostgreSQL persists topology, bounded inactive-Tenant cancellation and workload relay/control tasks; dead ownership revives and scheduler instances cannot execute one recurring task concurrently | `ControlPlaneIntegrationTest`; `ControlPlaneConfiguration` composes source due scans, selection relay and bounded run-history maintenance |
| API/worker Spring task executors and real db-scheduler execution use virtual threads while configured workload, scheduler, datasource, and Redis bounds remain effective | `BearerAuthenticationIntegrationTest`, `WorkerApplicationSmokeTest`, and `ControlPlaneIntegrationTest` |
| Flyway V7 creates the db-scheduler control plane, V8 creates Redis dispatch/processing evidence, and V9 cuts FILE binaries over to restrictive object-storage ownership against real PostgreSQL | `SchedulerSchemaMigrationTest` |
| API source commands commit against PostgreSQL without a Redis dependency | `SourceApiIntegrationTest` |
| Redis unavailability makes worker readiness unavailable without changing PostgreSQL operation authority | `RedisUnavailableReadinessIntegrationTest` |
| Redis credentials require TLS at startup; rejected configuration does not expose credentials | `RedisTransportSecurityConfigurationTest` |
| The worker composition contains the Redis consumer and no direct PostgreSQL polling executor or poll-delay configuration | `WorkerApplicationSmokeTest`, full source/configuration review, and worker runtime integration |
| Processing outcomes remain independent of ACK, duplicate/stale claims are SKIPPED, handled failures retain durable retries, and initial wait excludes retries/clamps negative clocks | `DefaultIngestionCoordinatorTest`, `WorkerFileProcessingIntegrationTest`; `SourceSyncProcessor`, `SelectionValidationProcessor` and `IngestionMetrics` cover continuation/supersession and bounded workload labels. `RedisOperationRelayTest` passed separately after the selection-validation allowlist correction |
| Provider adapter imports only public capability APIs | `ProviderDependencyRulesTest` |

## Controlled 100k-file traversal

The separate `.tmp/mem76-corpus-capacity.json` probe exercised the real `SourceSyncProcessor`, `DefaultConnectorSyncService`, credential authorization/encryption, JDBC transactions/dispatch/leases and `RestGoogleDriveProvider` against a loopback Google OAuth/Drive HTTP fixture and Testcontainers PostgreSQL. It seeded **100,000 known unchanged files under one selected folder**, including item/version/stored-object metadata, current Documents and mappings. It did not acquire bytes, extract content or index this corpus.

| Scenario | Run ID | Measured result |
| --- | --- | --- |
| Incomplete listing, no unsafe pruning | `e3bc20a0-b284-4e10-87c5-deb55d075d53` | FAILED with `SOURCE_GOOGLE_INCOMPLETE` after 100 scanned/unchanged; all 100,000 items/Documents retained; 4,753.3453 ms, 7 batches, 6 continuations, 7 duplicate-delivery skips |
| Complete no-change traversal with recovery | `2de508f2-75b3-49c0-9b01-a97a37d271ba` | SUCCEEDED; scanned=unchanged=100,000, acquisition/indexing/removal/error counters zero, indexing NOT_REQUIRED; 2,719,584.99 ms (45m20s), 6,313 completed batches, 6,312 continuations, 6,313 duplicate-delivery skips and one abandoned-claim recovery |

The processor was reconstructed after each batch. The full run made 108,314 HTTP requests: 101,001 metadata, 1,000 listing and 6,313 OAuth token calls; media/acquisition calls were zero. Maximum returned provider page was 100 and no provider call held a SQL transaction. JDBC instrumentation counted 3,700,261 statements and 245,198 commits. These are observed costs, not a production throughput/SLO claim.

Environment: Windows/JDK 25.0.3, 768 MiB maximum heap, G1, PostgreSQL 17.11 Alpine pinned image, V1–V23 schema, `fsync=off`, `synchronous_commit=on`, 128 MiB shared buffers and 4 MiB work memory. V24 subsequently adds only Files-read indexes, not traversal behavior. The probe reused one thread-bound physical JDBC connection; lease renewal could use a separate connection. It is not a production-pool/concurrency benchmark, real Google quota/latency measurement, Redis recovery test, JVM/container restart or database crash-durability proof.

At the final resource snapshot, used heap was 88,103,080 bytes, committed heap 157,286,400 bytes and the sum of heap-pool peaks 160,408,792 bytes; the last value is not a simultaneous or retained-heap peak. Cumulative GC counters were 189 young collections/227 ms, four concurrent collections/7 ms and zero old collections. The whole accumulating fixture database was 357,979,827 bytes. Total probe time including 44,676.7787 ms seeding was 2,776,706.7692 ms. Host contention, fixture clock handling, disabled fsync and the connection model limit performance conclusions. This evidence is separate from both 100,000 history summaries and the metadata-only Files UI fixture.

## MEM-76 isolated runtime — 2026-09-08

The following runs used the normal owner Keycloak login, real PostgreSQL/Redis/MinIO and API/worker execution, with **controlled Google OAuth/Drive/Docs HTTP fixtures, not live Google**. Source `f3219171-9026-4e0f-9af5-eb2e631ce5c2` activated 21 mixed roots. API history counters were directly correlated with PostgreSQL `source_sync_attempts`; counts below are acquired / published / unchanged, not current Source totals.

| Scenario | Run ID | Observed outcome |
| --- | --- | --- |
| Initial scope revision 1 | `f244441a-0d82-40aa-8f0e-972c110073bb` | SUCCEEDED; scanned 21; 21 / 21 / 0 |
| Manual no change | `a5ad9fc2-09b2-4131-ac31-8ea6205fa3bc` | SUCCEEDED; scanned 21; 0 / 0 / 21; indexing NOT_REQUIRED |
| Manual content change | `b72f6810-7fa6-4c5a-bcf4-29eee8de603e` | SUCCEEDED; scanned 21; 1 / 1 / 20 |
| Provider outage | `e3777748-0334-446d-82f4-f0cd1c8477c1` | Retry observed, then terminal COMPLETED_WITH_ERRORS; acquisition FAILED, `SOURCE_GOOGLE_UNAVAILABLE`; scanned 20, 0 / 0 / 9, skipped 1, removed 0 |
| Provider recovery | `f524dc7f-61f2-4182-ab9e-f57f232f91a5` | SUCCEEDED; scanned 21; 1 / 1 / 20 |
| Storage acquisition failure | `f740b5ef-fce5-4848-a7ca-0478079c0fe1` | COMPLETED_WITH_ERRORS; scanned 21; 0 / 0 / 20, acquisitionFailed 1, removed 0 |
| Storage recovery | `95930008-cf0a-463d-9c37-8bfeeca686ff` | SUCCEEDED; scanned 21; 1 / 1 / 20 |
| Linked approval activated scope revision 2 | `a28a125d-ef28-4e8e-8ee0-00c34854d7b5` | SCHEDULED, SUCCEEDED; scanned 22; 22 / 22 / 0 |
| Subsequent revision-2 scheduled no change | `69021041-8bf7-46a6-90ad-cfb77ad2dcc0` | SUCCEEDED; scanned 22; 0 / 0 / 22; indexing NOT_REQUIRED; no current run |
| UI pending receipt survived reload, then revision 3 activated | `f4513a87-1726-40e6-a654-5fde64399fe3` | SCHEDULED, SUCCEEDED; scope revision 3, scanned 23; 23 / 23 / 0; no failed, removed or pending files |

The storage failure retained a file-level `STORAGE_WRITE` / `SOURCE_STORAGE_WRITE_CONNECTIVITY` error for `root-doc-032`, separately from run-wide `PROVIDER` / `SOURCE_GOOGLE_INCOMPLETE`. Later success did not rewrite these historical failures. This demonstrates safe phase attribution, not permission to expose exceptions, tokens, signed URLs or internal object paths. Raw ignored evidence is `.tmp/mem76-runtime-runs.json`, `.tmp/mem76-runtime-final-history.json` and `.tmp/mem76-storage-errors.json`. The “retry-observed” entry in the saved runs file contains the final snapshot; it is not a captured intermediate retry-state payload.

The final UI pass submitted one additional explicit root while the worker was paused. `202` operation `b5991f5d-b46c-48bc-a904-d0e351c42773` remained visible with the exact same ID after a hard reload, while active scope revision 2 remained in effect. Restarting the real worker completed verification, activated revision 3 (22 explicit roots plus one approved linked file) and produced the successful 23-file run above. The owner also changed the isolated Source interval to 1,440 minutes; no user Source was modified. This is recorded in `.tmp/mem76-final-ui.json`, with controlled Google responses rather than live Google.

### FILE regression in the same real storage/worker runtime

Source `2f8808fd-764e-4d81-8c57-1f6169b57940` uploaded a real 141-byte TXT through browser-direct storage and published one Document. Initial attempt `8f0b150b-0723-49e6-9afd-f0ee9d0cc493` and reindex `5def621b-795b-45a0-9c1f-1cb24e33e87a` both SUCCEEDED; PostgreSQL kept `source_sync_attempt_id` null for both. The UI showed both separately in item-processing history, not Google synchronization history. `.tmp/mem76-file-runtime.json` retains the final INDEXED item and rendered history evidence.

The later [100k-item Files UI acceptance](connector.md#real-ui-files-pagination-over-100k-items) exercised a separate real FILE Source, bounded server pages and upload/reindex/removal across page changes. Its synthetic metadata rows are explicitly separated from the two actual uploaded files and actual worker operations.

Actual desktop/mobile/light/dark inspection and frontend gate details are in the [Connector matrix](connector.md#frontend-and-browser-acceptance--2026-09-08). These are headless-browser observations, not Orca embedded-browser acceptance. API/worker smoke services were stopped before the final uncached backend gate to release Windows JAR locks. No real user Sources, live Google content, shared runtime or DNS/hosts were changed. Older live Google runs remain separately bounded historical evidence in the increment; broader Google/Tasco/ACL acceptance and the increment stay active.

## Docling environment configuration — 2026-09-09

- Connector suite: 53 tests, zero failures/errors/skips, including timeout rejection above fifteen minutes, nonpositive budgets, real Docling OCR/page provenance, Vietnamese DOCX tables and PPTX extraction.
- `docker compose config --format json` passed with both staging and production overlays. Default and external-env-file cases checked all twelve Docling service envs plus the Worker timeout; coordinated overrides resolve to `15m`, `900`, and `910`.
- A throwaway Java launcher used real Spring environment binding and the production `DoclingSourceContentExtractor`: `MEMORYOS_EXTRACTION_DOCLING_TIMEOUT=15m` bound to 900 seconds and extracted OCR text plus page provenance from a generated one-page scanned PDF against an isolated digest-pinned Docling container configured for 900/910 seconds. The same Worker budget against the unchanged 300-second service was rejected with HTTP 422.
- The isolated parser used two CPUs and 4 GiB, not a throughput benchmark. No HUT reindex or proof of a fifteen-minute HUT completion is claimed. Existing Worker/API/Docling deployment settings were not changed.
- Compiler checks substitute for unavailable JetBrains MCP/Java LSP inspection. The [increment ledger](../increments/active/docling-timeout-configuration/plan.md) records commands, the temporary-build-path correction, remaining gate evidence and cleanup.

## Authenticated remote Docling — partial runtime verification

The authorized cutover uses `MEMORYOS_EXTRACTION_DOCLING_ENDPOINT`, optional `MEMORYOS_EXTRACTION_DOCLING_API_KEY`, and `MEMORYOS_EXTRACTION_DOCLING_ENGINE_REVISION` in Infisical **shared dev**, not staging. Shared values were read back and the newly created personal overrides removed at the user's request; developers using this baseline need the trusted VPN. No private endpoint/key is recorded here.

| Required observable contract | Evidence status |
| --- | --- |
| Authenticated conversion uses SDK `X-Api-Key`; empty/unset retains unauthenticated local behavior; credentials stay out of property rendering and parser metadata; redirects are refused | `BoundedDoclingClientTest` and `DoclingPropertiesTest` passed, including real loopback authentication, missing credentials, redirect refusal, bounded responses and credential-safe rendering/validation |
| Compose retains the configurable local endpoint default without a secret/blank override; selective remote Worker startup does not require local Docling, while full Compose can still start it | `docker compose config --quiet` passed with both staging and production overlays; no server deployment was performed |
| Authorized remote conversion retains actual OCR text and page provenance | A disposable launcher using the updated production Java extractor converted a generated 10,063-byte image-only PDF in 9.56 seconds; English and Vietnamese text and page provenance passed with Tesseract `vie,eng`, forced OCR and a 15-minute Worker budget |
| Retained Worker restarts with the refreshed shared dev configuration | Rebuilt Worker launched with the managed remote endpoint/key/revision and retained Tesseract `vie,eng`/15-minute settings; readiness returned HTTP 200 with `UP` |
| Remote Worker conversion publishes a current Document and extraction artifact through the real storage/queue flow | `WorkerFileProcessingIntegrationTest` passed its DOCX path with authenticated remote Docling and isolated real PostgreSQL, Redis and MinIO; this is fixture publication, not a new retained-corpus acceptance run |

Final local gate: `gradlew.bat clean check :worker:bootJar --no-configuration-cache --no-daemon --no-parallel --max-workers=1 --console=plain`, JDK 25.0.3, `CI=true`, `ARCONIA_DEV_SERVICES_ENABLED=false`, `MEMORYOS_OPENAPI_WRITE=false`, both `DOCLING_TEST_ENDPOINT` and `DOCLING_TEST_API_KEY` **unset**. Passed in 21 seconds: **509 tests, 505 passed, zero failures, four skipped** (API 102/one skipped, connector 66/three skipped, core 315, worker 26). All four final test tasks were restored from cache; 26 tasks comprised 13 executed, 12 restored and one up-to-date. The preceding runs executed connector authentication tests and all Worker tests successfully. Worker FILE integration used its TXT path with real PostgreSQL, Redis and MinIO; the three real Docling tests and one optional Chat test were skipped. An empty endpoint variable is not equivalent to an unset variable for the existing Worker fixture and must not be used to select its TXT path.

Remote ingress initially returned health/version 200, unauthenticated conversion 401 and authenticated invalid payload 422. The observed server versions were Docling Serve 1.32.0 and Docling 2.124.0; the configured engine revision records those versions, not an unverified image digest. After the workstation interruption, the endpoint timed out and the remote-enabled gate failed all three Docling conversions; Worker was kept stopped while the VPN route was unavailable. Once connectivity returned, health was 200 and authenticated invalid payload was 422 again.

Post-recovery command: `gradlew.bat :connector:test --tests io.memoryos.provider.file.DoclingServeIntegrationTest :worker:test --tests io.memoryos.worker.WorkerFileProcessingIntegrationTest --no-configuration-cache --no-daemon --no-parallel --max-workers=1 --console=plain`, with the real endpoint and key supplied through `DOCLING_TEST_ENDPOINT` and `DOCLING_TEST_API_KEY`. Passed in 1m6s; both test tasks executed, **four tests passed with zero failures/errors/skips**. Connector covered scanned PDF OCR/page provenance, Vietnamese DOCX table values and PPTX text; Worker covered real DOCX extraction, publication, redelivery and cleanup in its isolated storage/queue fixture. The retained Worker was then started; Worker/API readiness returned 200 with `UP`, and the frontend returned 200. Local Docling remained stopped.

These results do not establish remote whole-corpus indexing, financial-table fidelity or sustained-load capacity. JetBrains MCP/Java LSP was unavailable; compiler/project gates are the fallback, not an IDE-clean claim.

## Tasco scanned-PDF corpus — 2026-09-10

Final scope is OCR, extraction and indexing only; Search/Chat changes, integration and further acceptance testing are excluded. The real Orca owner session uploaded all six original PDFs through the normal FILE path into an isolated database/queue/storage/index namespace. No Google fixture or parser stub supplied these results. Originals remained unchanged.

All six current artifacts use the optional image `sha256:a7d3753fa80f66b8b79dfe451350f2d4a437f36c20a2836d10afb7cd3cb24055`: digest-pinned Docling Serve CPU 1.32.0 plus `tesseract-langpack-vie-4.1.0-3.el9`, explicit `tesseract` / `vie,eng`, full-page OCR and accurate tables. Tesseract alone is limited to one OpenMP thread; model execution retains four threads. The local service was bounded to four CPUs, 5 GiB and one conversion at a time, with coordinated 900/910-second processing/wait budgets and 20 MiB input admission.

| Report | Pages | Blocks | Detected tables | Cell records | Current chunks | Docling seconds |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Q1 2026 separate, Vietnamese | 43 | 879 | 33 | 2,472 | 1,418 | 802.98 |
| Q1 2026 consolidated, Vietnamese | 40 | 901 | 37 | 2,762 | 1,520 | 446.80 |
| Q2 2026 separate, Vietnamese | 44 | 883 | 33 | 2,718 | 1,465 | 630.02 |
| Q2 2026 consolidated, Vietnamese | 45 | 1,057 | 34 | 2,841 | 1,675 | 579.38 |
| Q2 2026 separate, English | 44 | 817 | 35 | 2,600 | 1,370 | 420.16 |
| Q2 2026 consolidated, English | 44 | 733 | 37 | 2,795 | 1,331 | 635.56 |
| **Total** | **260** | **5,270** | **209** | **16,188** | **8,779** | **3,514.90** |

These are parser-output counts, not ground-truth financial-cell counts. Every artifact passed byte-count/SHA-256 verification, matched its original PDF's source SHA-256, used the canonical v1 schema and contained the exact original page-number set. Body provenance covered every page; semantic text/table fields contained no binary image data URIs. All current content/chunk/index-readiness generations matched without indexing errors. The six artifacts total 19,799,030 bytes. Docling times exclude queueing, artifact publication and downstream indexing; host conditions were not controlled for a throughput/SLO claim.

Actual Orca Sources visibly showed **6 Sources, Active 6/6 and 6 indexed Documents**, one per Source. The English consolidated trial report was explicitly reindexed through the same UI onto the final image; seven successful operations represent six current Documents, not seven input files. Ignored evidence includes `.tmp/tasco-browser-evidence/artifact-verification.json`, `orca-final-sources.json` and the visually inspected `orca-final-sources.png`.

**Financial fidelity is not accepted.** A visually checked Q1 consolidated source page (PDF page 8, printed page 6) shows a current-period total liabilities-and-equity value that is absent from the canonical table, while the prior-period value is assigned to the current-period column. Reporting period, consolidated scope and VND units remain identifiable. The separate 70-cell pilot achieved only 63 digit-exact cells; this is neither corpus-wide accuracy nor financial-audit acceptance. Exact figures/source images stay in ignored private evidence. No source-specific digit substitution or column swap was added.

Repository checks passed separately from the live corpus: backend `clean check` reported 499 passed and four optional-service cases skipped, with API/connector/core results restored from Gradle cache and Worker tests executed; frontend `check` passed 93 tests plus generation/lint/format/types/build. Both deployment Compose overlays rendered successfully without deployment. The [increment ledger](../increments/active/tasco-scanned-pdf-ocr/plan.md) retains exact commands, earlier failed gates, image smoke checks, resource limits and unavailable IDE-inspection boundaries.

The subsequent [shared FILE/Drive binary admission verification](connector.md#shared-file-and-drive-binary-admission--2026-09-10) proves complete 20 MiB Google acquisition, explicit rejection above that ceiling and successful live acquisition of the five previously blocked Tasco binaries. It does not supersede the OCR/fidelity caveats above: those five Drive indexing attempts were pending at the recorded boundary, and the earlier Drive extraction had failed. The later runtime interruption is documented separately in the increment ledger.

## Thirty-minute Tasco trial admission — 2026-09-11

The application now admits a positive Docling budget up to thirty minutes while retaining the five-minute default. Backend `clean check :worker:bootJar` passed in 3m40s; API, connector and Worker tests executed, while core test results came from cache. A disposable launcher against compiled production classes accepted exactly thirty minutes and rejected an additional nanosecond. The optional real Docling tests were not enabled in this gate. Compiler/project gates substituted for unavailable JetBrains MCP and Java LSP; existing dependency/unchecked-operation and fixture-shutdown warnings remain.

The initial authenticated one-page request returned HTTP 422 because the running remote maximum was 900 seconds. After the operator reload, the same 1800-second admission check succeeded with HTTP 200 and no errors in 8.116 seconds at 04:41:34 UTC. Shared dev settings were `30m` for Worker, 1800 seconds for the document maximum and 1810 seconds for synchronous wait; the client deadline remained 1815 seconds. Actual proxy configuration and remote resource/queue measurements were unavailable; the management endpoint returned HTTP 403.

The authorized four-report reindex finished at 14:10:16 ICT. These are elapsed Worker attempts, including transport and possible remote waiting, not isolated OCR CPU timings:

| Report | Pages | Controlled attempt seconds | Final outcome |
| --- | ---: | --- | --- |
| Vietnamese separate | 49 | 1816.33 | `SOURCE_EXTRACTION_INTERNAL`; no artifact |
| Vietnamese consolidated | 70 | 1815.76; 1815.91 | First `INTERNAL`, then terminal `SOURCE_EXTRACTION_TIMEOUT`; no artifact |
| English consolidated | 71 | 149.77; 8.92; 19.76 | Three `SOURCE_EXTRACTION_INTERNAL` failures; no artifact |
| English separate | 50 | 859.38 | Published 1,892 current chunks |

The corrected Worker used Tesseract `vie,eng`, explicit full-page OCR, accurate tables and embedded images. An earlier roughly three-minute Worker start omitted the force-OCR flag and was stopped; it is excluded above. Cancellation of its remote request was not established, so these are not controlled capacity benchmarks. Database attempt counters include earlier and interrupted work; timings above use the corrected Worker's start/end events, not persisted first-start timestamps.

The English separate publication records `timeoutSeconds=1800` and `force=true`. Its source checksum matches the preserved original; its 3,788,152-byte artifact passes its stored checksum and is byte-identical to the fifteen-minute baseline, with 50 pages, 1,165 blocks and 44 tables. Against the previously sealed original-page sample, 24 of 26 financial cells match exactly. Both closing-cash values at code 70 on physical PDF page 13 remain absent from the canonical table and are not recovered in that page's text blocks. The other three reports have no artifacts and cannot receive financial-accuracy scores. This is one successful report out of four, not whole-corpus financial acceptance.

Orca confirmed one `INDEXED` and three `FAILED` rows. A Source-scoped database query found no unfinished attempts. Aggregate Source status initially displayed `Indexing`, then returned to `Active` in the saved screenshot without intervention; this observation does not establish a persistent status defect. Increasing the deadline did not improve the retained artifact or establish stability. Further diagnosis needs safe HTTP-status/exception-cause evidence for the generic failures and server-side resource/queue observations before choosing another timeout or capacity change. The [active trial ledger](../increments/active/tasco-scanned-pdf-ocr/plan.md#authorized-thirty-minute-trial--2026-09-11) records runtime boundaries and private evidence.

## Tasco failure root causes — 2026-09-11

The user authorized diagnosis and reporting, not another Source reindex, production fix or deployment. Direct authenticated conversions used the preserved originals and retained OCR/table settings. An offline, network-disabled reproduction traced the financial-cell loss. The final read-only Source query still reports one successful and three failed current attempts, with zero unfinished attempts; diagnostic conversion success does not publish a Document.

### File admission and hidden HTTP failures

- The 21,343,544-byte English consolidated original exceeds the live parser's 20,971,520-byte limit. With `abort_on_error=false`, both bounded and complete-document requests returned HTTP 200 with document `status=failure` and a `user_input` / `policy` error identifying those exact sizes. The full request completed in 8.216 seconds with 0.0014 seconds of parser processing; its 28,458,459-byte JSON request passed ingress. This establishes parser admission rejection for this report, not Nginx rejection and not a slow 71-page OCR run.
- Nonsecret shared-dev readback gives `DOCLING_SERVE_MAX_FILE_SIZE=10485760`, while MemoryOS admits 104,857,600 bytes and the live parser reports 20,971,520. These three limits are inconsistent. The deployment source of the live override is not established; no configuration was changed. Earlier larger HTTP 413 probes remain separate evidence, and end-to-end 100 MiB admission is still unverified.
- A throwaway Java launcher invoked the actual extractor and bounded SDK client from the retained Worker JAR. Production sends `abort_on_error=true`; the SDK received HTTP 404 with `Task result not found. Please wait for a completion status.` The extractor then threw a cause-less `IllegalStateException`, losing the status and cause used for diagnosis. The coordinator classifies this as `SOURCE_EXTRACTION_INTERNAL` and retries it. This live probe waited 614.936 seconds while the Vietnamese conversion was running; that elapsed time is not the file-policy check's cost.
- An independent asynchronous request using the same oversized original and `abort_on_error=true` failed in 5.660 seconds: task failure with sanitized `Internal processing error.`, followed by the same HTTP 404 on result retrieval. Together with the structured non-aborting response, this reproduces the admission → aborted task/no result → 404 → generic retry path. The deployed SDK also parsed the saved structured failure successfully; an enum/deserialization mismatch is not the cause.

### Document budget versus synchronous waiting

| Direct diagnostic | Actual document result | Measured boundary |
| --- | --- | --- |
| Full Vietnamese separate original, 49 pages | `success`, no errors, 49 page maps and 48 tables | 1,290.449 seconds parser processing; 1,308.930 seconds observer elapsed |
| Full Vietnamese consolidated original, 70 pages, production `abort_on_error=true` | `partial_success`; 53/70 pages processed successfully, 17 failed or incomplete | 1,816.885 seconds parser processing against an 1,800-second document budget; 1,841.925 seconds observer elapsed |

The consolidated result contains seventeen page-level `document timeout exceeded` errors and one pipeline timeout summary. Its asynchronous task status is `success`, but document status is `partial_success`; all 70 page-map keys are present despite the incomplete pages. Neither task completion nor page-map cardinality proves complete extraction. The existing MemoryOS strict-success guard must continue rejecting this result.

The separate report's successful diagnostic proves that this original can complete within thirty minutes under the observed conditions. It does not reconstruct the exact cause of its previous 1,816.33-second failure. Queueing and residual work remain plausible contributors, not retrospectively proven per-request causes.

Authenticated `/metrics` was available even though management access returned 403. Aggregate conversion metrics recorded three HTTP 504 responses with 5,433.734 seconds total duration, approximately 1,811.245 seconds each. These metrics have no per-request correlation and cannot independently assign each historical response to a report. During the consolidated diagnostic, the same serving process accumulated 1,303.26 CPU seconds over 326 seconds, approximately four CPU cores on average; sampled RSS was about 4.24 and 3.71 GiB. These are process observations, not container limits, peak memory, whole-host pressure or proof of remote OOM.

The pinned [Docling Serve 1.32.0 synchronous implementation](https://github.com/docling-project/docling-serve/blob/v1.32.0/docling_serve/app.py) waits after enqueueing, returns HTTP 504 when `max_sync_wait` expires, and does not cancel the task on that branch. It returns 404 when a completed task has no result. Shared settings select 1,800 seconds for document processing and 1,810 seconds for synchronous waiting, while the Java request deadline is 1,815 seconds. Queueing, request transfer and pipeline completion overhead therefore do not receive the same budget. HTTP 504 is currently collapsed to generic `INTERNAL`; only a directly wrapped JDK timeout receives the typed `TIMEOUT` classification. Blind retries can leave earlier remote work running.

### Missing English financial cells

Physical PDF page 13, printed page 9, loses both numeric cells at cash-flow code 70 before table reconstruction. A fresh remote page-only conversion succeeded in 28.258 seconds. Its 45 sparse table-cell records, including geometry, exactly matched the retained canonical table and the offline local Docling reproduction. The table bounding box includes the row; canonical row 10, columns 4 and 5 remain empty.

Tracing the actual threaded Docling raster, raw Tesseract output and TableFormer input tokens located the loss in Tesseract 4.1.1 PSM 3: the two values are visible in the bitmap but absent from OCR tokens before TableFormer sees them. Running Tesseract directly on that captured bitmap reproduces the omission. This is not MemoryOS text normalization, a table crop excluding the row, or numeric tokens dropped during table assembly.

Same-bitmap full-page PSM 6 and a margin-preserving row crop with PSM 6 recover both values. However, full-page PSM 6 changes the note marker, while a tight PSM 7 crop misreads one digit. Alternate PDF render paths also change whether PSM 3 finds the values. This establishes raster/segmentation sensitivity, not the exact internal Tesseract heuristic and not a safe global PSM change. Financial fidelity remains unaccepted; no Tasco-specific values, substitutions or production OCR changes were added.

### Correction priorities and retained evidence

1. Align managed, live parser and ingress admission with the application contract, accounting for base64 request expansion; verify the running service rather than configuration files alone.
2. Preserve safe HTTP status and structured failure classification without credentials or document bodies in routine logs. Evaluate non-aborting structured responses while retaining strict rejection of failed/partial documents; do not retry permanent size-policy rejection.
3. Address the measured 70-page processing-budget failure and remote task lifetime/queueing before another whole-Source replay. An asynchronous diagnostic is evidence, not an implemented production task-management solution.
4. Evaluate generic, geometry-based OCR recovery for suspicious numeric rows with adequate crop margins and provenance, against the unchanged corpus. Do not globally switch PSM, hardcode financial values or infer missing values from other rows.

Private evidence is under `.tmp/tasco-2025/diagnostics/`: `effective-limits.json`, `en-consolidated-full-request-response.txt`, `en-production-sdk-error.txt`, `en-production-exception-chain.json`, `en-consolidated-async-abort-*`, `vi-separate-async-*`, `vi-consolidated-async-*`, `vi-consolidated-outcome.json`, `http-duration-metrics.json`, `metrics-snapshot-*.json`, `financial-cells-report.json`, `en-separate-p13-local-stage-trace.json`, the captured rasters/TSV variants, and `source-state-after-diagnostics.json`. Exact financial figures and full parser output remain ignored. The live Java probe compiled and executed, both full Vietnamese diagnostics reached terminal results, and the offline same-bitmap experiment ran; no production code changed and no new repository test-suite result is claimed.

## Tasco corrections — 2026-09-11

The user approved admission alignment, typed failures, bounded task observation and geometry-based OCR evaluation. These changes do not authorize staging deployment or unrelated Source mutations.

### Implemented extraction behavior

The compiled production adapter now submits once asynchronously with `abort_on_error=false`, polls the same validated task identifier, and retrieves a bounded JSON result. It does not use the SDK's unbounded stream-result path or background async observer. Transient status-read failures can retry the same read; submission and potentially single-use result retrieval cannot. Task success, partial document success, missing content and nonempty error arrays cannot bypass publication validation.

Structured policy errors/HTTP 413 produce terminal `WRITE_LIMIT`; structured timeout/HTTP 408 or 504, interruption and observer expiry produce terminal `TIMEOUT`; other known external failures are terminal rather than automatic resubmission. Runtime logs retain safe status and exception-class diagnostics without parser bodies or raw exception messages. The five-minute default remains; an explicit document budget up to sixty minutes is accepted. HTTP exchanges are independently bounded to two minutes, with a document-budget-plus-five-minute observer. Task state is not persisted across Worker restart, and remote cancellation/exactly-once execution are not claimed.

Focused regression tests passed for one submission across a transient polling outage, complete document publication, task-success/document-failure distinction, structured policy/timeout/unknown errors, HTTP 413/504/503, interruption, credential safety, response limits and existing canonical-content validation.

An assertion-based disposable launcher exercised the actual compiled Worker extractor:

| Scenario | Observed result |
| --- | --- |
| Exact sixty-minute configuration | Accepted by production `DoclingProperties`; over-limit/zero/negative cases remain rejected by regression tests |
| Controlled pending task, three-second observer budget injected only into the disposable fixture | Terminal `TIMEOUT`, one submission, 3.205 seconds total including local PDF admission; no publication |
| Authenticated live service, valid one-page 20 MiB + 1 byte PDF, 30-minute document request | Terminal `WRITE_LIMIT` with HTTP 200 retained in safe diagnostics and no exception cause exposed; 16.557 seconds |
| Authenticated live service, one-page OCR smoke PDF, 30-minute document request | Complete extraction containing the original numeric marker; 6.319 seconds |

These are real extractor/task-boundary checks, not a new four-report indexing run or sixty-minute corpus completion. Routine project gates leave optional live endpoints unset; the separate live probes above provide remote extraction evidence.

### Managed configuration versus running service

Shared Infisical dev root was updated and read back with file admission `104857600`, document maximum `3600`, synchronous wait `3610`, and Worker `60m`. Endpoint, API key, unrelated shared values and personal overrides were preserved; staging was untouched.

The actual remote service still rejects a 3600-second one-page request with HTTP 422 and reports a maximum of 1800 seconds. A bounded exact-100-MiB file request still receives Nginx HTTP 413; the 20-MiB-plus-one request reaches Docling and reports the live 20-MiB policy. Shared configuration therefore is not a completed rollout. No configured SSH host/control plane targets this parser; available Docker contexts are local, and service API routes do not mutate deployment settings.

The missing prerequisite is authorized access to the actual parser and ingress deployment, or an operator performing the [runbook rollout](../runbooks/docling-extraction.md). Do not restart Worker with `60m` or replay the Source before effective limits and readiness pass. No such restart, reindex or remote deployment was performed in this correction.

### Geometry-based OCR evaluation

The candidate ran offline against the pinned existing image and twelve sealed source pages across all four reports: 239 geometry-derived rows and 478 Tesseract calls. It uses two padded row crops, OCR confidence and agreement, and a blank-only overlay; no expected-value access or document-specific rules occur in candidate recognition.

- All 104 sealed financial cells were evaluated: 97 baseline matches became 99, with two recoveries and zero regressions. Three pre-existing nonempty OCR errors and two merged-row ambiguities remain unchanged.
- Eleven natural blank candidates yielded two correct fills and nine abstentions. Twenty-five visually audited no-amount controls—six empty headings and nineteen printed dashes—received no numeric proposals.
- Shadow recognition yielded 85 exact values, zero incorrect values and nineteen abstentions. It uses existing geometry and is not proof of missing-cell detection safety.
- Existing nonempty text, bounding boxes and sparse cell records remained unchanged. Original truth remained sealed, and the known omission's raster/table input matched the prior trace.
- Capture worker wall time was 362.90 seconds; row OCR took 151.27 seconds. The natural-blank subset used twelve OCR calls in 4.64 seconds. Containers were limited to two CPUs/3 GiB with no network and all exited; process-tree peak memory was not measured.

**Decision: keep the candidate offline.** Two natural positive omissions do not establish generic production safety or whole-corpus fidelity. No image change, global PSM 6, existing-number replacement or fabricated financial value was shipped.

Sanitized runtime/admission evidence is under `.tmp/tasco-2025/corrections/`; reproducible offline results and provenance are in `.tmp/tasco-2025/diagnostics/recovery/final-report.json`, `evaluation-report.json`, `visual-audit.json` and `shadow-visual-audit.json`. Exact financial values and raw parser output remain ignored.

### Repository verification conditions

The first full correction gate passed API, connector and core tests, then failed because the Worker test JVM could not allocate 1,957,720 bytes of native memory in its compiler thread. The crash report records only 70 MiB free physical RAM and 36 MiB available page-file/commit capacity on the Windows host. This is a failed gate and host native-memory exhaustion, not a test assertion failure or evidence of remote Docling OOM. Only this worktree's retained development API and Worker were paused for the subsequent gate; their private JARs and retained 15-minute API/30-minute Worker launch environments were preserved. Other databases, services and worktrees were not stopped.

The next attempt exposed a verification-environment mistake: setting `DOCLING_TEST_ENDPOINT` to an empty string activated the integration test's non-null live-mode selector. Removing both optional Docling test variables, rather than setting empty values, made the unchanged `WorkerFileProcessingIntegrationTest` pass. No assertion, timeout or product code was weakened to resolve either verification failure.

The final `clean check :worker:bootJar` gate passed in 2 minutes 7 seconds on Microsoft JDK 25.0.3, with one Gradle worker, no parallelism/configuration cache and local self-attach/dynamic-agent flags for Mockito. The final result includes cached successful API, connector and core tests; all Worker tests executed in the final gate:

| Module | Tests | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: | ---: |
| API | 102 | 101 | 1 | 0 |
| Connector | 74 | 71 | 3 | 0 |
| Core | 317 | 317 | 0 | 0 |
| Worker | 26 | 26 | 0 | 0 |
| Total | 519 | 515 | 4 | 0 |

The skips are three optional `DoclingServeIntegrationTest` cases and one `ChatSessionApiIntegrationTest` case. Separate final-artifact live OCR/policy and controlled observer checks passed as recorded above. Javac reports the SDK-inherited generic `toBuilder()` unchecked-conversion warning; JDK/library native-access, class-sharing and deprecated-Unsafe warnings remain. No JetBrains MCP/Java language server was available, so compilation and runtime verification are not an IDE-inspection claim.

The retained API and Worker were restored from their original private JARs and launch environments. Both readiness endpoints returned HTTP 200 with `UP` on ports 18080 and 18081. The corrected artifact was exercised through the disposable launcher, not installed into those retained runtimes; no `60m` Worker rollout or Source replay occurred. Final sanitized evidence is in `runtime-smoke-final.json` and `final-gate-summary.json` under `.tmp/tasco-2025/corrections/`.

After verification, the disposable launcher/classes, synthetic smoke PDFs, temporary compiler-init script and plaintext managed-configuration snapshots were removed. Sanitized results and the reproducible offline OCR experiment remain; native JVM crash diagnostics were moved into the ignored correction evidence directory.

## Sixty-minute Tasco corpus — 2026-09-11

After effective remote admission recovered, the retained Worker ran the corrected asynchronous adapter with an explicit sixty-minute document budget, Tesseract `vie,eng` and full-page OCR. All four real Google Drive originals completed through the existing authorized ingestion path. This supersedes the earlier blocked-runtime state above, not its historical observations or the still-unverified full 100 MiB remote admission boundary.

| Report | Pages | Current chunks | Strict sampled cells | Current Worker attempt seconds |
| --- | ---: | ---: | ---: | ---: |
| Vietnamese separate | 49 | 1,862 | 20/26 | 1,281.682 |
| Vietnamese consolidated | 70 | 2,676 | 24/26 | 2,357.888 |
| English separate | 50 | 1,892 | 24/26 | 819.810 |
| English consolidated | 71 | 2,477 | 25/26 | 2,140.318 |
| Total | 240 | 8,907 | 93/104 | — |

Original SHA-256 values, artifact byte counts/checksums and complete page maps were checked. The 104-cell truth set was sealed before extraction inspection. Scoring accepts grouped numeric strings with an optional leading minus or enclosing parentheses; invalid characters, absent values and merged/ambiguous rows fail rather than being repaired. The eleven failures comprise three numeric corruptions, four table-rule contaminations and four missing/merged cells. These samples are not a whole-report accuracy estimate.

Attempt timings use the current Worker process's start/completion logs. The Vietnamese consolidated attempt recovered an expired earlier lease, so its retained database start includes interruption time and is not the current processing duration. The current process recorded four starts, four completions and no error lines. Queue wait and OCR CPU time were not separately measured; these are end-to-end Worker attempt times, not controlled throughput benchmarks.

Actual Orca Sources verification showed an Active Source, four indexed Documents and four completed file states. Reindex returned HTTP 202 and reused the existing nonterminal attempt without a duplicate. Long English filenames visibly overlap the Size column; that UI defect remains open. Financial correctness remains unaccepted despite successful indexing. Search/Chat and Google authorization behavior were not changed or accepted by this Sources check.

The installed private Worker JAR SHA-256 is `c6d0bcac98b4398d01bd2b1d3f4a58bc9c50d50fdf25d4e01cbce7898ef2e881`; retained evidence is under ignored `.tmp/tasco-2025/sixty-minute-reindex/`, including `final-verification.json`, `corpus-assessment.json`, current-process logs and Sources screenshots. The largest completed original is 21,343,544 bytes; completion proves admission of that input, not 100 MiB. No production OCR recovery algorithm was promoted.

## Tasco quality follow-up — 2026-09-11

### Frozen comparison and diagnostic smoke

The preceding four-report result is frozen independently of subsequent parser runs. The unchanged 104-cell truth has SHA-256 `94b3f546c7a2ce7b16a32ad1e89a733e4b3a32df8ddd4a1a2ae78b9683ae4964`; the exact copied scorer has SHA-256 `2a354e23116800b064f0fa70f50bf3f1ab37586046d134604dd6db5109f907da`. It requires a unique coded row on the specified physical page, compares the last two period columns, removes whitespace and numeric grouping, and accepts only ASCII minus or enclosing parentheses for negatives. It does not repair malformed amounts or ambiguous rows.

| Report | Original SHA-256 | Baseline canonical artifact SHA-256 |
| --- | --- | --- |
| Vietnamese separate | `60c9b52774d2394a401961598d7c76275501ca902b4c6807d04d228a821848e5` | `25c4299265bdc7aea000ba2df079e15ced53f912dc897794a937a7acbd6a80e7` |
| Vietnamese consolidated | `997eb989b1bf2dfa0c45175434352121566ec4b1f0e6f4d7f8d447a5513856cd` | `d6e533e008522a3bcddf01d54e97ed4be66601115145aa2d10268ca3fb141da9` |
| English consolidated | `94cbcca723a4463aa04c763bca1782b07d5a370803cf02f98d36118330455ff1` | `aec4eea64e3e36ffbc169e2374c7103953dbe9d047eba07dd8e0d137ce0cab40` |
| English separate | `bbc1b0410af55209468f01f94e6e9178bc35ebd426d3cdeb43955a417609c589` | `7305fef20d6029598202cf044036c9c52a6649ea1e2a7902527b80323cd8a9f6` |

All four persisted baseline parser fingerprints select `docling-java=0.6.5`, `docling-serve=1.32.0`, `docling=2.124.0`, Tesseract `vie,eng`, forced OCR, accurate tables, embedded images, 200 pages, 3,600 seconds, 104,857,600 input bytes and 33,554,432 output bytes. The new diagnostic/renderer fingerprint suffix is separate; the baseline artifacts were not rewritten.

An actual Java invocation of the new [financial diagnostics](../specs/document.md) assessed all four sealed canonical artifacts, checked their hashes and verified deep equality of every original block afterwards. Vietnamese separate remained unassessed because the damaged statement labels are unsupported. Vietnamese consolidated reported a current-period mismatch and invalid prior-period code 70; English consolidated reported two scoped arithmetic matches; English separate reported missing codes 61/70 for both periods. The helper took 6.4–33.6 ms per artifact in this invocation, not an ingestion throughput measurement. An authenticated live DOCX integration case also executed successfully with the changed extractor/client; that test's XML does not capture lifecycle logs.

### Bounded recovery evaluation, not production rollout

The next offline candidate used only original PDFs and frozen canonical geometry—no truth amounts during recognition. It requires two explicit period headers, isolated cells and reliable row anchors; whole-token containment and two padded PSM-6 crops must agree. Both-missing rows need an unambiguous row code. Only missing text receives an experimental grid/sparse-cell overlay; existing values are immutable. The global OCR engine, full-page segmentation and input raster policy were not changed.

The network-disabled run examined 24 pages: eight eligible sealed pages and sixteen held-out pages, four per report. Four sealed balance-sheet pages abstained under the structural guards. Across 261 rows, 26 missing-text candidates yielded three visually confirmed fills: both English separate closing-cash amounts and one held-out Vietnamese consolidated negative amount. The unchanged 104-cell scorer moved from **93 to 95**, with no sampled regression; this is an offline cell overlay, not published extraction or whole-report accuracy. Twenty-three other missing-text cells and twenty explicit dash controls received no number. Original-pixel contact sheets confirm genuine blanks/dashes were preserved. Twelve nonempty shadow string disagreements were reviewed, including two already numerically equal under the frozen scorer; none was applied.

The run took 200.143 seconds overall, including 177.822 seconds in row recognition, with a measured cgroup memory peak of 404,688,896 bytes. Limits were two CPUs, 1 GiB memory, a 900-second process deadline, 150 attempted rows per document and 48 per page; no errors or truncation occurred. Original/artifact hashes and the frozen truth/scorer hashes remained unchanged. Structural comparison found exactly three inserted grid values and three corresponding sparse cells, with no other overlay changes.

**Decision: retain the candidate offline.** Three natural positive omissions, only one on held-out pages from the same issuer, do not establish a generic production safety guarantee. No existing-number replacement, arithmetic repair or unused runtime recovery mode is shipped. Candidate SHA-256 is `9dbef331d7f1cab21786a25369bdf2b81c2e6c4b141c00c5c340fbab7e983892`; the retained row recognizer is `ab4274fb9aaac79ca38f671473798bae66faf3a5e2ded13cf3c96d3659fea89f`.

Ignored evidence: `.tmp/tasco-2025/quality-followup/baseline.json`, `baseline-scorer.mjs`, `financial-smoke.json`, and `recovery-v2/{predictions,scored,overlay-invariants,final-report}.json`, with source rasters and the nine audit sheets. Exact values/raw artifacts remain private evidence rather than production fixtures.

### Console correlation regression — 2026-09-12

The [lifecycle logging contract](../guidelines/observability.md#extraction-lifecycle-diagnostics) separates invocation-relative elapsed offsets from durable queue waits and observed parser states. During the recreated corpus run, the existing local console configuration discarded fluent key/value fields, leaving message text without event, stage, task or elapsed values.

A disposable Java program using the actual packaged Worker dependencies reproduced that loss with the packaged configuration. Loading the changed shared resources retained `event`, `stage`, `elapsed_ms` and `task_id` through `%kvp`, plus operation, delivery and workload MDC fields in the correlation pattern. Both old and changed staging-profile configurations produced JSON with the same fields and numeric elapsed type; `OTEL_SDK_DISABLED=true` prevented telemetry export in that serialization check.

These are synthetic console-configuration checks, not ingestion timings, OTLP delivery or a staging deployment. Ignored evidence is `.tmp/tasco-2025/quality-followup/console-configuration-smoke.json` and `structured-console-configuration-smoke.json`.

### Recreated six-report Drive corpus — 2026-09-12

The exact preserved Specific configuration was recreated as Source `b5237dd0-059c-4753-aeaf-2cb397c8cab4` after authorized deletion and verified cleanup of the old Source. This Q1/Q2 2026 corpus is separate from the four-report Tasco 2025 financial baseline above.

All six attempts succeeded on their first processing attempt. Original hashes and byte counts matched the preserved inputs; canonical artifact sizes and SHA-256 checksums matched storage records. All Documents were `ELIGIBLE`, with matching current content/chunk/searchable generations, no search error, and chunk plus semantic-body provenance covering every original page.

| Report | Pages | Chunks | Queue wait (ms) | Whole attempt (ms) |
|---|---:|---:|---:|---:|
| Q1 consolidated | 40 | 1,523 | 67,470 | 783,601 |
| Q1 separate | 43 | 1,415 | 2,755,841 | 1,012,294 |
| Q2 Vietnamese consolidated | 45 | 1,747 | 3,750,864 | 1,123,685 |
| Q2 Vietnamese separate | 44 | 1,501 | 837,049 | 1,017,848 |
| Q2 English consolidated | 44 | 1,328 | 1,843,287 | 919,758 |
| Q2 English separate | 44 | 1,370 | 4,861,679 | 724,752 |

Totals: 260 pages, 5,357 blocks, 214 tables, 16,268 cells, 8,884 chunks and 19,843,392 canonical artifact bytes. Timings come from durable attempt timestamps: queue wait is created-to-started; whole attempt is started-to-completed, not Docling execution time alone. Processing was sequential.

All six bounded cash-flow diagnostics abstained as `INCOMPLETE`: three missing row identities, two missing row codes and one missing period headers. The known Q1 consolidated total-liabilities-and-equity boundary on physical page 8 still contains a prior-period value in the current-period column and lacks an unambiguous prior-period cell. Nothing was repaired or certified. This run does not establish a new strict accuracy score for all six reports; the sealed four-report score remains 93/104, and the 95/104 recovery candidate remains offline.

The actual Orca Source page showed six indexed Documents. Its final Files snapshot and screenshot showed six `Indexed` / `Search index: Ready` rows, with separated filename, Size, Status, timestamp and Actions columns. Earlier narrow-screen checks and the background keyboard-focus limitation remain as recorded in the Connector matrix. No Search/Chat interaction or staging deployment was added.

Ignored evidence: `.tmp/tasco-2025/quality-followup/recreated-corpus/artifact-verification.json`, canonical artifacts, `q1-financial-boundary.private.json`, `source-completed.png` and `files-completed.png`; deletion and recreation records remain in the parent evidence directory.

### Structural investigation and publication boundary — 2026-09-12

The retained Worker exited with Windows status `1073807364` (`0x40010004`, `DBG_TERMINATE_PROCESS`). Its log contains normal startup but no application failure or orderly shutdown. Host sleep/resume events occurred around the interruption; the bounded event/crash review did not identify a matching application crash or resource-exhaustion event. A disposable ConPTY-close reproduction produced `0xC000013A`, not the observed status, while the non-PTY control survived. The exact termination caller remains unproven; no automatic restart, retry or attempt-state repair is introduced.

Two sequential authenticated conversions used the identical original Q1 page-8 PDF and unchanged OCR/table settings except `table_cell_matching`. Matching enabled retained 62/70 strict numeric cells; disabling matching returned 180 empty table cells and scored 0/70. Both conversions completed successfully, demonstrating why parser success alone is not fidelity evidence. The missing current-period total was absent from exported text; the misplaced prior-period value occupied a bounding box spanning both period columns. Reject the option change rather than guessing a column or manufacturing a value. Server processing times were 51.004 and 46.805 seconds respectively; this uncontrolled pair is not a throughput improvement.

A separate same-issuer hold-out benchmark was prepared from 16 scored original pages plus three continuation-support pages, excluding the Q1 tuning page: 121 numeric cells, 20 dashes, three source-unscorable cells, blank-note and row/period/merged-header/continuation controls. Pre-score synthetic checks rejected body text used as headers and overlapping row labels after evaluator hardening. The original 104-cell truth/scorer remain unchanged. The user prioritized publication before the expanded corpus evaluation; no new held-out score or production recovery claim is made.

Private evidence remains under `.tmp/tasco-2025/structure-followup/`; publication excludes PDFs, exact financial content, endpoint/key snapshots, crash logs and experiment scripts. Existing financial-fidelity and full remote 100 MiB admission limitations remain open.

The latest diagnostic change passed all 47 focused cases (18 financial diagnostics, 10 canonical Docling extraction, 12 bounded client and seven properties), with no failures/errors/skips. A disposable Java source launcher then invoked the compiled production assessor on all six retained canonical artifacts: seven scoped checks, six `INCOMPLETE` and one `INVALID_NUMERIC`; every original block remained deeply equal. This demonstrates more specific diagnosis, not corrected financial values. JDK 25.0.3/Gradle fallback verification was used because no JetBrains MCP or configured Java LSP was available. The disposable launcher and init script were removed after the smoke run.
