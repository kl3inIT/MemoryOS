# MEM-52 design: MinIO object storage and direct FILE uploads

## Outcome

MemoryOS moves immutable FILE binaries out of PostgreSQL and into private object storage before MEM-9. MinIO is the first deployed backend, accessed through the S3 API. The browser uploads bytes directly through short-lived presigned PUT authorizations; MemoryOS API requests authorize and finalize the lifecycle but never proxy file content.

PostgreSQL remains authoritative for Tenant ownership, upload state, ConnectorItem/version identity, index and cleanup operations, claims, leases, and terminal results. MinIO owns raw immutable bytes. Redis remains rebuildable identifier-only delivery state.

## Onyx evidence and deliberate differences

MemoryOS adopts the durable parts of Onyx's design, not its implementation shape:

- Onyx exposes a provider-neutral `FileStore` and persists an opaque `file_id` separately from display metadata and provider location.
- Its adapters include `S3BackedFileStore`, `GCSBackedFileStore`, `AzureBlobBackedFileStore`, and `PostgresBackedFileStore`.
- `FileOrigin.INDEXING_STAGING` is promoted to a retained connector origin, and attempt cleanup reaps abandoned staging files.

Onyx does not have a generic presigned `ObjectUpload`, upload-intent, or finalize state machine. Browser-facing FILE, project/user-file, avatar, skill-bundle, and user-library uploads are API-proxied and each capability owns its database association, status, replacement, and cleanup choreography. Chat-generated files, indexing checkpoints, log exports, sandbox snapshots, and other artifacts share `FileStore` plus `FileOrigin`, but not one generic upload aggregate.

The strongest reusable Onyx lifecycle is indexing staging: connector-generated raw files are saved with `INDEXING_STAGING`, promoted to `CONNECTOR` in the same database commit that adopts their `file_id`, cleaned at attempt end, and reaped from a crashed prior attempt when the next attempt starts. MemoryOS generalizes this stage/verify/adopt/reap invariant for direct presigned uploads while keeping each consuming capability responsible for its own association and receipt.

MemoryOS keeps those invariants: opaque application identity, metadata/byte separation, explicit staging/adoption, and orphan cleanup. It intentionally does not copy Onyx's API-proxied `UploadFile` path, global backend factory, provider location leakage, startup bucket creation, PostgreSQL compatibility backend, or mixed metadata/provider-I/O responsibility.

## Browser source discovery and setup

Onyx separates available connector types from configured connector instances: `/admin/add-connector` renders provider metadata tiles, then the selected provider owns its setup steps. MemoryOS adopts that information architecture without copying Onyx's deprecated numeric `FormContext` or FILE-specific step skipping.

The public browser vocabulary remains provider-neutral:

- a **Source type** is an implemented provider that can create Sources, such as `FILE`;
- a **Source** is one configured Tenant-owned instance, such as “Product documentation”;
- Connector, Credential, and Pair remain internal persistence and orchestration concepts.

`/admin` is a full-width semantic configured-Source table and contains no selected detail pane. Sources are grouped by provider under expandable summary rows with Source/document totals. Each Source row exposes Name, Last indexed, Status, Documents, and Manage, then navigates to `/admin/sources/{sourceId}` for uploads, indexing, cleanup, and destructive actions. `/admin/sources/new` lists only implemented Source types. Selecting FILE opens `/admin/sources/new/file`; successful creation returns to the dedicated detail route for the created Source.

Setup uses a typed `SourceSetupWizard` shell. Each provider supplies its own ordered step identifiers, labels, content, validation, and completion action. The shell owns a vertical 36px-row dot/connector rail plus a three-column Previous/Create/Continue navigation row; it does not know provider-specific fields. FILE currently has one meaningful `configuration` step, so the UI renders one step rather than inventing credential, review, or advanced steps. A later Google Drive increment can supply authorization and configuration steps through the same shell when that runtime exists.

The persistent administration layout also owns in-memory pending-finalization recovery. When provider PUT succeeds but API finalization fails, navigating through the Source list or another Source detail retains the initiating `sourceId`, `uploadId`, and filename without retaining the presigned URI or file bytes. Another detail route links back to the initiating Source; retry finalizes there without issuing a second provider PUT.

