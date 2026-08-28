# Ingestion verification matrix

| Contract | Evidence |
| --- | --- |
| TXT, Markdown, PDF, and DOCX bytes are detected/extracted despite misleading extensions | `TikaSourceContentExtractorTest` |
| Unsupported, encrypted, malformed, and write-limit content returns typed failures | `TikaSourceContentExtractorTest` failure matrix |
| PostgreSQL `SKIP LOCKED` claim plus expired lease issues a new token and rejects stale completion | `PostgresSourceConcurrencyTest.staleWorkerTokenCannotCompleteAfterLeaseReclaimAndTenantFksFailClosed` |
| Real scheduled worker indexes, removes, and deletes through PostgreSQL, including cleanup after Organization deactivation | `WorkerFileProcessingIntegrationTest.schedulerIndexesRemovesAndDeletesOneRealFile` |
| Provider adapter imports only public capability APIs | `ProviderDependencyRulesTest` |
| Worker starts with JDBC/provider composition and scheduling disabled in smoke context | `WorkerApplicationSmokeTest` |
