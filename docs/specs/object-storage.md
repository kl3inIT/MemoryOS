# Object storage capability contract

## Ownership and provider boundary

`objectstorage` owns Tenant-scoped immutable object metadata, generic browser-upload authorization, verification claims, adoption, discard, and pre-adoption cleanup. Its public `ObjectStorage` port uses only `ObjectKey`, `UploadConstraints`, `UploadAuthorization`, `ObjectMetadata`, and streaming `ObjectContent`; AWS SDK and MinIO types remain inside `objectstorage.s3`.

`S3ObjectStorage` is the first provider adapter. It is configured with separate service and browser-upload endpoints, static service credentials, region, bucket, path-style behavior, bounded HTTP timeouts, and a bounded authorization lifetime. The browser endpoint requires HTTPS except for explicit loopback development/test addresses. The internal service endpoint may use plain HTTP only inside the private single-host Docker bridge; adding speculative MinIO PKI there would not protect browser traffic. MinIO is the verified S3 implementation, not a type in the capability contract.

## Object and upload lifecycle

Every initiated upload creates one `STAGED` `StoredObject` and one generic `ObjectUpload` before returning authorization. Object keys are server-generated and Tenant-partitioned. The signed PUT binds the immutable key, media type, SHA-256 checksum, and declared `Content-Length`; the browser cannot choose a bucket or key. Browsers supply `Content-Length` automatically, so it is signed but omitted from the client-managed required-header map. Size is also verified from provider metadata during finalization.

Upload states are `PENDING`, `VERIFYING`, `VERIFIED`, `ADOPTED`, `DISCARDED`, `CLEANING`, and `EXPIRED`. Verification uses a token and lease, performs provider inspection outside a database transaction, and compares the actual key-bound size, media type, and SHA-256 with durable declared metadata. Adoption or discard requires the current verification token and an unexpired adoption deadline. A completed adoption is capability-owned and never selected by generic abandoned-upload cleanup.

Expected missing or mismatched uploaded content returns `OBJECT_UPLOAD_INTEGRITY_MISMATCH`. Provider availability or authorization failures return `OBJECT_UPLOAD_STORAGE_UNAVAILABLE` at the upload application boundary. Wrong-Tenant identifiers are indistinguishable from absent uploads. A finalized owner endpoint persists its capability receipt, so replay after a lost response returns the same result without adopting twice.

## Cleanup and readiness

The generic reaper claims only expired pre-adoption uploads and discarded duplicate objects. `CLEANING` plus a token and lease fences cleanup from verification/adoption. Provider deletion precedes token-guarded metadata removal and is idempotent; a row failure remains recoverable after its lease without aborting later claimed rows.

After adoption, the owning capability controls deletion. Connector invalidates provenance, marks the `StoredObject` `DELETE_PENDING`, deletes the provider object, then removes upload associations, versions/items, and object metadata in one claim-fenced transaction. Retried provider deletion and `DELETE_PENDING` marking are idempotent.

Deployment bootstrap owns bucket creation, private access, the readiness sentinel, CORS, service users, and least-privilege policies. API credentials may sign PUT and inspect `raw/*`; worker credentials may inspect and delete `raw/*`; both may read only the configured sentinel. Neither identity can list the bucket. API and worker readiness open a one-byte range from the sentinel and never mutate or list at startup.

## Staging inspection

Staging exposes MinIO's native Console on a dedicated HTTPS origin without publishing container port `9001`. A dedicated confidential Keycloak client uses Authorization Code with S256 PKCE and an exact `/oauth_callback`. Its realm-role mapper emits only the client-scoped `memoryos-inspector` role into the MinIO `policy` claim; the realm assigns that role only to the reconciled initial owner. MinIO uses claim-based authorization rather than a provider-wide role policy so an ordinary realm user cannot gain storage authority through the public STS endpoint.

Deployment bootstrap reconciles the matching `memoryos-inspector` MinIO policy. It may list buckets, inspect the configured bucket, and read its objects, but grants no upload, delete, bucket mutation, IAM, policy, service-account, or server-administration action. The human receives expiring OIDC-derived STS credentials, never the root, API, or worker secret. Production has no Console route, OIDC client-secret mount, or MinIO OIDC configuration.