The TanStack route shape is explicit: `_authenticated.admin.sources.new.tsx` renders an `Outlet`, `_authenticated.admin.sources.new.index.tsx` is the catalog leaf, `_authenticated.admin.sources.new.file.tsx` is the FILE setup leaf, and `_authenticated.admin.sources.$sourceId.tsx` is the configured-Source detail leaf. The persistent administration `AppShell` remains mounted across the flow.

Provider discovery uses small Onyx-aligned icon-and-label tiles grouped under nonempty provider categories. FILE appears exactly once under `Popular` while it is the sole implementation. Search, descriptions inside tiles, unavailable-provider tiles, and “coming soon” connectors remain absent until multiple implemented providers make them useful.


## Naming model

`FileStore` is not used in MemoryOS Java code. It collides conceptually with `java.nio.file.FileStore` and obscures the split between durable metadata and external provider I/O. The enterprise vocabulary is:

| Concern | Name | Responsibility |
| --- | --- | --- |
| External provider port | `ObjectStorage` | Authorize upload, inspect, open, and delete object bytes |
| Reusable direct-upload lifecycle | `ObjectUpload` / `object_uploads` | Initiation, verification, adoption, discard, and expiry |
| First adapter | `S3ObjectStorage` | AWS SDK v2 S3 client/presigner; verified with MinIO |
| Future non-S3 adapter | `AzureBlobObjectStorage` | Future implementation of the same port; absent in MEM-52 |
| Provider-neutral address | `ObjectKey` | Opaque, server-created immutable object name |
| Observed provider facts | `ObjectMetadata` | Size, SHA-256 checksum, and content type |
| Direct-upload capability | `UploadAuthorization` | Method, URI, required headers, and expiry |
| Verified upload claim | `VerifiedObject` | Stored-object reference plus token-fenced verification evidence |
| Bounded readable bytes | `ObjectContent` | Closeable streaming content plus verified metadata |
| Durable raw-object record | `StoredObject` / `stored_objects` | Tenant ownership, key, integrity, and lifecycle state |
| Source-specific association | `SourceUploadLink` / `source_uploads` | FILE source binding and idempotent source receipt |
| Source final result | `SourceUploadReceipt` | Item, version, and index-attempt identifiers |
| SQL implementations | `JdbcStoredObjectRepository`, `JdbcObjectUploadRepository`, `JdbcSourceUploadRepository` | Capability-owned locks, transitions, and row mapping |
| Reaper | `AbandonedObjectUploadCleanupTask` | Generic cleanup of expired, unadopted, or discarded staged objects |

Application methods are `initiateUpload` and `finalizeUpload`. HTTP resources are:

```text
POST /api/sources/{sourceId}/uploads
POST /api/sources/{sourceId}/uploads/{uploadId}/finalize
```

The storage port and JDBC repositories remain separate. `ObjectStorage` never creates application metadata, commits database transactions, or decides Source lifecycle. Repositories never sign URLs or call provider SDKs.

## Package and capability ownership

`ObjectStorage` does not belong to Connector. Connector is currently the first consumer, but raw object storage is also a natural dependency for future attachments, imported media, exports, and other ingestion sources. Owning it inside Connector would reverse that dependency and make later consumers import a source-management capability for unrelated byte I/O.

MEM-52 therefore adds one real, implemented `objectstorage` Spring Modulith capability inside the existing `core` Gradle module. A separate Gradle module is unnecessary: there is no independent release or runtime boundary, and both API and worker already depend on `core`.

