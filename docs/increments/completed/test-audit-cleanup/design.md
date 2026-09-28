# Test-audit cleanup

## Requirement

Owner request 2026-09-25: audit the repository's tests for low-value, implementation-coupled or duplicated checks and the test-only production seams they keep alive, then fix the recommended candidates in one pull request. The sweep ran read-only in four lanes (core; api, worker and sources; web; a repository-wide pattern sweep) against the `test-audit` checklist: mocks that return the asserted value, self-comparisons, copied inventories, exports kept only for tests, dead code whose only caller is a test, and a contract tested again at a weaker layer.

## Scope

In scope: each candidate below, with its contract kept at the strongest existing boundary before the weaker check is removed. The published HTTP contract (`openapi.yml`, `web/src/lib/hey-api`) is unchanged.

Out of scope, left for an owner decision: `SearchGenerations.fixed(...)` (removing it needs database-backed generations in the OpenSearch tests), the test-only `GoogleDriveMetadataCache()` constructor, and the opt-in `ChatFileResourceMeasurementTest` named by `docs/tests/ingestion.md`.

## Decisions

| Candidate | Finding | Action | Contract now owned by |
| --- | --- | --- | --- |
| `web-search.tsx` and its test in `chat-web.test.tsx` | Dead since the activity timeline replaced it; only the test imported it | Delete both (owner confirmed) | `chat-activity-live.test.tsx` |
| `search-page.test.tsx` "persists a real dark theme preference" | Never reloads, so it cannot prove persistence | Delete | `identity-shell.spec.ts` "persists the selected dark theme" |
| `threadMetadata` | Exported only so the test could call it | Assert through `adapter.list()` and `adapter.fetch()`; unexport | `chat-thread-list-adapter.test.ts` |
| `speechSocketUrl`, `transcriptionUrl`, `meetingStreamUrl` | Exported only for tests; the fake socket already records the URL | Assert the exact URL on the opened socket; unexport | the three socket tests |
| `withCorrection` | Private helper exported for one case | Move the append case to `foldCorrection`; unexport | `meetings-api.test.ts` |
| `embeddingTestOutcome` success case | Copies the i18n key object; the browser test checks the rendered text | Delete the success case; keep the failure case | `search-settings.spec.ts` |
| `TOOL_FAILED` | Exported, never imported | Unexport | — |
| `DeepResearchSpikeProbeTest` | MEM-101 spike harness, "not a product contract" | Delete | `ChatSessionApiIntegrationTest` research cases |
| `test_health_version_matches_package_metadata` | Compares the package version with itself | Delete | `test_service_version_matches_helm_chart_version` |
| `ProviderAuthorityServiceTest` routing cases | Mock returns `true`, test asserts `true` | Delete; keep the fail-closed case | `PostgresSharePointSyncTest`, `PostgresGoogleDriveSyncTest` |
| `SearchProjectionMaintenance` 4-argument constructor | Only tests called it, to default the rebuild window | Delete; the tests pass the window | the `@Autowired` constructor |
| `DocumentChunkServiceTest.firstPublication…` | Both assertions read back the stubbed `load` | Delete; verify object close in the PostgreSQL test | `ExtractionArtifactLifecycleTest` |
| `TextHelpersTest` UTF-8 line | Compares `Sha256.hex` overloads with each other | Pin the literal digest of `họp` | `TextHelpersTest` |
| `UndatedDateClauseTest` | Greps `toString()` of the clause | Replace with a real OpenSearch window search over an undated origin; make `dateRange` private | `OpenSearchRetrievalIntegrationTest` |
| `OperationStatusTest` | Mapper test beside boundary tests; `COMPLETED_WITH_ERRORS` had no boundary owner | Add `GET /api/source-operations/{id}` → `SUCCEEDED`; delete | `SourceApiIntegrationTest` |
| `ApiExceptionHandlerTest` business cases | Helper switches copied from the handler, run per unrelated reason enum | One literal row per `FailureCategory` through a local `BusinessException` | `ApiExceptionHandlerTest` |
| `ResearchExecutorTest.onyxPromptHelpers…` | Tests `ResearchPrompts` in the executor's test | Move unchanged to `ResearchPromptsTest` | `ResearchPromptsTest` |
| `test_dataset_facts.py` category test | Row counts copied from the dataset; "actor" never asserted | Assert every category and the ten-question floor | `test_dataset_facts.py` |
| `WorkerDevelopmentConfigurationTest` | Copies YAML placeholder literals; stale "API-owned" name | Keep the classpath and no-own-dev-service guard, renamed | `WorkerDevelopmentConfigurationTest` |

Historical evidence lines in other increments that name a removed test record what ran at that time and are not rewritten.
