# Implementation plan

Builds on [ADR 0019](../../../decisions/0019-provider-families-use-adapters-behind-a-registry.md) and the delivered
[provider adapter registries](../../completed/provider-adapter-registries/plan.md). Steps 1 to 5 preserve behavior
and are one pull request (owner decision 2026-09-30); what was built is in the design's
[as delivered](design.md#as-delivered). Lark Suite is deferred.

## 0. Scope and inventory

- [x] MEM-118 absorbs MEM-128; Todo, assigned to the owner.
- [x] Inventory of every place the engine names a provider ([design](design.md#inventory)), 2026-09-30.
- [x] Owner decision 2026-09-30: the selection-verification queue merges into one `source_selection_operations`
  table with a details table per provider.
- [x] Comparison of every provider table pair ([design](design.md#connector-database)), 2026-09-30.
- [x] Owner decision 2026-09-30: steps 1 to 5 are one pull request.

## 1. One selection queue table

- [x] `V134`: `source_selection_operations`, `google_drive_selection_details` and
  `sharepoint_selection_details`; every row of both old tables copied; the checkpoint tables reference the details
  tables; the ten per-provider trigger functions replaced by four provider-neutral ones; the old tables dropped.
- [x] `SelectionOperations` serves one `SourceType` of the shared table; the Drive and SharePoint selection
  repositories write the header and their details row; `JdbcSourceOperationQueryRepository` reads one table and
  derives the operation type from `source_type`.
- [x] `ConnectorStandardizationMigrationTest`: rows of both providers in every status with their checkpoint rows;
  credential changed, unusable or deleted, Source deleting or deleted, membership revoked and Tenant inactive
  re-verified.

## 2. One sync state table

- [x] `V134`: `source_sync_state`; rows copied from `google_drive_sources` and `sharepoint_sources`; the moved
  columns dropped; a provider Source row requires its state row.
- [x] `JdbcSourceSyncRepository`, the indexing and dispatch eligibility queries and the Drive and SharePoint source
  repositories read and write the neutral table.

## 3. Credential state on `credentials`

- [x] `V134`: `credential_revision` and `payload_revision` on `credentials`; `credentials.status` reconciled with the
  provider copies; SharePoint's copy dropped; Google Drive's copy held equal by a foreign key; the credential fence
  moved to `credentials`.
- [x] Repositories read and write the state on `credentials`; `SyncTarget` deleted.

## 4. Registries, one workload and names

- [x] `SourceSyncAdapter` and `SourceSyncAdapterRegistry`; `ProviderAuthorityService` removed.
- [x] `SourceProviderCapabilities.permissionSync` replaces `type == GOOGLE_DRIVE` in `SourceAccessPolicy`; `resumed`
  and `itemRemoved` on the adapter replace the Drive-only calls in `DefaultSourceManagementService`.
- [x] `OperationWorkload.SELECTION_VALIDATION`; `SourceSelectionAdapter` and `SourceSelectionAdapterRegistry`; one
  candidate query; one relay task, stream and group.
- [x] `GoogleDriveGateway`, `SharePointGateway` and their `sources` implementations.
- [x] `SourceAdapterRegistryTest`; the connector, ingestion and boundary tests.
- [x] The shared workload exercised through the real dispatch and processor: `SourceApiIntegrationTest` claims
  `SELECTION_VALIDATION` and runs a Google Drive verification through `SelectionValidationProcessor`;
  `PostgresSharePointSelectionTest` runs the SharePoint adapter on the shared table; `ControlPlaneIntegrationTest` and
  `RedisExecutionTopologyIntegrationTest` cover the relay task and the stream group.
- [ ] A deployed Worker observed verifying a request of each provider on the shared stream. Not run for this pull
  request: the development machine could not hold the Worker and its services in memory; CI is the runtime gate.

## 5. Permission-change event

- [x] `SourceAclChanged` replaces `GoogleDriveAclChanged`.

## 6. Document links and hierarchy for readers

- [x] `documentUrl(providerFileId, sourceUrl)` on the adapter replaces the Drive-only rule in
  `DocumentSourceMetadata` and the Drive-only link check in `ChatSource`; SharePoint returns the recorded `webUrl`
  on a SharePoint host; the library uses the same link (owner decision 2026-10-01: before the hierarchy).
- [x] Access changes follow the creation rule for every provider; SharePoint may change between Public and Private.
- [ ] `SourceHierarchyAdapter` for Google Drive and SharePoint from stored provider state; endpoint under the
  Sources API; contract regenerated.
- [ ] The tree on the library's Sources view; browser evidence and owner approval before merge.

## 7. Consolidate

- [x] `ARCHITECTURE.md`, the connector and ingestion specs and test matrices describe the neutral tables and the
  registries.
- [ ] Reconcile the roadmap; this increment stays active for steps 6 and Lark or is closed and they get their own.

## Deferred: Lark Suite

Needs a separate owner go-ahead and the open questions closed (edition, app type, demo spaces, customer approval).

- [ ] `LarkGateway`/`RestLarkGateway`; `connector.lark`: credential, scope selection, sync adapter (Drive, Docs,
  Sheets, Wiki), permissions, hierarchy; `lark_*` tables and the `SourceType` CHECK.
- [ ] Controllers, contract and web screens following the Google Drive pattern.
- [ ] Adapter tests on recorded responses; `clean check`; `pnpm --dir web check`.