```text
core/src/main/java/io/memoryos/objectstorage/
  package-info.java
  ObjectStorage.java
  ObjectKey.java
  ContentSha256.java
  ObjectMetadata.java
  ObjectContent.java
  UploadConstraints.java
  UploadAuthorization.java
  ObjectStorageException.java
  ObjectStorageFailureCode.java
  StoredObjectId.java
  StoredObjectReference.java
  StoredObjectRegistry.java
  ObjectUploadId.java
  ObjectUploadSpecification.java
  ObjectUploadAuthorization.java
  ObjectVerificationToken.java
  VerifiedObject.java
  ObjectUploadService.java
  ObjectUploadCleanupPort.java

  application/
    DefaultObjectUploadService.java
    DefaultStoredObjectRegistry.java

  lifecycle/
    StoredObject.java
    StoredObjectState.java
    ObjectUpload.java
    ObjectUploadState.java

  persistence/
    JdbcStoredObjectRepository.java
    JdbcObjectUploadRepository.java

  s3/
    S3ObjectStorage.java
    S3ObjectStorageProperties.java
    S3ObjectStorageConfiguration.java

core/src/main/java/io/memoryos/connector/
  SourceUploadReceipt.java
  SourceManagementService.java
  IndexWork.java

  application/
    DefaultSourceManagementService.java

  upload/
    SourceUploadLink.java

  persistence/
    JdbcSourceUploadRepository.java
    JdbcSourceItemRepository.java
    JdbcIndexAttemptRepository.java
    JdbcCleanupAttemptRepository.java

api/src/main/java/io/memoryos/api/source/
  SourceController.java
  contract/
    InitiateSourceUploadRequest.java
    SourceUploadAuthorizationResponse.java
    SourceUploadReceiptResponse.java

worker/src/main/java/io/memoryos/worker/
  AbandonedObjectUploadCleanupTask.java
  ControlPlaneConfiguration.java

core/src/main/resources/db/migration/
  V9__cut_over_file_content_to_object_storage.sql
```

Ownership is explicit:

- `objectstorage` owns provider I/O, `StoredObject`, the reusable `ObjectUpload` verification/adoption lifecycle, S3 configuration, `stored_objects`, and `object_uploads`.
- Before adoption, `ObjectUploadCleanupPort` owns expired, abandoned, verified-but-unclaimed, and discarded object cleanup because no product capability owns those bytes yet.
- After adoption, the consuming capability owns retention and deletion timing. Connector owns FILE removal/source deletion; a future conversation capability owns attachment deletion. `objectstorage` supplies idempotent byte deletion and `StoredObjectRegistry` metadata removal but never initiates deletion of an `ACTIVE` object.
- `connector` owns only `SourceUploadLink`, FILE source authorization, item/version convergence, the source receipt, active FILE-object cleanup choreography, and `source_uploads`.
- `ingestion` consumes public `ObjectStorage.open` through `IndexWork`; it never imports S3 or object-storage persistence.
- `api` maps source-specific HTTP DTOs to public Connector commands; it never invokes an SDK.
- `worker` schedules generic abandoned-upload cleanup and capability-specific Connector cleanup through their public ports; it never imports either capability's internal packages.

```text
Before adoption:
  objectstorage decides cleanup eligibility
  PENDING / VERIFYING / VERIFIED / DISCARDED -> generic reaper

After adoption:
  owning capability decides cleanup eligibility
  ConnectorItemVersion -> Connector CleanupAttempt -> object deletion
```

Module dependencies are acyclic:

```text
tenant <- objectstorage
tenant + document + objectstorage <- connector
tenant + document + connector + objectstorage <- ingestion
core <- api
core + connector adapter bundle <- worker
```

`ObjectUploadService` is the public application boundary for generic direct-upload transitions; both JDBC repositories remain internal to `objectstorage`. `JdbcSourceUploadRepository` persists only the source-to-upload association and final receipt. Connector coordinates public object-storage operations with its own concrete repositories without cross-capability persistence imports.

The reusable object-storage application contract is:

```java
public interface ObjectUploadService {
    ObjectUploadAuthorization initiate(
            TenantId tenantId,
            ObjectUploadSpecification specification);

    VerifiedObject verify(
            TenantId tenantId,
            ObjectUploadId uploadId);

    StoredObjectReference adopt(
            TenantId tenantId,
            ObjectUploadId uploadId,
            ObjectVerificationToken token);

    void discard(
            TenantId tenantId,
            ObjectUploadId uploadId,
            ObjectVerificationToken token);
}
```

The public Connector contract binds that generic upload to a FILE source:

```java
ObjectUploadAuthorization initiateUpload(
        ActorId actorId,
        SourceId sourceId,
        ObjectUploadSpecification specification);

SourceUploadReceipt finalizeUpload(
        ActorId actorId,
        SourceId sourceId,
        ObjectUploadId uploadId);
```

