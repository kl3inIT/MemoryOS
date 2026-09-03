# Ingestion verification matrix

| Contract | Evidence |
| --- | --- |
| TXT, Markdown, PDF, and DOCX bytes are detected/extracted despite misleading extensions | `TikaSourceContentExtractorTest` |
| Unsupported, encrypted, malformed, and write-limit content returns typed failures; timeout terminates the child process and extractor close remains bounded | `TikaSourceContentExtractorTest` failure and lifecycle matrix |
| PostgreSQL `SKIP LOCKED` dispatch claims exclude concurrent relays, rediscover nonterminal operations, defer transport failures without changing business state, and cancel inactive-Tenant indexing | `PostgresSourceLifecycleTest.concurrentRelayClaimsOnceAndRediscoveryRepublishesFromPostgres`, `transportFailureDefersWithoutFailingTheOperation`, and `inactiveTenantCancelsPendingIndexWorkWithoutPublishing` |
| Identifier-scoped processing claims renew only the current token, reject stale completion, expose lease state to reclaim, and terminal-fail exhausted unexpected retries | `PostgresSourceLifecycleTest.staleWorkerTokenCannotCompleteAfterLeaseReclaim` and `unexpectedProcessingFailureRetriesThenTerminatesDurably` |
| Relay messages contain only Tenant/workload/operation/delivery identifiers; stream pressure is bounded independently by workload; Redis and evidence-write failures leave durable work deferred | `RedisOperationRelayTest` |
| Redis topology is idempotent and supports identifier-only XADD → consumer-group delivery → PEL → XACK against real Redis | `RedisExecutionTopologyIntegrationTest` |
| The real worker repairs a deleted stream from PostgreSQL rediscovery, reclaims an abandoned pending delivery, indexes/removes/deletes one file, handles cleanup after Tenant deactivation, and ACKs terminal duplicates without reprocessing | `WorkerFileProcessingIntegrationTest.redisStreamsIndexRemoveAndDeleteOneRealFile` |
| PostgreSQL persists topology and both relay control tasks, revives dead ownership, and prevents concurrent execution across scheduler instances | `ControlPlaneIntegrationTest` |
| API/worker Spring task executors and real db-scheduler execution use virtual threads while configured workload, scheduler, datasource, and Redis bounds remain effective | `ApiApplicationSmokeTest`, `WorkerApplicationSmokeTest`, and `ControlPlaneIntegrationTest` |
| Flyway V7 creates the db-scheduler control plane and V8 creates the Redis dispatch/processing evidence against real PostgreSQL | `SchedulerSchemaMigrationTest` |
| API source commands commit against PostgreSQL without a Redis dependency | `SourceApiIntegrationTest` |
| Redis unavailability makes worker readiness unavailable without changing PostgreSQL operation authority | `RedisUnavailableReadinessIntegrationTest` |
| Redis credentials require TLS at startup; rejected configuration does not expose credentials | `RedisTransportSecurityConfigurationTest` |
| The worker composition contains the Redis consumer and no direct PostgreSQL polling executor or poll-delay configuration | `WorkerApplicationSmokeTest`, full source/configuration review, and worker runtime integration |
| Provider adapter imports only public capability APIs | `ProviderDependencyRulesTest` |
