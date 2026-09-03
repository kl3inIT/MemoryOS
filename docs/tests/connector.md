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
| Application has no SQL/JdbcClient and persistence remains capability-owned | `CoreDependencyRulesTest`, Modulith verification, and IDE inspection |
