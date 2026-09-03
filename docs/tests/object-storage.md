# Object storage verification matrix

| Contract | Evidence |
| --- | --- |
| S3 authorization signs method, key, media type, and checksum; MinIO accepts the exact request and rejects altered bytes | `S3ObjectStorageIntegrationTest.presignsVerifiesStreamsAndIdempotentlyDeletesObjects`, `rejectsContentThatDoesNotMatchTheSignedChecksum` |
| Authorization expiry prevents object creation | `S3ObjectStorageIntegrationTest.expiredAuthorizationCannotCreateAnObject` |
| Inspect and streaming open return the persisted SHA-256 metadata; deletion is idempotent and missing objects are typed | `S3ObjectStorageIntegrationTest.presignsVerifiesStreamsAndIdempotentlyDeletesObjects` |
| Readiness probes one sentinel without bucket listing or checksum metadata | `S3ObjectStorageIntegrationTest.probesAReadinessSentinelWithoutRequiringUploadChecksumMetadata`, `ObjectStorageHealthIndicator` |
| MinIO CORS admits the configured browser origin and signed PUT headers but not an untrusted origin | `S3ObjectStorageIntegrationTest.allowsOnlyTheConfiguredBrowserOriginToSendSignedUploadHeaders` |
| Wrong-Tenant access, metadata mismatch, retry, adoption replay, and adopted-object retention are enforced in PostgreSQL | `ObjectUploadLifecycleIntegrationTest.tenantIsolationIntegrityRetryAndReplayAreEnforced` |
| Provider inspection failures return a stable service-unavailable upload error and release verification for retry | `ObjectUploadLifecycleIntegrationTest.providerInspectionFailureReturnsAStableRetryableUploadError` |
| Expired and discarded pre-adoption objects are token-claimed, deleted once, and retained only as idempotency tombstones | `ObjectUploadLifecycleIntegrationTest.expiredPendingUploadIsDeletedOnceAndLeavesAnIdempotencyTombstone`, `PostgresSourceLifecycleTest.duplicateDiscardAndAdoptedRemovalReleaseEveryObjectReference` |
| Cleanup/verification races are fenced; transient deletion is retried only after lease expiry | `ObjectUploadLifecycleIntegrationTest.expiredVerificationClaimIsFencedFromCleanupCompletion`, `transientDeleteFailureIsRetriedOnlyAfterCleanupLeaseExpiry` |
| Replayed Source finalization returns one receipt; duplicate content converges and every active object reference is released on removal | `PostgresSourceLifecycleTest.finalizeReplayReturnsThePersistedReceiptWithoutAdoptingTwice`, `duplicateUploadConvergesOnOneItemVersionAndAttempt`, `duplicateDiscardAndAdoptedRemovalReleaseEveryObjectReference` |
| Browser bytes use the authorized object-storage origin, never an API request; progress and retry reuse one PUT | `identity-shell.spec.ts` FILE source scenario, `direct-upload.test.ts` |
| Real worker indexing streams from MinIO and remove/delete cleanup removes provider bytes and relational ownership through Redis delivery | `WorkerFileProcessingIntegrationTest.redisStreamsIndexRemoveAndDeleteOneRealFile` |
| Deployment bootstrap is private, idempotent, uses writable ephemeral client config, and authenticates distinct API/worker users against the sentinel | `infrastructure/minio/bootstrap.sh` plus the MEM-52 deployment smoke evidence |
