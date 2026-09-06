# MEM-35 design: Tenant-owned FILE connectors

## Outcome

MemoryOS delivers the first production source vertical slice through the Onyx-aligned operational model:

```text
Connector + Credential -> ConnectorCredentialPair -> IndexAttempt -> Document
```

An active Tenant `OWNER` creates a named `FILE` Connector. MemoryOS creates or reuses the Tenant's `NO_AUTH` Credential, links both through one ConnectorCredentialPair, accepts bounded files as first-class ConnectorItems, and indexes them asynchronously through the same Pair-keyed status, scheduler, access, metrics, and cleanup boundary that later Google Drive work consumes.

A file is one ConnectorItem beneath a Connector. It is never one Connector and never an ad hoc mutation of the Knowledge Base.

## Reference decision

The local Onyx reference at upstream main `ec08b5f` establishes the model deliberately:

- Connector configuration and Credential authority are independently reusable;
- ConnectorCredentialPair is the executable source identity, not a passive join;
- Pair owns indexing state, access type, permission-sync settings, timestamps, counts, attempts, errors, and UI status;
- UI creation selects or reuses a Credential, configures a Connector, then configures Pair access/indexing;
- Public and Private Pair access avoid per-document permission synchronization where coarse visibility is sufficient;
- Auto Sync preserves source ACLs only when Connector and authentication method support it;
- FILE and user-library ingestion create empty credentials to reuse the common Pair pipeline.

MemoryOS accepts the same operational tradeoff. A `NO_AUTH` Credential is an explicit authentication context with no provider account, scope, secret reference, token, or refresh lifecycle. It is not provider secret material. Every configured source has at least one Pair so the product does not acquire separate FILE and authenticated-source pipelines.

The design also accounts for the costs observed in Onyx: Pair deletion spans many dependent records, group authorization can leak existence or degenerate into N-query checks, and attempt timestamp coupling can stop scheduling while stale documents remain visible. MemoryOS therefore makes Tenant scoping, cleanup state, authorization queries, and scheduling transitions explicit from the first Pair.

## Product flow

```text
active Tenant OWNER
  -> create FILE Connector
  -> create/reuse deterministic Tenant NO_AUTH Credential
  -> create PUBLIC ConnectorCredentialPair
  -> upload one allowlisted file up to 10 MiB
  -> persist one ConnectorItem revision and NOT_STARTED IndexAttempt atomically
  -> worker claims the attempt
  -> detect media type and extract bounded text
  -> finalize Document, DocumentVersion, Pair mapping, counts, and statuses
  -> UI polls Pair while indexing and polls returned CleanupAttempt after delete
  -> operator can inspect, reindex, remove an item, or delete the source
```

Initial accepted formats are PDF, DOCX, UTF-8 TXT, and Markdown. File extension is presentation metadata only; detected media type controls parser selection. ZIP/container import, OCR, executable content, macros, embedded attachments, watched folders, arbitrary server paths, and multi-file requests are excluded.

## Boundary discovery evidence

MEM-35 applies the repository boundary-discovery sequence before assigning packages or Gradle artifacts.

### Domain stories

```text
Source management:
Tenant OWNER creates/configures/revokes a source and chooses access policy.

Indexing:
Worker claims an item-version attempt, obtains/extracts content, publishes a normalized version, and records outcome.

Corpus access:
An active member requests evidence; current source access and document eligibility decide whether normalized content may be used.
```

These stories have different actors, failure models, and reasons to change: OWNER/control-plane operations, worker/lease execution, and member/read behavior.

### Visual glossary

| Term | Meaning and owner |
| --- | --- |
| Connector | Source configuration; connector capability |
| Credential | Provider authorization context, including explicit NO_AUTH; connector capability |
| ConnectorCredentialPair | Operational source instance, status, access, and indexing configuration; connector capability |
| ConnectorItem | Stable source-native item and revision lineage; connector capability |
| IndexAttempt | One leased processing execution and terminal outcome; ingestion capability |
| Document | Stable normalized corpus identity; document capability |
| DocumentVersion | Immutable normalized content version; document capability |
| DocumentByConnectorCredentialPair | Source provenance/access mapping to DocumentId; connector capability |

Source is the product-area umbrella, not a competing entity name. Pair is the source card/runtime identity. Provider driver is an adapter implementation, not a bounded context.

### Commands, events, aggregates, and read models

Initial synchronous commands are CreateFileConnector, AddFile, ReindexItem, RemoveItem, and DeletePair. Pair/ConnectorItem/Document invariants finalize through mandatory transaction ports. IndexAttempt and CleanupAttempt are durable process state rather than domain events; asynchronous worker execution must not weaken atomic source/document publication.

Pair is the source-management aggregate boundary, ConnectorItem owns source revision identity, Document owns normalized version allocation, and attempts own leases/claims. PairView, ConnectorItemView, IndexAttemptView, and DocumentView are separate read models.