`ObjectUploadSpecification` carries filename, media type, size, and `ContentSha256`. `ObjectUploadAuthorization` combines the durable `ObjectUploadId` with `UploadAuthorization`. `VerifiedObject` carries verified metadata and an opaque token; only token-guarded `adopt` or `discard` can close it. `SourceUploadReceipt` carries stable item, version, and index-attempt identifiers.

## Reuse by future consumers

`objectstorage` owns the reusable 80%: object identity, direct-upload authorization, integrity verification, streaming reads, lifecycle fencing, and abandoned-object cleanup. Each consuming capability owns only its authorization, association, retention decision, and final receipt.

```text
FILE source:
  source_uploads(source_id, object_upload_id, source receipt)

Future conversation attachment:
  conversation_attachment_uploads(conversation_id, object_upload_id, attachment receipt)

Future imported media:
  media_imports(owner_id, object_upload_id, media receipt)
```

All three can reuse the same `ObjectUploadService`, `ObjectStorage`, `stored_objects`, `object_uploads`, MinIO adapter, verification, and reaper without importing Connector. Consumer tables use normal foreign keys to `object_uploads` and never add polymorphic `owner_type`/`owner_id` columns to object storage.

Server-originated writes, presigned downloads, multipart/resumable upload, retention classes, and multi-backend routing are intentionally absent until a real consumer requires them. For example, a future Google Drive ingestion increment may add a bounded streaming write operation to `ObjectStorage`; MEM-52 does not add that unused method merely to predict the provider.

## System flow

```text
Browser                  Source API / PostgreSQL             ObjectStorage / MinIO             Redis worker
   |                                  |                                |                              |
1. | initiateUpload(metadata) ------->|                                |                              |
   |                                  | authorize OWNER + source       |                              |
   |                                  | create StoredObject(STAGED)    |                              |
   |                                  | create ObjectUpload(PENDING)   |                              |
   |                                  | bind SourceUploadLink          |                              |
   |                                  | authorizeUpload ------------->|                              |
2. |<---------------------------------| UploadAuthorization            |                              |
3. | PUT bytes + signed headers ------------------------------------->|                              |
4. | finalizeUpload(uploadId) ------->|                                |                              |
   |                                  | claim VERIFYING token + lease  |                              |
   |                                  | inspect --------------------->|                              |
   |                                  |<-------------------------------| ObjectMetadata               |
   |                                  | verify key/size/SHA-256/type   |                              |
5. |                                  | token-guarded DB transaction:  |                              |
   |                                  |  StoredObject -> ACTIVE        |                              |
   |                                  |  ObjectUpload -> ADOPTED       |                              |
   |                                  |  item/version/attempt + receipt|                              |
6. |<---------------------------------| SourceUploadReceipt            |                              |
7. |                                  | relay attempt ID -------------------------------------------->|
8. |                                  |                                |<------------- open(ObjectKey)|
   |                                  |                                |-------------- ObjectContent->|
9. |                                  |<------------------------------- durable terminal finalization|
10.|                                  |                                |<--------------- XACK / XDEL --|
```

### Phase 1: initiate

`POST /api/sources/{sourceId}/uploads` accepts JSON metadata only:

- normalized display filename;
- allowlisted media type;
- declared size within the existing 1 byte–10 MiB FILE bound;
- lowercase hexadecimal SHA-256.

The command requires an active Tenant OWNER and an active FILE Pair. The generic object-storage service creates `StoredObjectId`, `ObjectUploadId`, and an immutable key such as `raw/{tenantId}/{storedObjectId}`. Client filenames never enter the key.

Object storage first commits `StoredObject(STAGED)` and `ObjectUpload(PENDING)`. Connector then persists `SourceUploadLink(sourceId, objectUploadId)` after revalidating the source; only after that association commits does the API return the authorization. A failure between those steps produces a generic abandoned object upload that the reaper can remove, never an unowned ConnectorItem.

`ObjectUploadService` calls `ObjectStorage.authorizeUpload` and returns `ObjectUploadAuthorization`. The authorization is scoped to exactly one key, method, expiry, content type, `x-amz-checksum-sha256` value, and `Content-Length`. The browser receives no object-store credential, bucket listing permission, read permission, or stable object URL. Reissuance retains the same upload, object identity, key, integrity declaration, and original maximum lifetime.

