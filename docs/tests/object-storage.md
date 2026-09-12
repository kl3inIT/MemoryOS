# Object storage verification matrix

Evidence boundary — 2026-09-09: the records below were collected before the isolated integration of Google Drive checkpoint `290357a` with main `fb835f9`. Branch Google/runtime evidence and main IAM/Search evidence are retained with their original commands, migration numbers and scenario names; none is a new integrated test result. Branch V13–V24 maps to integrated V18–V29 (+5), while main V1–V17 stays unchanged. Owner-only Google authorization in historical evidence predates the global `SOURCES_MANAGE` cutover. See the [pending integration gates](../increments/active/google-drive-structured-ingestion/plan.md#isolated-main-integration--2026-09-09).

This matrix identifies retained automated coverage and separately labels historical deployment evidence. No validation was rerun during documentation consolidation. PostgreSQL lifecycle tests use controlled storage collaborators to exercise races; S3 adapter tests use MinIO. Neither establishes live Google authorization/acquisition end to end.

## Provider and browser uploads

| Contract | Evidence |
| --- | --- |
| S3 authorization signs method, key, media type, checksum, and size; MinIO accepts the exact request and rejects changed bytes/length | `S3ObjectStorageIntegrationTest.presignsVerifiesStreamsAndIdempotentlyDeletesObjects`; `S3ObjectStorageIntegrationTest.rejectsContentThatDoesNotMatchTheSignedChecksum`; `S3ObjectStorageIntegrationTest.rejectsContentLargerThanTheSignedSize` |
| Expired authorization cannot create an object | `S3ObjectStorageIntegrationTest.expiredAuthorizationCannotCreateAnObject` |
| Browser endpoints require HTTPS except loopback development/test; private service endpoint configuration stays separate | `S3ObjectStoragePropertiesTest.acceptsHttpsAndLoopbackHttpUploadEndpoints`; `S3ObjectStoragePropertiesTest.rejectsNonLoopbackHttpUploadEndpoint` |
| Inspection/streaming return SHA-256 metadata; deletion is idempotent and missing objects are typed | `S3ObjectStorageIntegrationTest.presignsVerifiesStreamsAndIdempotentlyDeletesObjects` |
| Readiness opens a bounded sentinel range without upload-checksum metadata | `S3ObjectStorageIntegrationTest.probesAReadinessSentinelWithoutRequiringUploadChecksumMetadata` |
| MinIO CORS admits the configured browser origin/signed headers, not an untrusted origin | `S3ObjectStorageIntegrationTest.allowsOnlyTheConfiguredBrowserOriginToSendSignedUploadHeaders` |
| Tenant isolation, integrity mismatch, retry, adoption replay and adopted-object retention | `ObjectUploadLifecycleIntegrationTest.tenantIsolationIntegrityRetryAndReplayAreEnforced` |
| Exact 100 MiB upload metadata can be verified and adopted; one byte more is rejected | `ObjectUploadLifecycleIntegrationTest.oneHundredMiBUploadCanBeVerifiedAndAdoptedButOneMoreByteIsRejected`; lifecycle collaborators are controlled, not a 100 MiB live MinIO upload |
| Inspection failure returns a stable retryable upload error | `ObjectUploadLifecycleIntegrationTest.providerInspectionFailureReturnsAStableRetryableUploadError` |
| Expired pending upload cleanup leaves an idempotency tombstone; duplicate discard/removal releases references | `ObjectUploadLifecycleIntegrationTest.expiredPendingUploadIsDeletedOnceAndLeavesAnIdempotencyTombstone`; `PostgresSourceLifecycleTest.duplicateDiscardAndAdoptedRemovalReleaseEveryObjectReference` |
| Cleanup/verification races are fenced; deletion failure does not abort later rows and retries after lease expiry | `ObjectUploadLifecycleIntegrationTest.expiredVerificationClaimIsFencedFromCleanupCompletion`; `ObjectUploadLifecycleIntegrationTest.oneDeleteFailureDoesNotAbortLaterRowsAndIsRetriedAfterCleanupLeaseExpiry` |
| Finalization replay does not adopt twice and duplicate content converges | `PostgresSourceLifecycleTest.finalizeReplayReturnsThePersistedReceiptWithoutAdoptingTwice`; `PostgresSourceLifecycleTest.duplicateUploadConvergesOnOneItemVersionAndAttempt` |
| Browser bytes use the authorized object origin, with transport progress and no second successful PUT on finalization retry | `direct-upload.test.ts` — `uses only authorization fields and reports transport progress`; `identity-shell.spec.ts` — `creates, indexes, removes, and deletes a FILE source`; `file-source-setup.spec.ts` — `FILE single-step setup: finalize` (browser scenarios use routed responses) |
| Real worker FILE indexing streams MinIO bytes; Redis-delivered remove/delete releases provider bytes and relational ownership | `WorkerFileProcessingIntegrationTest.redisStreamsIndexRemoveAndDeleteOneRealFile` |

## Tracked server writes

| Contract | Evidence |
| --- | --- |
| Reservation/key exists before network PUT, outside the transaction; timeout retains durable cleanup ownership even for arbitrarily late PUT completion | `ObjectWriteLifecycleIntegrationTest.timeoutRetainsDurableReservationAcrossCleanupAndArbitrarilyLatePut` |
| A writer completing during cleanup cannot revive the claim or make a later write disappear from cleanup tracking | `ObjectWriteLifecycleIntegrationTest.positiveCompletionDuringCleanupCannotForgetAnObjectWrittenAfterDelete` |
| Adoption requires the caller transaction, correct tenant/token/reference metadata, and rolls back with source acceptance | `ObjectWriteLifecycleIntegrationTest.adoptionIsTenantTokenAndMetadataFencedAndRollsBackWithAcceptance` |
| Adopted references survive generic cleanup/discard, and release is rejected until item versions are removed | `ObjectWriteLifecycleIntegrationTest.acceptedReferencesSurviveCleanupAndCannotBeReleasedBeforeVersionRemoval` |
| Even an unexpected item-version reference prevents abandoned-write cleanup | `ObjectWriteLifecycleIntegrationTest.unexpectedReferenceAlsoPreventsAbandonedCleanup` |
| Integrity mismatch is unadoptable and remains durably tracked for cleanup | `ObjectWriteLifecycleIntegrationTest.integrityFailureRemainsUnadoptableAndTrackedForCleanup` |
| Native snapshots retain their independent 32 MiB limit and cannot be authorized as browser uploads | `ObjectWriteLifecycleIntegrationTest.nativeStorageRetainsItsOwnBoundsAndCannotAuthorizeBrowserUploads` |
| Binary persistence accepts 104,857,600 bytes and rejects 104,857,601; native persistence independently retains 33,554,432 bytes | `ObjectWriteLifecycleIntegrationTest.binaryInputsAdmitOneHundredMiBWithoutChangingNativeSnapshotBounds`; `binaryWritesRejectOneByteBeyondOneHundredMiB` |
| Source synchronization adopts staged objects through page continuation and extracts accepted bytes offline | `PostgresGoogleDriveSyncTest.checkpointsPagesAndResumesWithoutRepublishingOrRestartingTheTraversal`; `PostgresGoogleDriveSyncTest.indexesAdoptedBytesOfflineAndRollsBackPublicationAfterCredentialRevisionChanges` |
| Abandoned tracked writes have a bounded recurring worker cleanup entrypoint | Source inspection: `ControlPlaneConfiguration.abandonedObjectWriteCleanupTask` invokes `ObjectWriteService.cleanup(16)` with a one-minute fixed delay; this is wiring evidence, not a separate live cleanup acceptance run |

## Deployment and inspection

| Contract | Evidence |
| --- | --- |
| Worker policy permits raw acquisition PUT/read/delete and extracted-artifact PUT/read/delete; API retains raw PUT/read; both have sentinel reads without bucket listing | `infrastructure/minio/bootstrap.sh`; [current local worker raw-policy smoke](../increments/active/google-drive-structured-ingestion/plan.md#actual-embedded-browser-and-storage-proof) observed PUT/GET/DELETE success, exact bytes, anonymous 403 and post-delete 404. Not a staging rollout; historical MEM-52 smoke predates raw worker PUT |
| Bootstrap is private/idempotent, uses writable ephemeral client config and authenticates distinct API/worker sentinel users | Historical MEM-52 deployment smoke evidence |
| Staging Console uses exact Keycloak OIDC callback and owner-only claims; owner access is read-only, service-account creation returns `403`, ordinary users get no storage policy, port `9001` is not host-published, and production has no Console/OIDC configuration | Historical MEM-56 Compose, Keycloak token, MinIO STS/S3 and browser acceptance evidence; not rerun for this cutover |

See [connector](connector.md) for Google account/scope authority and source removal, [ingestion](ingestion.md) for acquisition/extraction bounds and durable execution, and [document](document.md) for current Document and extracted-artifact ownership.
