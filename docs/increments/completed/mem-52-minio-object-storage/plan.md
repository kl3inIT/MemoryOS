# MEM-52 implementation plan: MinIO object storage and direct FILE uploads

Implementation is complete and under repository/runtime verification. Checkboxes describe one clean cutover; no partial runtime mode is independently shippable.

## Contract and schema

- [x] Add the implemented `objectstorage` Spring Modulith capability with public `ObjectStorage`, object value types, `ObjectUploadService`, `ObjectUploadCleanupPort`, `StoredObjectRegistry`, verified-object claims, and typed failure contracts.
- [x] Add V9 `stored_objects`, generic `object_uploads`, and Connector-owned `source_uploads` with state/result constraints, verification/adoption deadlines, cleanup claims, and Tenant-scoped references.
- [x] Replace `connector_item_versions.content_bytes` with non-null `stored_object_id` ownership and an `ON DELETE RESTRICT` foreign key.
- [x] Replace byte-array Connector/Ingestion work contracts with immutable stored-object identities.
- [x] Add provider-neutral `ObjectUploadAuthorization`/`VerifiedObject` and Connector-specific `SourceUploadReceipt` contracts.

## S3 adapter and MinIO

- [x] Add AWS SDK v2 S3 client and presigner only to the concrete storage adapter implementation.
- [x] Implement `S3ObjectStorage` without leaking SDK types or MinIO-specific APIs.
- [x] Authorize one immutable key, content type, and SHA-256 header with bounded expiry.
- [x] Implement inspect/open/idempotent delete and stable provider-failure classification.
- [x] Add MinIO-backed `ObjectStorage` conformance tests for signed headers, expiry, checksum metadata, streaming reads, and idempotent deletion.

## Source upload lifecycle

- [x] Add object-storage-owned `JdbcStoredObjectRepository`/`JdbcObjectUploadRepository` and Connector-owned `JdbcSourceUploadRepository` without cross-capability persistence imports.
- [x] Enforce `connector -> objectstorage` and `ingestion -> objectstorage` through Spring Modulith and ArchUnit without adding a Gradle module or cross-capability persistence import.
- [x] Add OWNER-authorized `POST /api/sources/{sourceId}/uploads` that creates a generic object upload, binds it to the source, and returns authorization only after the binding commits.
- [x] Add idempotent `POST /api/sources/{sourceId}/uploads/{uploadId}/finalize` using generic `VERIFYING`/`VERIFIED` claims followed by Connector-owned adoption.
- [x] Inspect and verify actual object key, size, SHA-256, media type, and metadata outside database transactions before token-guarded `adopt` or `discard`.
- [x] Preserve Pair-scoped duplicate-content convergence and persist duplicate staged objects as `DISCARDED` cleanup work.
- [x] Keep API transactions independent of Redis and preserve one PostgreSQL-authoritative IndexAttempt.
- [x] Remove the multipart endpoint and all API byte-array handling.

## Worker and object lifecycle

- [x] Load `StoredObjectId`/`ObjectKey` rather than BYTEA in identifier-scoped index claims.
- [x] Stream `ObjectContent` through the existing bounded isolated Tika extraction path.
- [x] Keep adopted FILE-object cleanup Connector-owned: invalidate provenance, run idempotent provider deletion, then remove version/item and `StoredObject` metadata in one token-guarded transaction.
- [x] Implement generic `AbandonedObjectUploadCleanupTask` only for pre-adoption expired `PENDING`, expired-lease `VERIFYING`, stale unadopted `VERIFIED`, and `DISCARDED` uploads.
- [x] Preserve lease fencing, retry exhaustion, supersession, terminal completion, XACK/XDEL, and inactive-Tenant cleanup behavior.

## Browser

- [x] Regenerate the OpenAPI snapshot and Hey API client for initiate/finalize JSON commands.
- [x] Replace multipart upload with direct PUT using only `UploadAuthorization` fields.
- [x] Expose truthful progress, cancellation, expiry, provider rejection, and finalize-retry states.
- [x] Restrict browser `connect-src` and MinIO CORS to configured origins and required headers/methods.
- [x] Prove through browser network observation that file bytes never traverse MemoryOS API.
- [x] Separate configured Sources from the implemented Source-type catalog and replace inline FILE creation with `Add source`.
- [x] Add a typed provider-owned setup wizard shell; FILE supplies one real `configuration` step without placeholder credential, review, or advanced steps.
- [x] Replace URL-selected master-detail with a full-width configured-Source list and dedicated Source detail route.
- [x] Match the provider catalog's full-width icon/title/action header, search field, and exact Onyx `w-40` wrapping tile rhythm while showing only implemented providers.
- [x] Align the generic provider-owned wizard with Onyx's lightweight progress rail, centered configuration body, and navigation row.
- [x] Match Onyx provider grouping with a searchable/filterable semantic six-column table, total/active/public/document metrics, expand/collapse controls, status/access badges, and icon-only Manage actions.
- [x] Retain failed-finalization recovery above Source routes so list/other-detail navigation returns to the initiating Source without another PUT.

## Deployment and operations

- [x] Add pinned MinIO service, durable volume, private bucket bootstrap, health check, and least-privilege API/worker policies.
- [x] Configure separate internal service and browser-reachable presigning endpoints plus region/bucket/path-style/TLS/timeout/authorization lifetime; mount distinct deployment-owned API/worker credentials.
- [x] Provision a private sentinel object and use object-scoped inspection for non-mutating API/worker readiness without bucket listing or startup mutation.
- [x] Document the approved early-project database reset and rollback boundary; ship no BYTEA compatibility path.

## Verification and durable records

- [x] Test wrong-Tenant, missing-object, expiry, size/checksum mismatch, replay, lost response, duplicate, concurrent verify/adopt, and expired verification claims against PostgreSQL and MinIO.
- [x] Test generic pre-adoption cleanup independently, then test Connector-owned active-object deletion, transient provider failure, idempotent retry, restricted metadata deletion, and active-object retention.
- [x] Exercise the production Source path from object-upload initiation through indexing, remove/delete cleanup, and abandoned-object cleanup with PostgreSQL, MinIO, and Redis.
- [x] Inspect every non-deleted changed file through JetBrains MCP with warnings enabled, fix actionable findings, and record intentional retained diagnostics.
- [x] Run focused tests, architecture checks, `clean check`, frontend checks, production image builds, and browser/runtime smoke.
- [x] Extend the FILE Source Playwright scenario across list navigation, compact catalog selection, wizard creation, dedicated detail, direct upload/finalize retry, remove, and delete.
- [x] Consolidate architecture, Object Storage/Connector/Ingestion specs, verification matrices, runtime configuration, runbooks, roadmap, and Linear scope.
- [ ] Merge one reviewed head, verify exact merge-SHA CI, close MEM-52, and unblock MEM-9.
