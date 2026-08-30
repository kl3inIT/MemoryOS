# Ingestion verification matrix

| Contract | Evidence |
| --- | --- |
| TXT, Markdown, PDF, and DOCX bytes are detected/extracted despite misleading extensions | `TikaSourceContentExtractorTest` |
| Unsupported, encrypted, malformed, and write-limit content returns typed failures; timeout terminates the child process and extractor close remains bounded | `TikaSourceContentExtractorTest` failure and lifecycle matrix |
| PostgreSQL `SKIP LOCKED` claim plus expired lease issues a new token and rejects stale completion | `PostgresSourceConcurrencyTest.staleWorkerTokenCannotCompleteAfterLeaseReclaim` |
| Real scheduled worker carries each durable record's `TenantId` through indexing, removal, and deletion on PostgreSQL, including cleanup after Tenant deactivation | `SourceWorkerTest.clampsTheBatchAndDelegatesAvailableWork` and `WorkerFileProcessingIntegrationTest.schedulerIndexesRemovesAndDeletesOneRealFile` |
| Provider adapter imports only public capability APIs | `ProviderDependencyRulesTest` |
| Worker starts with JDBC/provider composition and scheduling disabled in smoke context | `WorkerApplicationSmokeTest` |