### Data and invariant owners

```text
connector  -> connectors, credentials, pairs, items, item versions, pair/document mappings
document   -> documents, document versions
ingestion  -> index attempts, cleanup attempts
```

Each table has one writer capability. Cross-capability finalization uses public mandatory transaction ports; no capability imports another persistence package.

### Context map and module cut

```text
tenant -> identity
invitation -> identity, tenant

connector -> identity, tenant, document
ingestion -> connector, document, tenant
```

Connector is a source-management capability; document is a normalized-corpus capability; ingestion is a supporting process/application capability. They are separate top-level Spring Modulith modules in core because their language, data ownership, actors, lifecycle, and failure semantics differ. They remain in one Gradle artifact because they release/deploy/transact together. Provider drivers are physical adapters and receive a Gradle boundary only for concrete SDK/classpath isolation; that build decision does not define a bounded context.

## Gradle and capability ownership

MEM-35 keeps product capabilities in `:core` and adds one shared provider-integration bundle:

```text
:core
:connector
:api
:worker
```

### `:core`

Core owns complete product capability implementations that run in one application, transact over one schema, and release together. Current modules are identity, tenant, and invitation. MEM-35 adds:

```text
connector
document
ingestion
```

#### `connector`

Owns Connector, Credential, ConnectorCredentialPair, ConnectorItem/binary lineage, Pair status/access/health, DocumentByConnectorCredentialPair mappings, source-management views, and mandatory transaction ports consumed by ingestion. It depends on public identity, tenant, and document APIs and never on ingestion.

#### `document`

Owns Document/DocumentVersion, normalized title/text/metadata, stable identity, version allocation, eligibility, and hard deletion. It depends only on TenantId and knows no provider SDK, Pair, attempt, worker, or binary-store implementation.

#### `ingestion`

Owns IndexAttempt/CleanupAttempt queues, lease/claim-token lifecycle, extraction/finalization/cleanup orchestration, and provider-facing ports such as SourceContentExtractor. It depends on connector, document, and tenant public APIs.

### `:connector`

This Gradle module is an integration bundle, not the Connector bounded context. It depends on `:core`, implements public provider ports, and contains per-provider folders:

```text
io.memoryos.provider.file
io.memoryos.provider.googledrive
io.memoryos.provider.<future>
```

MEM-35 adds only FILE/Tika. MEM-9 later adds Google Drive/OAuth in the same artifact. Core never imports provider implementation classes or SDK types. Adapter-classpath ArchUnit tests forbid imports of capability `application` or `persistence` packages.

The bundle uses Spring Boot auto-configuration so API/worker source code imports only core. Runtime composition selects the whole bundle:

```text
:connector -> :core
:api -> :core
:worker -> :core
```

MEM-35 runtime:

```text
API    = api + core
Worker = worker + core + connector(FILE/Tika)
```

After MEM-9:

```text
API    = api + core + connector(FILE/Tika + Google)
Worker = worker + core + connector(FILE/Tika + Google)
```

The accepted tradeoff is lower Gradle/build complexity at the cost of bundle-wide dependency, image, CVE, and update blast radius. In particular, API carries Tika after it needs the shared bundle for Google OAuth. This is deliberate and recorded in the implementation ADR.

Provider folders are kept independent from the first commit so they can move unchanged into per-provider Gradle modules if SDK conflicts, image/CVE cost, build time, independent release, or deployable-specific provider selection becomes material. No provider folder/module is created before its implementing increment.

API owns HTTP/OpenAPI/Flyway composition. Worker owns scheduling/process lifecycle. Aggregate Modulith verification remains in `:core:test` and expands from three to six real capabilities.

Reconsider a `:sources` artifact only for an independent source-subsystem consumer, release/runtime, or demonstrated build bottleneck; do not use it merely to mirror package modules.
## Package and type design

```text
core/src/main/java/io/memoryos/
├── connector/
│   ├── package-info.java
│   ├── ConnectorId.java
│   ├── CredentialId.java
│   ├── ConnectorCredentialPairId.java
│   ├── ConnectorItemId.java
│   ├── ConnectorItemVersionId.java
│   ├── ConnectorType.java
│   ├── ConnectorStatus.java
│   ├── CredentialKind.java
│   ├── CredentialStatus.java
│   ├── PairStatus.java
│   ├── PairAccessType.java
│   ├── IndexingMode.java
│   ├── ConnectorItemStatus.java
│   ├── ConnectorAdministration.java
│   ├── ConnectorIndexingPort.java
│   ├── ConnectorCleanupPort.java
│   ├── SourceDocumentAccessResolver.java
│   ├── application/
│   └── persistence/
├── document/
│   ├── package-info.java
│   ├── DocumentId.java
│   ├── DocumentVersionId.java
│   ├── DocumentStatus.java
│   ├── DocumentCommandPort.java
│   ├── DocumentQuery.java
│   ├── application/
│   └── persistence/
└── ingestion/
    ├── package-info.java
    ├── IndexAttemptId.java
    ├── CleanupAttemptId.java
    ├── FileIngestionService.java
    ├── IndexAttemptQueue.java
    ├── ConnectorCleanupQueue.java
    ├── SourceContentExtractor.java
    ├── application/
    └── persistence/

connector/src/main/java/io/memoryos/provider/
├── file/
│   ├── FileProviderAutoConfiguration.java
│   └── TikaSourceContentExtractor.java
└── googledrive/              // added only by MEM-9
    ├── GoogleProviderAutoConfiguration.java
    ├── GoogleDriveClient.java
    └── GooglePermissionReader.java
```

