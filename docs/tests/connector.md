# Connector verification matrix

| Contract | Evidence |
| --- | --- |
| Only active OWNER manages Sources; member receives safe 403 | `SourceApiIntegrationTest` |
| Concurrent FILE source creation shares one NO_AUTH Credential | `PostgresSourceConcurrencyTest.concurrentSourceCreationSharesOneNoAuthCredential` |
| Duplicate bytes converge on one item/version/live attempt | `PostgresSourceConcurrencyTest.duplicateUploadConvergesOnOneItemVersionAndAttempt` |
| Cross-Organization live associations fail at composite FKs | `PostgresSourceConcurrencyTest.staleWorkerTokenCannotCompleteAfterLeaseReclaimAndTenantFksFailClosed` |
| PUBLIC mapping grants owner/member and invalidates immediately on remove | `SourceApiIntegrationTest.indexesAndCleansUpOneFileThroughTheAuthorizedApi` |
| Command-style remove/delete and durable polling drive browser state | `identity-shell.spec.ts` FILE source scenario |
| Application has no SQL/JdbcClient and persistence remains capability-owned | `CoreDependencyRulesTest`, Modulith verification, and IDE inspection |