### Phase 2: direct upload

The browser performs one PUT directly to the authorization URI with exactly the required client-managed headers. The browser supplies the signed `Content-Length` automatically; application code neither sets nor exposes that forbidden header. UI state distinguishes upload progress, cancellation, expired authorization, object-provider rejection, and API finalization failure. Finalize retry retains the initiating `sourceId` and never emits another provider PUT.

The MinIO bucket is private. CORS permits only configured MemoryOS origins, PUT/preflight methods, and required content/checksum headers. Presigned URIs are short-lived bearer capabilities and are excluded from logs, telemetry, error payloads, durable browser state, and query caches.

### Phase 3: finalize

`POST /api/sources/{sourceId}/uploads/{uploadId}/finalize` is an explicit idempotent command:

1. Load the Tenant/source association and return its persisted `SourceUploadReceipt` when already finalized.
2. Reject an upload that is not associated with this Tenant and source.
3. `ObjectUploadService.verify` claims `PENDING`, or reclaims an expired `VERIFYING` claim, with a fresh token and short lease.
4. Outside the database transaction, it calls `ObjectStorage.inspect(ObjectKey)`, compares actual key, size, SHA-256 checksum, content type, and required provider metadata, then token-guardedly persists `VERIFIED`. ETag is never treated as a SHA-256 contract.
5. In a Connector-owned transaction, revalidate the live source, converge/create ConnectorItem and ConnectorItemVersion, create or resolve one live IndexAttempt, persist `SourceUploadReceipt`, and call `ObjectUploadService.adopt` with the verification token so the stored object becomes `ACTIVE` and the upload becomes `ADOPTED`.
6. Return only after commit. Redis publication remains downstream of the existing PostgreSQL-authoritative relay.

Generic verification prevents the abandoned-object reaper and concurrent consumer finalizations from racing provider inspection while avoiding provider I/O inside a database transaction. A deterministic metadata rejection returns the object upload to `PENDING` while its original lifetime remains valid. Process loss leaves `VERIFYING`; another request may reclaim only after its lease expires. A verified-but-unadopted object remains bounded cleanup work. If Connector adoption commits but the HTTP response is lost, the source association returns the same persisted receipt. No distributed transaction or fabricated object-store rollback is claimed.

### Duplicate content

Pair-scoped duplicate-content convergence remains unchanged. The bytes are uploaded and generically verified before they influence Source state. When their verified checksum matches the current version, Connector persists the existing item/version/live-attempt receipt and calls `ObjectUploadService.discard` with the verification token. The object upload becomes `DISCARDED`, its stored object becomes `DELETE_PENDING`, and generic cleanup removes only that new object. Objects are never deduplicated across Tenants.

### Worker indexing

Index claims carry `StoredObjectId` and `ObjectKey`, not cloned `byte[]`. `DefaultIngestionCoordinator` opens `ObjectContent` outside claim/finalization transactions. The FILE adapter streams bounded content into temporary storage for the isolated Tika child JVM; the API and worker no longer hydrate raw content from JDBC BYTEA.

The token-fenced claim, scheduled lease renewal for both index and cleanup work, stale-completion rejection, extraction failure taxonomy, Redis acknowledgement, and supersession rules stay unchanged. An `ACTIVE` object remains retained after extraction so parser, chunker, or embedding rebuilds can reprocess the original source later.

### Item and source cleanup

Item removal and source deletion remain Connector-owned. Connector first invalidates provenance and creates/claims its durable `CleanupAttempt`; generic object-upload cleanup cannot select the `ACTIVE` object.

The claimed Connector cleanup obtains the exact `StoredObjectReference`, calls idempotent `ObjectStorage.delete` outside the final database transaction, then in one token-guarded transaction deletes ConnectorItemVersion/item rows and calls public `StoredObjectRegistry.remove` to remove object metadata. `JdbcStoredObjectRepository` remains internal to `objectstorage`.

If provider deletion succeeds and database finalization fails, retry treats provider not-found as success and repeats the guarded database step. If provider deletion is temporarily unavailable, Connector's cleanup operation stays retryable while the Source/item remains non-retrievable. Cleanup never lists the bucket or derives ownership from a key prefix.