Capability root packages are public APIs. Application and persistence packages remain internal. Credential stays inside the core connector capability. Provider implementations live outside every capability package subtree and may import only public core types.

### Persistence implementation boundary

`DefaultSourceManagementService` owns authorization, input validation, orchestration, transaction boundaries, transition decisions, and typed failures. It contains no SQL, row mapping, `JdbcClient`, locks, claims, or bulk updates.

The application service injects concrete single-implementation repositories from `connector.persistence`; no internal repository interface is added:

```text
JdbcSourceRepository          -> Connector/Credential/Pair create, load, lock, status
JdbcSourceItemRepository      -> item/version identity, current version, byte/hash idempotency
JdbcIndexAttemptRepository    -> attempt creation, ordering, claims, leases, completion/failure
JdbcSourceDocumentRepository  -> Pair/Document provenance and retrieval eligibility
JdbcSourceQueryRepository     -> source/item/operation read projections
```

Repositories follow aggregate/use-case and consistency boundaries, not one class per table. Source/ingestion remains JDBC-first because conditional transitions, row locks, worker claims, multi-join projections, and bulk invalidation require explicit SQL. MEM-35 adds no JPA, Querydsl, or jOOQ. Existing invitation and Tenant JDBC code is not migrated for stylistic uniformity. JPA is reconsidered for MEM-36 Groups only if relationship lifecycle demonstrably reduces complexity.

## Domain semantics

### ConnectorType

Initial values:

```text
FILE
GOOGLE_DRIVE
```

Only FILE behavior ships in MEM-35. GOOGLE_DRIVE reserves the already-approved MEM-9/MEM-10 integration point; no Google runtime path is added here.

### ConnectorStatus

```text
ACTIVE
DELETING
```

The final Pair deletion marks Connector DELETING in the same transaction. No new item or Pair operation is accepted afterward; retries resolve the existing DELETE_SOURCE cleanup.

### CredentialKind

Initial values:

```text
NO_AUTH
GOOGLE_OAUTH
```

MEM-35 creates only NO_AUTH. A deterministic Tenant NO_AUTH credential is unique by `(tenant_id, kind)` and reusable by every no-auth Connector in that Tenant.

### CredentialStatus

```text
ACTIVE
INVALID
REVOKED
```

NO_AUTH has no token lifecycle. Persisted Credential status remains independent of Tenant status; every management, worker claim/finalization, and read decision separately requires the Tenant to remain active.

### PairAccessType

```text
PUBLIC
PRIVATE
SYNC
```

`PUBLIC` means all actors with current active membership in the owning Tenant. It never means internet-public. FILE supports PUBLIC only in MEM-35.

`PRIVATE` requires selected MemoryOS Groups and is rejected until a concrete Group sharing increment ships.

`SYNC` requires source-native permission synchronization supported by both Connector type and authentication method. It is rejected for FILE and deferred to MEM-10.

### IndexingMode

```text
MANUAL
```

FILE uses MANUAL in MEM-35: upload and explicit reindex create work. Scheduled and event-driven modes remain absent until a Connector requires them.

### ConnectorItemStatus

```text
PENDING
INDEXED
FAILED
DELETING
```
ConnectorItem status is derived for its current version: any live mapping means INDEXED; otherwise any nonterminal attempt means PENDING; otherwise the greatest item_sequence terminal FAILED means FAILED; otherwise it remains PENDING until removed. item_sequence is ConnectorItem-wide across Pairs, while Pair status uses pair_sequence.
DELETING makes every Document mapping for the item retrieval-ineligible before cleanup begins. A worker finalization cannot return a DELETING item to INDEXED.

### DocumentStatus

```text
ACTIVE
INELIGIBLE
```

The document module retains a Document only while at least one live connector mapping exists. Final mapping removal hard-deletes Document and versions in the same transaction after making it ineligible.

### PairStatus

```text
NOT_STARTED
INDEXING
ACTIVE
FAILED
DELETING
```

