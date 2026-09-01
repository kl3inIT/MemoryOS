# Ingestion verification matrix

| Contract | Evidence |
| --- | --- |
| TXT, Markdown, PDF, and DOCX bytes are detected/extracted despite misleading extensions | `TikaSourceContentExtractorTest` |
| Unsupported, encrypted, malformed, and write-limit content returns typed failures; timeout terminates the child process and extractor close remains bounded | `TikaSourceContentExtractorTest` failure and lifecycle matrix |
| PostgreSQL `SKIP LOCKED` claim plus expired lease issues a new token and rejects stale completion | `PostgresSourceConcurrencyTest.staleWorkerTokenCannotCompleteAfterLeaseReclaim` |
| Real scheduled worker carries each durable record's `TenantId` through indexing, removal, and deletion on PostgreSQL, including cleanup after Tenant deactivation | `SourceWorkerTest.clampsTheBatchAndDelegatesAvailableWork` and `WorkerFileProcessingIntegrationTest.schedulerIndexesRemovesAndDeletesOneRealFile` |
| Redis execution topology is idempotent and supports identifier-only XADD → consumer-group delivery → PEL → XACK against real Redis | `RedisExecutionTopologyIntegrationTest` |
| PostgreSQL persists the recurring topology control task, revives dead ownership, and prevents concurrent execution across two scheduler instances | `ControlPlaneIntegrationTest` |
| API/worker Spring task executors and the real db-scheduler topology execution use virtual threads while scheduler concurrency remains bounded | `ApiApplicationSmokeTest.applicationTaskExecutorUsesVirtualThreads`, `WorkerApplicationSmokeTest.contextLoadsWithPersistenceRuntimeAndSchedulingDisabled`, and `ControlPlaneIntegrationTest.registersExecutesAndRecoversTheTopologyControlTask` |
| Flyway V7 creates the exact db-scheduler table and indexes against real PostgreSQL | `SchedulerSchemaMigrationTest` |
| Redis unavailability makes worker readiness unavailable without changing PostgreSQL business state | `RedisUnavailableReadinessIntegrationTest` |
| Redis credentials require TLS at startup; rejected configuration does not expose credentials | `RedisTransportSecurityConfigurationTest` |
| Disabling topology avoids binding topology-only settings, while expected Redis access failure is translated without masking programming failures | `WorkerApplicationSmokeTest` and `RedisExecutionTopologyTest` |
| Provider adapter imports only public capability APIs | `ProviderDependencyRulesTest` |
| Worker starts with JDBC/provider composition and scheduling disabled in smoke context | `WorkerApplicationSmokeTest` |