### Abandoned object-upload cleanup

`AbandonedObjectUploadCleanupTask` is a cluster-safe bounded db-scheduler task owned by the worker but operating through `ObjectUploadCleanupPort`. It claims expired `PENDING`, expired-lease `VERIFYING`, stale unadopted `VERIFIED`, and `DISCARDED` uploads with a cleanup token and lease, deletes the exact staged object idempotently, and closes it as `EXPIRED` or discarded. It cannot race a live verification/adoption claim, select an `ACTIVE` object, list the bucket, or infer ownership from a key prefix. This cleanup is reusable for every future consumer, not tied to FILE sources.

## ObjectStorage contract

The public `objectstorage` capability port uses provider-neutral verbs. `S3ObjectStorage` translates them to PutObject presigning, HeadObject, GetObject, and DeleteObject:

```java
public interface ObjectStorage {
    UploadAuthorization authorizeUpload(
            ObjectKey key,
            UploadConstraints constraints);

    ObjectMetadata inspect(ObjectKey key);

    ObjectContent open(ObjectKey key);

    void delete(ObjectKey key);
}
```

```java
public record UploadConstraints(
        long sizeBytes,
        String mediaType,
        ContentSha256 checksum) {}

public record UploadAuthorization(
        String method,
        URI uri,
        Map<String, String> requiredHeaders,
        Instant expiresAt) {}

public record ObjectMetadata(
        long sizeBytes,
        String mediaType,
        ContentSha256 checksum) {}

public interface ObjectContent extends AutoCloseable {
    ObjectMetadata metadata();
    InputStream inputStream();
}
```

`ObjectKey` and `ContentSha256` are validated value types, not bare provider strings. `UploadAuthorization` is transport data, not a credential object and not durable state. `ObjectContent.close()` owns provider response-stream closure. `delete` is idempotent: an absent object is success.

`S3ObjectStorage` uses AWS SDK v2 S3 and S3 Presigner internally. Endpoint, region, bucket, path-style access, and credentials are deployment configuration. MinIO is the first supported and verified backend. AWS S3 and Cloudflare R2 may use the same adapter only after provider-specific conformance checks. A future Azure Blob adapter can translate the same MemoryOS contract without changing upload, Connector, or worker services.

Typed failures distinguish not-found, authorization, integrity/precondition, throttling, transient availability, and permanent configuration errors. Provider messages, SDK exceptions, bucket/container names, credentials, and presigned URIs never cross the port as public error details.

The port exists for concrete reasons: API and worker are independent consumers, external provider I/O is not JDBC persistence, and a future non-S3 implementation is accepted. It is not a single-implementation repository interface.

## Durable schema

Flyway V9 introduces:

### `stored_objects`

- `stored_object_id`
- `tenant_id`
- `object_key`
- normalized display filename and declared media type
- verified size and lowercase SHA-256
- lifecycle state: `STAGED`, `ACTIVE`, `DELETE_PENDING`
- expiry and timestamps

### `object_uploads`

- `object_upload_id`
- `tenant_id` and `stored_object_id`
- status: `PENDING`, `VERIFYING`, `VERIFIED`, `ADOPTED`, `DISCARDED`, `EXPIRED`
- original expiry, verification token/lease, verification timestamp, and bounded adoption deadline
- cleanup claim token/lease, attempts, and safe failure evidence

### `source_uploads`

- `tenant_id`, source Pair, and `object_upload_id`
- nullable stable receipt identifiers for ConnectorItem, ConnectorItemVersion, and IndexAttempt
- finalization timestamp
- unique Tenant/source/upload association for idempotent source responses

### `connector_item_versions`

- non-null `stored_object_id` ownership replaces `content_bytes`;
- one raw object belongs to one version;
- Tenant-scoped foreign keys and uniqueness prevent cross-Tenant attachment;
- the foreign key uses `ON DELETE RESTRICT`, so `StoredObject` metadata cannot disappear while a capability still owns it.

Constraints enforce immutable keys, bounded sizes, lowercase SHA-256, valid state/result combinations, and cleanup eligibility. Database rows carry no presigned URI or provider credential. Capability cleanup removes its owning reference and `StoredObject` metadata in the same transaction after provider-byte deletion has succeeded.