Pair status is derived after every attempt/mapping transition under the Pair row lock:

```text
Pair already DELETING                     -> DELETING
any current attempt NOT_STARTED/IN_PROGRESS -> INDEXING
latest completed attempt FAILED             -> FAILED
at least one live mapping                    -> ACTIVE
otherwise                                    -> NOT_STARTED
```

The final branch covers only cancelled/obsolete history with no live mapping. DELETING dominates every result. One success cannot hide pending work, and an older completion cannot overwrite a newer terminal result.

### IndexAttemptStatus

```text
NOT_STARTED
IN_PROGRESS
SUCCEEDED
FAILED
CANCELLED
```

SUCCEEDED, FAILED, and CANCELLED are terminal. An expired worker lease may be reclaimed with a new claim token on the same IN_PROGRESS attempt; a late worker cannot finalize because its token no longer matches. Retry after a real extraction failure creates a new attempt number.

### CleanupAttemptStatus

```text
NOT_STARTED
IN_PROGRESS
SUCCEEDED
FAILED
SUPERSEDED
```

SUCCEEDED, FAILED, and SUPERSEDED are terminal. Remove/delete commands return CleanupAttemptId, and the record remains queryable after target hard deletion. SUPERSEDED means a broader cleanup atomically adopted the target; it is a successful terminal outcome for polling and idempotent retries.

## Persistence design

Flyway V5 creates the first connector/document schema. Existing migrations remain immutable.

### `connectors`

```text
id UUID PK
tenant_id UUID NOT NULL
connector_type VARCHAR(32) NOT NULL
status VARCHAR(16) NOT NULL
name VARCHAR(200) NOT NULL
configuration_json JSONB NOT NULL
created_by_actor_id UUID NOT NULL
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
UNIQUE (tenant_id, id)
FK tenant_id -> tenants
FK created_by_actor_id -> actors
```

A separate unique expression index enforces `(tenant_id, lower(name))`. FILE configuration contains no file IDs or secrets and records only bounded source-level options exercised by FILE.
### `credentials`

```text
id UUID PK
tenant_id UUID NOT NULL
credential_kind VARCHAR(32) NOT NULL
status VARCHAR(16) NOT NULL
provider_account_reference VARCHAR(500)
secret_reference VARCHAR(500)
created_by_actor_id UUID NOT NULL
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
UNIQUE (tenant_id, id)
FK tenant_id -> tenants
FK created_by_actor_id -> actors
```

A separate partial unique index enforces one NO_AUTH Credential per Tenant. A check requires NO_AUTH provider account and secret reference to be null. Future GOOGLE_OAUTH requires a secret reference and safe provider account metadata.
### `connector_credential_pairs`

```text
id UUID PK
tenant_id UUID NOT NULL
connector_id UUID NOT NULL
credential_id UUID NOT NULL
status VARCHAR(16) NOT NULL
access_type VARCHAR(16) NOT NULL
indexing_mode VARCHAR(32) NOT NULL
next_attempt_sequence BIGINT NOT NULL DEFAULT 1
last_indexed_at TIMESTAMPTZ
last_success_at TIMESTAMPTZ
last_pruned_at TIMESTAMPTZ
last_permission_sync_at TIMESTAMPTZ
document_count BIGINT NOT NULL DEFAULT 0
last_error_code VARCHAR(100)
created_by_actor_id UUID NOT NULL
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
UNIQUE (tenant_id, id)
UNIQUE (tenant_id, id, connector_id)
UNIQUE (tenant_id, connector_id, credential_id)
FK tenant_id -> tenants
FK (tenant_id, connector_id) -> connectors
FK (tenant_id, credential_id) -> credentials
FK created_by_actor_id -> actors
```

FILE + NO_AUTH + PUBLIC + MANUAL is enforced in the creation transaction; provider-specific cross-table invariants cannot be PostgreSQL CHECK constraints.

The schema permits future multiple Credentials per Connector. MEM-35 FILE creation exposes exactly one Pair because one Tenant NO_AUTH Credential plus pair uniqueness cannot produce a second valid FILE Pair. Multi-Pair execution and convergence are enabled only by MEM-9/MEM-10 after their provider rules are implemented.
### `connector_items`

```text
id UUID PK
tenant_id UUID NOT NULL
connector_id UUID NOT NULL
native_key VARCHAR(500) NOT NULL
current_version BIGINT NOT NULL
next_attempt_sequence BIGINT NOT NULL DEFAULT 1
filename VARCHAR(500) NOT NULL
media_type VARCHAR(200)
status VARCHAR(32) NOT NULL
created_by_actor_id UUID NOT NULL
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
UNIQUE (tenant_id, id)
UNIQUE (tenant_id, id, connector_id)
UNIQUE (tenant_id, connector_id, native_key)
FK tenant_id -> tenants
FK (tenant_id, connector_id) -> connectors
FK created_by_actor_id -> actors
```

