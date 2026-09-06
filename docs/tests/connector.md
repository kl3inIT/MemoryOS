# Connector verification matrix

| Contract | Evidence |
| --- | --- |
| Only active OWNER manages Sources; member receives safe 403 | `SourceApiIntegrationTest` |
| Concurrent FILE source creation shares one NO_AUTH Credential | `PostgresSourceLifecycleTest.concurrentSourceCreationSharesOneNoAuthCredential` |
| Duplicate bytes converge on one item/version/live attempt | `PostgresSourceLifecycleTest.duplicateUploadConvergesOnOneItemVersionAndAttempt` |
| Source upload initiation binds generic authorization to one Pair; finalization replay returns one receipt without adopting twice | `SourceApiIntegrationTest`, `PostgresSourceLifecycleTest.finalizeReplayReturnsThePersistedReceiptWithoutAdoptingTwice` |
| Duplicate discard and adopted removal release all live object, upload, version, and source-upload references without violating restrictive foreign keys | `PostgresSourceLifecycleTest.duplicateDiscardAndAdoptedRemovalReleaseEveryObjectReference` |
| Every live source association carries `tenant_id`, and the schema rejects a second Tenant | `DefaultInitialTenantBootstrapperTest.databaseRejectsASecondTenant`, V6 migration, and source repository tests |
| PUBLIC mapping grants owner/member and invalidates immediately on remove | `SourceApiIntegrationTest.indexesAndCleansUpOneFileThroughTheAuthorizedApi` |
| Browser initiation → direct object PUT → finalize, truthful retry, remove/delete, and durable polling drive Source state without sending bytes through the API | `identity-shell.spec.ts` FILE source scenario |
| Browser groups configured Sources in a searchable/filterable semantic six-column table with provider totals, collapse/expand controls, status/access badges, and icon-only Manage navigation; renders the searchable fixed-tile catalog and single-step FILE form; and preserves failed-finalization recovery across Source routes without another PUT | `identity-shell.spec.ts` FILE source scenario |
| FILE setup validates selection before writes, supports drop/browse and removal, suggests a name, submits once, retries create/upload/finalize failures without duplicating a known Source or repeating successful PUT, and fits mobile width | `file-source-setup.spec.ts` |
| Application has no SQL/JdbcClient and persistence remains capability-owned | `CoreDependencyRulesTest`, Modulith verification, and IDE inspection |