## Failure semantics

| Boundary | Durable result |
| --- | --- |
| Initiate transaction fails | No durable upload and no accepted authorization |
| Presigning fails after initiate commit | `PENDING` upload can reissue or expire |
| Browser PUT fails/cancels | No item/version/attempt; upload remains retryable until expiry |
| PUT succeeds but no consumer finalizes | Expired `PENDING` upload and `STAGED` object are reaped generically |
| Object inspection reports missing or integrity mismatch | Verification rejects without partial consumer state |
| Object verifies but consumer adoption never commits | Stale unadopted `VERIFIED` upload is reaped after its adoption deadline |
| Source adoption commits but response is lost | Repeat source finalize returns persisted `SourceUploadReceipt` |
| Verified duplicate is found | Existing source receipt returned; new object upload becomes `DISCARDED` cleanup work |
| Redis is unavailable after finalize | PostgreSQL IndexAttempt remains authoritative and rediscoverable |
| Worker GET is transiently unavailable | Existing bounded processing retry policy applies |
| Delete succeeds but DB finalization fails | Idempotent delete and retry converge |
| Delete is transiently unavailable | Cleanup remains retryable; provenance stays invalidated |

## Deployment and security

MinIO is added with a pinned image, durable volume, health check, private bucket bootstrap, and no anonymous access. Integration tests use a real isolated MinIO container and bucket rather than mocked S3.

API credentials permit signing PUT for the configured prefix and reading metadata required by finalize. Worker credentials permit GET and DELETE for controlled keys. Production secret values remain in deployment-owned mode-`0600` files mounted only into bootstrap and the matching application; repository configuration contains only paths and non-secret identity names. MinIO and its one-shot bootstrap retain only `DAC_OVERRIDE` after dropping all capabilities so their root processes can read those deployment-owned files without widening file modes. Bucket creation and policy reconciliation are deployment responsibilities, never application startup side effects.

Configuration separates the internal service endpoint used by API/worker SDK clients from the browser-reachable upload endpoint used by the presigner. The signed browser host must equal the host reached by the browser, use HTTPS except for loopback development/tests, and be preserved by any ingress proxy. The private single-host Docker bridge uses the capability-qualified `http://memoryos-minio:9000` alias so shared networks cannot resolve another stack's generic `minio` alias. Shared settings cover region, bucket, path-style access, connection/read timeouts, and authorization lifetime.

Deployment provisions a private sentinel object. API and worker readiness open at most a one-byte range from that sentinel with their runtime credentials; readiness never lists the bucket, downloads the full object, creates objects, or mutates policy.

## Clean cutover

The existing multipart endpoint, byte-array application contracts, JDBC BYTEA reads/writes, `content_bytes`, and compatibility tests are removed in the same increment. There is one upload path and one worker path.

MemoryOS remains under the repository's early-project schema policy with no external durable-user-data commitment. Development and staging databases are backed up if needed and reset/recreated for the V9 target schema. MEM-52 ships no temporary migration endpoint, profile, dual read/write path, PostgreSQL binary backend, or permanent BYTEA backfill framework.

## Verification boundary

Real PostgreSQL and MinIO tests prove authorization, signed-header enforcement, expiry, wrong-Tenant isolation, size/checksum mismatch, idempotent finalize, duplicate convergence, orphan cleanup, worker streaming reads, cleanup retries, and active-object retention. Browser verification proves PUT bytes go to MinIO rather than MemoryOS API.

The production images exercise initiate → direct PUT → finalize → PostgreSQL relay → Redis consumer → `ObjectStorage.open` → terminal indexing, then item/source cleanup and expired-upload cleanup.

## Non-goals

- Replacing Tika with Docling, MarkItDown, MinerU, or another parser.
- Chunking, embeddings, vector storage, retrieval, citations, or chat.
- Implementing Azure Blob, GCS, or a second object-storage adapter.
- Claiming AWS S3 or Cloudflare R2 production support without conformance tests.
- Public object download URLs, browser bucket listing, S3 multipart/resumable upload, or objects above the current 10 MiB FILE contract.