For FILE, native_key is the lowercase hexadecimal SHA-256 of uploaded bytes. Same bytes in one Connector resolve the same item. Same bytes in different Connectors retain distinct provenance.
### `connector_item_versions`

```text
id UUID PK
tenant_id UUID NOT NULL
connector_id UUID NOT NULL
connector_item_id UUID NOT NULL
version BIGINT NOT NULL
content_sha256 CHAR(64) NOT NULL
binary_content BYTEA NOT NULL
binary_size BIGINT NOT NULL
detected_media_type VARCHAR(200)
original_filename VARCHAR(500) NOT NULL
created_at TIMESTAMPTZ NOT NULL
UNIQUE (tenant_id, id)
UNIQUE (tenant_id, id, connector_id)
UNIQUE (tenant_id, connector_item_id, version)
FK tenant_id -> tenants
FK (tenant_id, connector_item_id, connector_id) -> connector_items
CHECK binary_size = octet_length(binary_content)
CHECK binary_size BETWEEN 1 AND 10485760
CHECK content_sha256 ~ '^[0-9a-f]{64}$'
```

Application code computes SHA-256 while enforcing the byte limit; PostgreSQL checks stored length and digest shape. PostgreSQL BYTEA is the smallest durable production boundary because MemoryOS has no object store. No speculative storage interface is introduced.
### `index_attempts`

```text
id UUID PK
tenant_id UUID NOT NULL
connector_id UUID NOT NULL
connector_credential_pair_id UUID NOT NULL
connector_item_version_id UUID NOT NULL
status VARCHAR(16) NOT NULL
attempt_number INTEGER NOT NULL
pair_sequence BIGINT NOT NULL
item_sequence BIGINT NOT NULL
available_at TIMESTAMPTZ NOT NULL
claim_token UUID
claimed_by VARCHAR(200)
claimed_at TIMESTAMPTZ
lease_expires_at TIMESTAMPTZ
started_at TIMESTAMPTZ
completed_at TIMESTAMPTZ
error_code VARCHAR(100)
error_detail VARCHAR(500)
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
UNIQUE (tenant_id, id)
UNIQUE (tenant_id, connector_credential_pair_id, connector_item_version_id, attempt_number)
UNIQUE (tenant_id, connector_credential_pair_id, pair_sequence)
UNIQUE (tenant_id, connector_item_version_id, item_sequence)
FK tenant_id -> tenants
FK (tenant_id, connector_credential_pair_id, connector_id) -> connector_credential_pairs
FK (tenant_id, connector_item_version_id, connector_id) -> connector_item_versions
```

pair_sequence is allocated from Pair.next_attempt_sequence and item_sequence from ConnectorItem.next_attempt_sequence while both rows are locked in the global order. Pair status chooses its latest terminal outcome by greatest pair_sequence; ConnectorItem status chooses across Pairs by greatest item_sequence.

Partial indexes support deterministic claims for NOT_STARTED attempts and expired IN_PROGRESS leases. Claim/finalization predicates include claim_token so a late worker cannot commit after another worker reclaims the attempt.
### `documents`

Owned by the document capability:

```text
id UUID PK
tenant_id UUID NOT NULL
stable_key VARCHAR(500) NOT NULL
current_version BIGINT NOT NULL
status VARCHAR(16) NOT NULL
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
UNIQUE (tenant_id, id)
UNIQUE (tenant_id, stable_key)
FK tenant_id -> tenants
```

DocumentStatus is ACTIVE or INELIGIBLE. For MEM-35, stable_key derives from ConnectorItemId. Provider-native deduplication across different Connectors is deliberately absent.
### `document_versions`

```text
id UUID PK
tenant_id UUID NOT NULL
document_id UUID NOT NULL
version BIGINT NOT NULL
source_content_sha256 CHAR(64) NOT NULL
title VARCHAR(500) NOT NULL
normalized_text TEXT NOT NULL
metadata_json JSONB NOT NULL
created_at TIMESTAMPTZ NOT NULL
UNIQUE (tenant_id, id)
UNIQUE (tenant_id, document_id, version)
UNIQUE (tenant_id, document_id, source_content_sha256)
FK tenant_id -> tenants
FK (tenant_id, document_id) -> documents
CHECK source_content_sha256 ~ '^[0-9a-f]{64}$'
CHECK char_length(normalized_text) <= 2000000
```

The document capability locks the Document row before allocating a version. Same-content concurrency is resolved inside DocumentCommandPort through the content-hash uniqueness constraint; full FILE finalization is additionally Pair-serialized.
### `documents_by_connector_credential_pair`

Owned by Connector:

```text
tenant_id UUID NOT NULL
connector_id UUID NOT NULL
connector_credential_pair_id UUID NOT NULL
document_id UUID NOT NULL
connector_item_id UUID NOT NULL
retrieval_eligible BOOLEAN NOT NULL
first_indexed_at TIMESTAMPTZ NOT NULL
last_indexed_at TIMESTAMPTZ NOT NULL
PRIMARY KEY (tenant_id, connector_credential_pair_id, document_id)
FK tenant_id -> tenants
FK (tenant_id, connector_credential_pair_id, connector_id) -> connector_credential_pairs
FK (tenant_id, connector_item_id, connector_id) -> connector_items
FK (tenant_id, document_id) -> documents
```

Indexes support Pair document count/list, item removal, and reverse cleanup by Document. ConnectorItem mutation is Connector-scoped; Pair mappings determine which operational sources expose a Document.

### `connector_cleanup_attempts`

```text
id UUID PK
tenant_id UUID NOT NULL
target_connector_id UUID NOT NULL
target_pair_id UUID
target_item_id UUID
operation VARCHAR(32) NOT NULL
status VARCHAR(16) NOT NULL
claim_token UUID
claimed_by VARCHAR(200)
available_at TIMESTAMPTZ NOT NULL
lease_expires_at TIMESTAMPTZ
completed_at TIMESTAMPTZ
error_code VARCHAR(100)
created_at TIMESTAMPTZ NOT NULL
updated_at TIMESTAMPTZ NOT NULL
FK tenant_id -> tenants
```

Operations are REMOVE_ITEM and DELETE_SOURCE. Target IDs are retained evidence, not foreign keys, because successful cleanup hard-deletes target rows while the result remains queryable. A stable `target_key` plus unique `(tenant_id, operation, target_key)` enforces one cleanup record per target. The command looks up this evidence before locking a possibly deleted target, so retry after a lost response returns the original CleanupAttemptId even when the target is DELETING or already gone.
## Transaction and concurrency flows

### Create FILE Connector

One API transaction:

1. lock and resolve current active Tenant OWNER so deactivation cannot overtake the transaction;
2. lock or insert deterministic Tenant NO_AUTH Credential;
3. insert Connector;
4. insert PUBLIC Pair in NOT_STARTED while holding the Connector row as owner of the Pair set;
5. return Pair-keyed source view.

### Upload file

The API reads at most 10 MiB and computes SHA-256 before opening the write transaction. One transaction:

1. lock and resolve active Tenant OWNER;
2. resolve the selected Pair, then lock Connector followed by Pair;
3. require FILE + NO_AUTH + PUBLIC and Pair not DELETING;
4. insert or resolve ConnectorItem by `(tenant, connector, hash)`;
5. insert or resolve immutable ConnectorItemVersion by content hash;
6. if this Pair already has an attempt/mapping for the version, return that Pair-specific outcome idempotently;
7. otherwise allocate Pair-wide pair_sequence under the Pair lock and insert NOT_STARTED IndexAttempt for the selected Pair + existing/new item version;
8. recompute Pair aggregate status to INDEXING;
9. commit before detection or extraction.

This branch remains correct when a later Connector has several Pairs: ConnectorItem/version deduplication is Connector-scoped, while attempt/mapping idempotency is Pair-scoped.

No parser or media detector runs in the API request. Filename is display metadata only.

### Reindex one item

`POST /api/sources/{sourceId}/items/{itemId}/index-attempts` reindexes that item's current version through the operational Pair represented by sourceId. One transaction locks active Tenant lifecycle, Connector, Pair, item/current version, then:

1. rejects Connector/Pair/item DELETING;
2. returns the existing NOT_STARTED/IN_PROGRESS attempt for the same Pair + current item version when present;
3. otherwise allocates pair_sequence and item_sequence under their owning row locks;
4. inserts one NOT_STARTED attempt and recomputes Pair/item aggregate state;
5. returns that IndexAttemptId idempotently.

MEM-35 has no Pair-wide reindex-all command. Bulk reindex requires its own bounded job contract and is deferred.
### Claim and reclaim work

The scheduler first reads a bounded ordered list of candidate IDs without taking row locks. For each candidate it opens a short transaction, locks the candidate Tenant lifecycle, Connector, and Pair in global order, then attempts `SELECT ... FOR UPDATE SKIP LOCKED` on that exact attempt with the eligible-state predicate. If another worker changed or locked it, the candidate is skipped.

Eligibility is NOT_STARTED with `available_at <= now()` or IN_PROGRESS with expired `lease_expires_at`. Inactive Tenant work becomes CANCELLED while holding the lifecycle lock. Otherwise claim writes a fresh random claim_token, worker identity, claim/start time, and two-minute lease. Reclaim keeps the history row but replaces the token; a late worker cannot finalize.
### Extract outside transaction

