# Implementation plan

Builds on [ADR 0019](../../../decisions/0019-provider-families-use-adapters-behind-a-registry.md) and the delivered
[provider adapter registries](../../completed/provider-adapter-registries/plan.md). Steps 1 to 3 preserve behavior;
each step is its own pull request.

## 0. Scope and inventory

- [x] MEM-118 absorbs MEM-128; Todo, assigned to the owner.
- [x] Inventory of every place the engine names a provider ([design](design.md#inventory)), 2026-09-30.
- [x] Owner decision 2026-09-30: the selection-verification queue merges into one `source_selection_operations`
  table with a details table per provider.
- [ ] Owner review of [design](design.md).

## 1. Sync adapter registry and names (no schema change)

- [ ] `SyncTraversal` becomes `SourceSyncAdapter`; `SourceSyncAdapterRegistry` replaces the engine's map and fails
  startup on a missing or duplicate adapter.
- [ ] `ProviderAuthorityService` removed; callers use the registry.
- [ ] `SyncTarget` becomes a record each adapter returns.
- [ ] `SourceCapabilities.permissionSync` replaces `type == GOOGLE_DRIVE` in `SourceAccessPolicy`; `resume` on the
  adapter replaces the Drive-only branch in `DefaultSourceManagementService`.
- [ ] `GoogleDriveProvider`/`SharePointProvider` become `…Gateway`, with their `sources` implementations.
- [ ] Registry test; existing connector tests unchanged; `clean check`.

## 2. Deep links and permission-change event

- [ ] `documentUrl` on the adapter replaces the Drive-only rule in `DocumentSourceMetadata`; first confirm what
  SharePoint stores for a document's web URL and return it when present.
- [ ] `SourceAclChanged` replaces `GoogleDriveAclChanged`.
- [ ] Citation and projection tests; `clean check`.

## 3. One selection-verification workload

Two pull requests, so the schema change is reviewed on its own.

**3a. One table (schema and persistence; workloads unchanged)**

- [ ] Migration: create `source_selection_operations`, `google_drive_selection_details` and
  `sharepoint_selection_details`; copy every row of both old tables; repoint the child tables' foreign keys;
  replace the per-provider trigger functions with provider-neutral ones; drop the old tables.
- [ ] `SelectionOperations` without a table name; the Drive and SharePoint selection repositories read and write the
  header and their details table; `JdbcSourceOperationQueryRepository` reads one table and derives the operation
  type from `source_type`.
- [ ] `JdbcOperationDispatchRepository`: both existing workloads select from the one table by `source_type`, so the
  streams and the Worker are untouched in this pull request.
- [ ] Migration test on a database with rows of both providers in every status and with child rows; the trigger
  behaviors (credential deleted or changed, Source deleting, membership revoked, Tenant inactive) re-verified;
  existing selection tests unchanged; `OpenApiContractTest` unchanged; `clean check`.

**3b. One workload**

- [ ] `OperationWorkload.SELECTION_VALIDATION`; `SourceSelectionProcessor.type()`; processors in a registry keyed by
  `SourceType`, complete except `FILE`; one candidate query.
- [ ] Worker: one relay task, stream, group and property block. The old streams drain in this release and their
  consumers are removed in the next; an undelivered operation is relayed again from PostgreSQL.
- [ ] Worker started and a verification of each provider observed on the new stream; `clean check`.

## 4. Hierarchy for readers

- [ ] `SourceHierarchyAdapter` for Google Drive and SharePoint from stored provider state; endpoint under the
  Sources API; contract regenerated.
- [ ] The tree on the library's Sources view; browser evidence and owner approval before merge.

## 5. Lark Suite

- [ ] Close the open questions (edition, app type, demo spaces, customer approval).
- [ ] `LarkGateway`/`RestLarkGateway`; `connector.lark`: credential, scope selection, sync adapter (Drive, Docs,
  Sheets, Wiki), permissions, hierarchy; `lark_*` tables and the `SourceType` CHECK.
- [ ] Controllers, contract and web screens following the Google Drive pattern.
- [ ] Adapter tests on recorded responses; `clean check`; `pnpm --dir web check`.

## 6. Consolidate

- [ ] `ARCHITECTURE.md`, the connector and ingestion specs and test matrices describe the registries and Lark.
- [ ] After merge, move this increment to `completed/` and reconcile the roadmap.
