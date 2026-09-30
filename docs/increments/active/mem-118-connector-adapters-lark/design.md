# Connector adapters and the Lark Suite connector

Status: **connector standardization implemented 2026-09-30 in one pull request ([as delivered](#as-delivered));
document links, the reader hierarchy and Lark Suite remain.** Linear:
[MEM-118](https://linear.app/memory-os/issue/MEM-118), which absorbs MEM-128 (Lark Suite). It applies
[ADR 0019](../../../decisions/0019-provider-families-use-adapters-behind-a-registry.md) to connectors, after the Web,
Voice and Image families in [provider adapter registries](../../completed/provider-adapter-registries/design.md).

## Problem

Lark Suite is the next Source and Tasco's everyday workspace. Before adding it, the connector must be in the shape of
ADR 0019, so a third provider adds classes and touches no engine code. The inventory below (`main` at `58a3243f`)
shows how far the connector already is from that and where it is not.

## Owner decisions (2026-09-30)

1. MEM-118 and MEM-128 are one issue: standardize connectors, then build Lark on the standard.
2. Everything is standardized now, before production; no family is left on the old shape.
3. The connector database is standardized first; Lark Suite waits until that is done and gets its own go-ahead.
4. The standardization is one pull request, not one per step.

## As delivered

One migration, `V134__connector_standardization.sql`, and the code that reads it. The sections under
[Design](#design) are the reasoning; where the delivery differs, this section is what was built.

| Design section | Delivered | Differs from the design |
| --- | --- | --- |
| Connector database: one selection queue | `source_selection_operations` with `google_drive_selection_details` and `sharepoint_selection_details` | `scope_mode` lives in the details tables, because its values are each provider's vocabulary. A request ID is unique per provider, as it was with two tables. A Source holds one pending request whatever its provider; two tables allowed one per provider, which a Source of one type never used. Checkpoint rows reference the details row, so they cannot attach to another provider's request |
| Connector database: one sync state | `source_sync_state`; a provider Source row requires it | As designed |
| Connector database: credential state | `credential_revision` and `payload_revision` on `credentials`; `credentials.status` is the connection status | `auth_method` stays on the provider tables: its values are provider vocabulary and only the secret envelope reads it. SharePoint's `connection_status` is dropped as a duplicate. Google Drive's stays, because its envelope CHECK reads it (a revoked credential keeps no secret) and a CHECK cannot read another table; a foreign key now holds it equal to `credentials.status` |
| 1. One workload | `OperationWorkload.SELECTION_VALIDATION`, one relay and stream; `SourceSelectionAdapterRegistry` | The existing `selection-validation` stream serves every provider, so nothing drains: the SharePoint stream and relay are removed, a request already published there is rediscovered from PostgreSQL after the rediscovery delay, and a message published before the upgrade is discarded as invalid and rediscovered the same way. The old SharePoint stream and its group stay in Redis of an existing deployment until deleted; nothing reads them |
| 2. The sync adapter | `SourceSyncAdapter`, `SourceSyncAdapterRegistry`, `SourceProviderCapabilities.permissionSync`, `resumed` and `itemRemoved` hooks; `ProviderAuthorityService` and `SyncTarget` deleted | `itemRemoved` was not in the inventory: item removal called the Google Drive exclusion for every Source type |
| 3. Permission changes | `SourceAclChanged` | The document link is not delivered here (below) |
| 4. Names | `GoogleDriveGateway`, `SharePointGateway` and their `Rest…Gateway` implementations; `…SyncAdapter`, `…SelectionAdapter`, `BatchedSelectionAdapter` | As designed |

**One property is given up.** Each connector's verification had its own stream, so one connector's backlog could not
delay another's. They now share one queue, delivered oldest first; requests of one credential are still verified one
at a time and a busy one is handed back. Verification is started by a person and is rare, and a stream per provider
would grow the Worker configuration with every connector.

**Still naming a provider, on purpose.** `JdbcSourceRepository.updateAccess` lets `FILE` and Google Drive Sources
change access and not SharePoint; that is a product rule to settle with SharePoint permissions, not a storage
detail. The indexing eligibility rule keeps a Google Drive branch for selection membership.

**Not in this pull request.**

- The document link (`documentUrl`, inventory 7). Returning a SharePoint link changes what readers see and what
  `ChatSource` accepts as a stored link, so it goes with the reader hierarchy and its browser review.
- The reader hierarchy (section 5) and Lark Suite (section 6).

## Inventory

**Already in the shape.** Synchronization is one engine with one class per provider: `SourceSyncEngine` holds an
`EnumMap<SourceType, SyncTraversal>` built from the beans, and `GoogleDriveSyncTraversal` and
`SharePointSyncTraversal` implement `SyncTraversal` (`due`, `credentialRevision`, `credentialCurrent`, `walk`,
`classify`, `authenticationFailed`, `ended`). Selection verification has one contract, `SourceSelectionProcessor`,
one shared base (`SelectionBatchProcessor`) and one shared persistence helper (`SelectionOperations`, given its table
name). Indexing and cleanup (`ConnectorIndexingPort`, `ConnectorCleanupPort`) never name a provider. The first
version of this design assumed two unrelated stacks and six new function interfaces; the code does not need them.

**Where the engine still names a provider.**

| # | Site | What it does | Cost of a third provider today |
| --- | --- | --- | --- |
| 1 | `ingestion.OperationWorkload`: `GOOGLE_DRIVE_SELECTION_VALIDATION`, `SHAREPOINT_SELECTION_VALIDATION`; `SelectionValidationProcessor` (`switch`), `DefaultIngestionCoordinator`, `JdbcOperationDispatchRepository.table`/`candidateSql`; Worker `ControlPlaneConfiguration` (one relay task each), `RedisExecutionProperties`, `application.yaml` (one Redis stream and group each), `WorkerConfiguration` | One workload, relay task, Redis stream and candidate query **per provider** for the same kind of work | A new enum constant, stream, relay task, property block, candidate SQL and `switch` arm |
| 2 | `connector.sync.ProviderAuthorityService` | `switch (SourceType)` to ask whether a credential is current, the question `SyncTraversal.credentialCurrent` already answers | A new `case` and constructor argument |
| 3 | `connector.sync.persistence.SyncTarget` (enum of provider table names) | Names each provider's Source and credential tables for the engine's SQL | A new constant in an engine enum |
| 4 | `SourceSyncEngine`'s map | No startup check: a `SourceType` without a traversal fails at the first run | Silent until a Source is due |
| 5 | `connector.source.SourceAccessPolicy.access` | `type == GOOGLE_DRIVE` decides that only Drive offers Auto Sync (provider permissions) and defaults to it | Lark syncs permissions too (MEM-128): another `==` |
| 6 | `DefaultSourceManagementService` (resume) | `type() == GOOGLE_DRIVE` re-queues a resumed Drive Source through Drive's own service | SharePoint and Lark resume differently or not at all |
| 7 | `connector.DocumentSourceMetadata.providerUrl` | Builds the citation deep link only for Drive file IDs | No deep link for other providers |
| 8 | `ingestion.SearchProjectionMaintenance` listens to `GoogleDriveAclChanged` | Re-projects chunk ACLs when Drive permissions change | A second event type and listener |
| 9 | Gateways `GoogleDriveProvider`, `SharePointProvider` (`connector` root, implemented in `sources`) | The HTTP seam to each provider | Named `…Provider`, which the naming convention keeps for the vendor enum |

**Per provider by nature, and staying so.** Credentials, scope selection and their forms: the root APIs
(`GoogleDriveSourceService`, `SharePointSourceService`, credential and authorization services), their controllers
(`GoogleDrive*Controller`, `SharePoint*Controller`), the web screens under `features/sources/<provider>`, and the
provider state tables (17 `google_*`, 9 `sharepoint_*`). A Drive selection (folders, files, linked documents) and a
SharePoint scope (sites, libraries, exclusions) are different product surfaces, as Onyx's per-connector forms are.

**Not there at all.** A Source's folder hierarchy for readers (Tasco's request of 2026-09-24): Drive has a selection
tree for administrators, SharePoint has roots; nothing neutral exists.

## Reference

Onyx at `40eb240df`, `backend/onyx/connectors/`: `interfaces.py` splits a connector into function interfaces a class
implements as it can (`CheckpointedConnector`, `SlimConnectorWithPermSync`, `HierarchyConnector`, `OAuthConnector`);
`registry.py` maps `DocumentSource` to the connector class and `factory.py` validates what it supports.

| | |
| --- | --- |
| Requirement | Owner decisions above; Lark is the third provider; Tasco needs each Source's folder hierarchy shown |
| Reference strengths | The engine calls functions, never providers; a new connector adds classes and one registry entry |
| Proposed difference | MemoryOS keeps its own engine, durable operations, leases and PostgreSQL authority, which already give resumable runs (Drive's frontier, SharePoint's delta link); only the remaining provider-named sites change |
| Benefit | Lark, and every later Source, touches no engine code |
| Costs | One data-preserving migration and a Redis stream change for selection verification; renames in `connector`, `ingestion`, `worker` and `sources` |
| Simpler baseline | Add Lark with a third `case`, workload and stream. Rejected by the owner decisions |

## Design

### Connector database

MemoryOS already has the provider-neutral layer Onyx has: `connectors`, `credentials`, `connector_credential_pairs`,
`index_attempts`, `source_sync_attempts` and `connector_cleanup_attempts` name no provider. The 17 `google_*` and
9 `sharepoint_*` tables extend it. Comparing the current columns of each pair (V1 to V133) shows three places where
the two providers keep the same data under two names, and the rest is genuinely different:

| Pair | Same in both | Only one provider | Decision |
| --- | --- | --- | --- |
| `*_selection_operations` | 37 of 41 columns: identity, request, bounds, progress and the whole queue | Drive: discovery revision and counters. SharePoint: include flags and intervals | **Merge** into `source_selection_operations` with a details table per provider (section 1) |
| `*_sources` | The sync state the engine reads and writes: scope revision (`revision` / `scope_revision`), `generation`, `schedule_revision`, `sync_interval_minutes`, `next_sync_at`, `last_synced_at`, `sync_paused` | Drive: discovery state. SharePoint: include flags, prune schedule, refresh window, tenant host. `scope_mode` has different values in each | **Split**: the shared sync state moves to one `source_sync_state` table; each provider table keeps its own configuration |
| `*_credentials` | `connection_status`, `credential_revision`, `payload_revision`, `auth_method` | Every secret: Drive refresh token, OAuth client and service account key; SharePoint client secret, private key and certificate | **Split**: the shared credential state moves onto the neutral `credentials` table; each provider table keeps its secrets |
| `*_roots`, `*_selection_entries` | 2 to 4 key columns | Everything that describes a scope: Drive files and folders, SharePoint sites, libraries and items | Stay per provider |
| ACL snapshots, frontier, membership, linked documents, group sync, items, exclusions, sync runs | — | Protocol state of one provider | Stay per provider |

Why the two splits matter: `JdbcSourceSyncRepository` builds ten SQL statements by splicing a provider's Source
table, scope revision column and credential table from the `SyncTarget` enum. With `source_sync_state` and the
credential state on `credentials`, that SQL names fixed tables, `SyncTarget` disappears, and a new provider adds only
the tables that hold its own configuration, secrets and protocol state.

Each change is one migration that preserves data ([ADR 0018](../../../decisions/0018-schema-changes-preserve-data.md)):
create or extend the neutral table, copy the columns from both provider tables, repoint the constraints and triggers
that read them, then drop the moved columns. The API does not change: the columns move between tables, and the
repositories that read them return the same values.

Not proposed: folding the provider tables into JSON columns on the neutral tables, as Onyx's
`connector_specific_config` does. MemoryOS's provider state is relational (frontier pages, ancestors, ACL snapshots,
sync runs with foreign keys and triggers), and typed columns keep the database's checks.

### 1. One selection-verification workload (inventory 1)

The largest gap. Selection verification is the same work for every provider, so it becomes one workload:

- `OperationWorkload.SELECTION_VALIDATION` replaces the two per-provider constants: one Redis stream and group, one
  relay task, one property block.
- `SourceSelectionProcessor` gains `SourceType type()`, and `SelectionValidationProcessor` takes the processors as a
  registry keyed by `SourceType` (every type except `FILE`, checked at startup) instead of two constructor arguments
  and a `switch`.
- **One queue table (owner decision 2026-09-30).** `google_drive_selection_operations` (V30, V47, V77) and
  `sharepoint_selection_operations` (V81) are the same table but for a few columns, so they merge, as Onyx keeps one
  `connector` table with a `source` column. Rejected: keeping both and having the relay union every registered table,
  which builds SQL from adapter-supplied names and makes a delivery probe each processor for its owner.
  - **`source_selection_operations`** holds every column the two share, plus `source_type`: identity (`id`,
    `tenant_id`, `source_id`, `actor_id`, `request_id`, `request_hash`), the request (`credential_id`,
    `credential_revision`, `scope_revision`, `scope_mode`, `source_name`, `access_type`, `group_ids`), its bounds and
    progress (`max_requests`, `max_roots`, `max_request_bytes`, `request_count`, `elapsed_millis`), and the whole
    queue (status, claim, lease, dispatch, attempts, error, origin trace, timestamps). Its indexes are the ones both
    tables have: one live operation per `(tenant_id, source_id)`, the dispatch index and the credential index.
    `scope_mode` is checked per `source_type`.
  - **A details table per provider**, keyed by the operation id with `ON DELETE CASCADE`, holds what only that
    provider has: `google_drive_selection_details` (`discovery_revision`, `max_metadata`, `ancestor_count`,
    `metadata_count`) and `sharepoint_selection_details` (`include_documents`, `include_pages`,
    `sync_interval_minutes`, `prune_interval_hours`). Lark adds its own and nothing else.
  - **One migration, data preserved** ([ADR 0018](../../../decisions/0018-schema-changes-preserve-data.md)): create
    the header and details tables, copy every row of both old tables, repoint the child tables' foreign keys
    (Drive selection entries, ancestors and metadata; SharePoint selection entries), replace the ten per-provider
    trigger functions (cancel on a deleted or changed credential, a deleting Source, a revoked membership, an
    inactive Tenant) with provider-neutral ones on the header, keep Drive's checkpoint compaction, then drop the old
    tables. Operation ids are UUIDs, so the two tables' rows do not collide.
  - **The API does not change.** The operation type a client sees (`VALIDATE_GOOGLE_DRIVE_SELECTION`,
    `VALIDATE_SHAREPOINT_SELECTION`) is derived from `source_type`, so `openapi.yml` stays as it is.
  - `SelectionOperations` drops its table-name argument; `JdbcSourceOperationQueryRepository` reads one table instead
    of a union per provider; `JdbcOperationDispatchRepository` has one candidate query.
- The old streams drain before removal: the relay stops publishing to them in the release that starts the new
  stream, and their consumers go in the following one. Operations are PostgreSQL-authoritative, so an undelivered one
  is relayed again on the new stream.

### 2. The sync adapter carries what the engine asks a provider (inventory 2 to 6)

`SyncTraversal` is already the provider's sync adapter. It is renamed `SourceSyncAdapter` and gains the questions the
engine still answers by `switch` or `==`:

- `ProviderAuthorityService` is removed; its callers ask the registry for `credentialCurrent` (`FILE` answers false).
- `SyncTarget` is deleted once the [connector database](#connector-database) is standardized: the engine then reads
  neutral tables and splices no provider table name.
- `SourceSyncAdapterRegistry` replaces the engine's private map and fails startup when a `SourceType` other than
  `FILE` has no adapter or has two.
- `SourceCapabilities` from the adapter: `permissionSync` (today only Drive) replaces `type == GOOGLE_DRIVE` in
  `SourceAccessPolicy`, and `resume(...)` on the adapter replaces the Drive-only branch in
  `DefaultSourceManagementService`.

### 3. Deep links and permission changes (inventory 7 and 8)

- `SourceSyncAdapter.documentUrl(providerFileId)` (nullable) replaces the Drive-only rule in
  `DocumentSourceMetadata.providerUrl`, so SharePoint and Lark citations get a deep link through the same path.
- `GoogleDriveAclChanged` becomes the provider-neutral `SourceAclChanged`; Drive publishes it as today, and
  `SearchProjectionMaintenance` listens to the one event.

### 4. Names (inventory 9)

`GoogleDriveProvider` and `SharePointProvider` become `GoogleDriveGateway` and `SharePointGateway` (as
`IdentityProviderGateway`), with `RestGoogleDriveGateway` and `RestSharePointGateway` in `sources`. The per-provider
root services keep their names. Vendor spelling: `GoogleDrive`, `SharePoint`, `Lark` (Feishu, if ever needed, is the
same protocol on another domain and a configuration value, not a class name).

**Where the code lives.** Traversal, selection, ACL and provider state are `core` classes in `connector.googledrive`
and `connector.sharepoint`, each with its `persistence`; `sources` holds only the HTTP gateways and extractors
([ADR 0016](../../../decisions/0016-integration-bundle-named-sources.md)) and never imports `persistence`. The
adapters therefore stay where they are, and Lark's go in `connector.lark`. `ingestion` already depends on
`connector`, so no module dependency changes and no ADR is expected.

### 5. Hierarchy for readers

`SourceHierarchyAdapter` (a provider implements it when it has folders): children of a node a reader may see, from
the provider state MemoryOS already stores (Drive ancestors, SharePoint items), never a live provider call. One
endpoint under the Sources API and the tree on the library's Sources view. This is a product feature with UI; its
screens need the owner's review before merge.

### 6. Lark Suite (deferred)

Waits until the steps above are delivered and the owner gives a separate go-ahead. Built on the shape above, scope
from MEM-128. Lark adds `LarkGateway` and `RestLarkGateway` (`sources`),
`connector.lark` with its `SourceSyncAdapter`, `SourceSelectionProcessor`, credential and scope services and
`lark_*` tables, one `SourceType` constant and its CHECK, its controllers and web screens, and no engine change.

1. Lark Drive files through the existing readers and OCR.
2. Lark Docs and Sheets read natively, tables as structured artifacts as Google Docs and Sheets are.
3. Lark Wiki spaces and nodes.
4. Out of the first slice: Messenger, Base, Calendar.

Requirements: Tenant credential stored encrypted as Google Drive's is; scope selection; first and incremental sync
with delete, rename and move; Lark Open API rate limits; permission sync into chunk ACLs (`permissionSync`); deep
links in citations; sync history and errors on the Sources page; the folder hierarchy.

Open, from MEM-128: Lark international or Feishu, plan tier, whether an internal (tenant) app is allowed or OAuth per
user, which Lark spaces hold the demo data, and the customer's approval of the integration flow.

### Out of scope

The other Sources MEM-118 listed (OneDrive, Teams, Confluence, Jira, Slack, Notion, Gmail/Outlook) get their own
issues and build on this shape.

## Verification

- Registry tests: every `SourceType` except `FILE` has exactly one sync adapter and one selection processor, or
  startup fails.
- The Google Drive and SharePoint sync, selection, ACL and cleanup tests pass unchanged after steps 1 to 4; they are
  the proof the refactor preserved behavior. The migration test covers both operation tables with rows in every
  status.
- `ModulithArchitectureTest`, `CoreDependencyRulesTest`, `SourcesDependencyRulesTest`, `OpenApiContractTest`; the
  Worker is started and a selection verification is observed on the new stream.
- Hierarchy: browser evidence and owner approval.
- Lark: adapter tests against recorded Lark Open API responses.