The worker loads bounded bytes, closes the database transaction/connection, then starts one bounded child JVM to detect and extract. It never holds row locks or a database connection while Tika parses. The extraction deadline is shorter than the claim lease; timeout or shutdown forcibly terminates the child process, while an abrupt worker/process crash leaves the attempt reclaimable.
### Finalize success
One transaction reads the attempt identity without locking, then locks active Tenant lifecycle, Connector, Pair, attempt, ConnectorItem, current item version, Document, and mappings in the shared stable order. It requires:

1. attempt remains IN_PROGRESS and claim_token matches;
2. owning Tenant remains active;
3. Pair is not DELETING;
4. ConnectorItem is not DELETING and still points to this version;
5. item version is still current and no greater pair_sequence exists for the same Pair + item version.

Attempts for another Pair on the same item version are independent and may create their own mapping. Any attempt for an older item version is obsolete. A later explicit retry/reindex for the same Pair + item version supersedes an older attempt regardless of completion order.

If a deletion/removal guard fails, the attempt becomes CANCELLED and no Document write occurs. Otherwise the transaction:

1. invokes the document public command to lock/create Document and resolve or append one content-hash-unique DocumentVersion;
2. upserts a retrieval-eligible DocumentByConnectorCredentialPair mapping;
3. marks ConnectorItem INDEXED;
4. marks attempt SUCCEEDED;
5. recomputes Pair document_count from live mappings;
6. recomputes Pair status from DELETING, all nonterminal attempts, latest terminal attempt, and live mappings;
7. updates safe last-index/last-success timestamps;
8. commits atomically.
### Finalize failure

One transaction requires the current claim token, records bounded safe error code/detail, marks the attempt FAILED, marks the item FAILED only when this is its latest version/attempt, and recomputes Pair status under the Pair lock. Binary bytes, extracted text, stack traces, sensitive filenames, and provider secrets never enter error fields. A stale token changes nothing.
### Item removal and Pair deletion

Every mutating transaction uses one lock order: Tenant lifecycle, Connector, affected Pair rows sorted by UUID, index/cleanup attempts sorted by UUID, ConnectorItem, item version, Document, then mappings.

Removing an item locks active Tenant lifecycle, Connector, affected Pairs, attempts, item/version, Document, then mappings. If Connector or its only FILE Pair is DELETING, it returns the existing DELETE_SOURCE CleanupAttemptId and creates no child task. Otherwise it marks the item DELETING, invalidates nonterminal claim tokens, marks mappings retrieval-ineligible, recomputes Pair aggregates, marks Document INELIGIBLE, and inserts or resolves one idempotent REMOVE_ITEM cleanup attempt.

Pair deletion uses the same order and additionally locks every nonterminal REMOVE_ITEM cleanup attempt for the Connector. For the final FILE Pair it marks Connector DELETING, changes Pair to DELETING, invalidates index/cleanup claim tokens, marks subordinate item cleanups SUPERSEDED, makes mappings ineligible, recomputes aggregate state, marks Documents with no other live mappings INELIGIBLE, and inserts or resolves one DELETE_SOURCE cleanup attempt. The source-delete command always returns the existing/new CleanupAttemptId.

Claim transactions select a bounded batch with `FOR UPDATE OF attempt SKIP LOCKED` (or the single-table cleanup equivalent), assign a fresh random token per row, and commit before extraction. Execution requires the current token. Lease reclaim replaces the token, so a stale worker cannot finalize. Cleanup remains allowed after Tenant deactivation; missing targets adopted by broader cleanup become SUPERSEDED rather than stranded.

REMOVE_ITEM removes mappings and item attempts, recomputes Pair aggregates, and deletes versions/binary/item. For every removed mapping it hard-deletes unreferenced Document/version content under the Document lock. DELETE_SOURCE performs the same per-mapping check before removing Pair attempts/Pair. If another Pair remains, ConnectorItems survive; if none remains, it removes items then Connector. Concurrent Pair creation requires Connector lock, so future final-Pair decisions serialize. Cleanup cannot create mappings, Documents, or access and remains allowed after Tenant deactivation.
## File extraction boundary

Use Apache Tika 4.0.0 inside the worker, not the API. Pin only `tika-core`, PDF, Microsoft, and text parser modules; detection uses content plus resource metadata and extension never overrides detected type.

Allowlist:

```text
application/pdf
application/vnd.openxmlformats-officedocument.wordprocessingml.document
text/plain; charset=UTF-8
text/markdown; charset=UTF-8
```

Parser configuration disables OCR, macros, recursive embedded attachment extraction, and archive/container expansion. Output is limited to 2,000,000 characters. Every parse runs in a memory-bounded child JVM using a length-bounded binary protocol; the worker forcibly terminates the process at the extraction deadline and during shutdown. Encrypted, malformed, unsupported, timeout, write-limit, child-process, and internal parser failures map to distinct safe IndexAttempt error codes.

Tika configuration is capability-owned and closed; callers receive normalized extraction results or typed failures, never Tika objects.

## Worker runtime

Worker adds JDBC/PostgreSQL runtime dependencies and datasource configuration. Component scanning includes worker scheduling plus core connector/document/ingestion implementations required for processing, but excludes API/security/web composition.

The API remains the Flyway migration owner. Deployment starts worker only after API health proves migrations completed. Worker readiness requires datasource reachability and successful index/cleanup queue initialization. A real worker container uses bounded restart policy, memory/CPU, and the same managed database credential boundary as API.

The worker loop claims index and cleanup work in bounded batches with idle delay and graceful shutdown. Each claim has a two-minute lease and random token. A process crash, OOM kill, or node loss leaves work reclaimable after lease expiry; a late process cannot finalize with its stale token. Extraction deadline remains below the lease, and shutdown forcibly terminates remaining parser children before the deployment grace expires.
## HTTP and UI contract

Initial API surface:

```text
POST   /api/sources/file
GET    /api/sources
GET    /api/sources/{sourceId}
GET    /api/sources/{sourceId}/index-attempts
POST   /api/sources/{sourceId}/items/{itemId}/index-attempts
POST   /api/sources/{sourceId}/delete

POST   /api/sources/{sourceId}/items
GET    /api/sources/{sourceId}/items
POST   /api/sources/{sourceId}/items/{itemId}/remove

GET    /api/source-operations/{operationId}

```

Source is the consumer-facing read model for one operational ConnectorCredentialPair; sourceId carries Pair identity without exposing the persistence association name. Source ID owns navigation, upload execution, status, item views, reindex, and deletion. Internal commands resolve Pair then Connector and retain the documented Connector-wide item semantics. FILE has exactly one Pair in MEM-35.

Upload is one multipart file per request. The synchronous response may fail only for authority, tenant scope, multipart shape, byte-size, idempotency/conflict, or durable write failure. Media detection and extraction failures are asynchronous terminal IndexAttempt/Pair outcomes visible through status APIs and RFC 9457 is not fabricated after the upload response has committed.

List/detail responses expose safe type, status, access, pending-work flag, last success, document count, item/attempt summaries, and safe error code. They never expose configuration secrets, binary bytes, extracted text, claims, or raw parser failures.

Identity projection adds SOURCES_MANAGE only for active Tenant OWNER. Backend authority still resolves durable membership for every command.

Sources UI follows the Onyx interaction model: connector type, fixed NO_AUTH credential context, connector configuration, PUBLIC access, then a Pair-keyed status page/card. It polls Pair detail while indexing. The delete command returns CleanupAttemptId; the UI treats SUCCEEDED and SUPERSEDED as terminal success and FAILED as terminal failure, after which Pair not-found is expected for successful source deletion. No WebSocket/SSE infrastructure is introduced.
## Security and access

All persistence reads and writes include authorized Tenant ID. A missing or foreign Pair, Connector, or item returns a safe not-found outcome without revealing existence. OWNER management authority is separate from PUBLIC read clearance. PRIVATE and SYNC are rejected until their prerequisites exist.

Connector exposes SourceDocumentAccessResolver accepting ActorId and DocumentId. For PUBLIC, it resolves current active Tenant membership and one live retrieval-eligible Pair mapping; it does not require source-management authority. Retrieval has no endpoint in MEM-35, but this public connector contract makes persisted access executable without introducing a connector/document/ingestion cycle.

Multipart handling applies request limits and filename normalization for display only. Detected media type is authoritative: valid allowed bytes are accepted even when the extension is missing or misleading. Unsupported detected type becomes an asynchronous failed IndexAttempt. Request bodies and extracted content are never logged.

Spring Boot manages Jakarta Validation and Hibernate Validator through the platform BOM. Only `:api` depends on `spring-boot-starter-validation`. Request DTOs use `@Valid`; direct query/path constraints use Spring MVC method validation without class-level `@Validated`. `ApiExceptionHandler` remains a narrow advice and handles only business failures plus `MethodArgumentNotValidException` and `HandlerMethodValidationException`; it does not extend `ResponseEntityExceptionHandler` or add catch-all exception mappings. Core retains semantic authority and lifecycle validation.
## Exclusions

- Google OAuth, real Google Credential execution, Drive sync, and permission materialization;
- PRIVATE access, Groups, SCIM, and source group mapping;
- SYNC access and per-document provider ACLs;
- retrieval, embeddings, answers, and citations;
- generic provider SDK/plugin registry beyond concrete FILE and imminent Drive needs;
- actor-owned UserFile library;
- object storage abstraction without an actual object store;
- ZIP, OCR, macros, embedded attachments, executable formats, watched folders, arbitrary paths, bulk upload, and every-file-format support;
- automatic extraction retries beyond expired-lease reclaim, dead-letter queues, outbox, or distributed live updates.
