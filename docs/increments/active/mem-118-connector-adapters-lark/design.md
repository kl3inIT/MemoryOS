# Connector adapters and the Lark Suite connector

Status: **proposed 2026-09-30; not started.** Linear: [MEM-118](https://linear.app/memory-os/issue/MEM-118), which
absorbs MEM-128 (Lark Suite). It applies the
[provider family convention](../../../conventions.md#change-design) to connectors, after the Web, Voice and Image
families in [provider adapter registries](../../completed/provider-adapter-registries/design.md).

## Problem

The Source lifecycle is already provider-neutral: Sources, operations, runs, leases and the `ConnectorSyncPort`,
`ConnectorIndexingPort` and `ConnectorCleanupPort`. What is not neutral is how that lifecycle reaches a provider:

- The engine chooses a provider with `switch` or `==` on `SourceType` or on per-provider operation kinds:
  `ProviderAuthorityService`, `SelectionValidationProcessor`, `DefaultIngestionCoordinator`,
  `JdbcOperationDispatchRepository` (which also picks per-provider table names), `SourceAccessPolicy`,
  `DefaultSourceManagementService` and `DocumentSourceMetadata`.
- Google Drive and SharePoint are two parallel vertical stacks: gateway (`GoogleDriveProvider`,
  `SharePointProvider`), source service, selection processor, credential service, sync traversal, persistence and
  controllers, each named its own way.
- The two provider gateways are named `…Provider`, which the naming convention now reserves for the vendor enum.

Lark Suite is the next Source, and Tasco's everyday workspace. Added today it would be a third copy of every `switch`
and a third naming scheme. The product is not yet in real production, so this is the cheapest moment to fix the
shape.

## Owner decisions (2026-09-30)

1. MEM-118 and MEM-128 are one issue: standardize connectors, then build Lark on the standard.
2. Everything is standardized now, before production; no family is left on the old shape.

## Reference

Onyx at `40eb240df`, `backend/onyx/connectors/`:

- `interfaces.py` splits a connector into function interfaces a class implements as it can: `LoadConnector` (full
  load), `PollConnector` (changes in a time window), `CheckpointedConnector` (resumable), `SlimConnector` and
  `SlimConnectorWithPermSync` (ids and permissions only, for pruning and ACL sync), `OAuthConnector`,
  `CredentialsConnector`, `HierarchyConnector` (folder tree), `EventConnector`.
- `registry.py` maps `DocumentSource` to the connector class; `factory.py` instantiates it and validates that the
  class supports the requested input type.
- `capabilities.py` and `source_operations.py` declare per-source capabilities and operations.

| | |
| --- | --- |
| Requirement | Owner decisions above; Lark is the third provider; Tasco needs each Source's folder hierarchy shown |
| Reference strengths | The engine calls functions, never providers; a new connector adds classes and one registry entry |
| Proposed difference | Java interfaces and Spring beans keyed by `SourceType`, checked complete at startup, instead of lazy import by module path; MemoryOS's durable operations, leases and PostgreSQL authority stay as they are |
| Benefit | Lark, and every later Source, touches no engine code; one vocabulary across providers |
| Costs | A large rename and reshaping inside `core`'s `connector` and `ingestion` and a gateway rename in `sources`; the API and web client regenerate if a contract name changes |
| Simpler baseline | Add Lark with a third `case` everywhere. Rejected by the owner decisions |

## Design

### Function interfaces the engine calls

Each is an interface in `connector`'s root API, collected into a `<Function>AdapterRegistry` keyed by `SourceType`.
The Onyx counterpart is named for orientation.

**Where the code lives.** Traversal, selection, ACL and provider state are in `core` today
(`connector.googledrive`, `connector.sharepoint`, each with its `persistence` package), and `sources` holds only the
HTTP gateways and extractors ([ADR 0016](../../../decisions/0016-integration-bundle-named-sources.md); `sources`
never imports `persistence`). So the adapters that implement these interfaces are `core` classes in the existing
feature packages, `connector.googledrive.GoogleDriveSourceAdapter`, `connector.sharepoint.SharePointSourceAdapter`
and later `connector.lark.LarkSourceAdapter`, next to the persistence they use. This is the one deliberate
exception to the convention's `adapter` subpackage: `io.memoryos.connector.adapter.*` already belongs to `sources`,
and one package must not be split across two Gradle modules. `sources` keeps the gateway implementations
(`RestGoogleDriveProvider` becomes `RestGoogleDriveGateway`, and so on, plus `RestLarkGateway`). `ingestion` already
depends on `connector`, so no module dependency changes.

| Interface | What the engine asks | Onyx | Registry |
| --- | --- | --- | --- |
| `SourceAuthorityAdapter` | Is the stored credential current and allowed | `validate_connector_settings` | Complete |
| `SourceSelectionAdapter` | Validate and resolve a scope selection | `normalize_url`, settings validation | Complete |
| `SourceSyncAdapter` | Next page of changes from a checkpoint | `CheckpointedConnector`, `PollConnector` | Complete |
| `SourceContentAdapter` | Bytes and metadata of one document for extraction | raw file callback | Complete |
| `SourcePermissionAdapter` | Who may read a document, for chunk ACLs | `SlimConnectorWithPermSync` | Partial |
| `SourceHierarchyAdapter` | Folder tree of the Source, for the Sources page | `HierarchyConnector` | Partial |

**Checkpoint.** `SourceSyncAdapter` is resumable, as Onyx's `CheckpointedConnector` is: the engine passes the last
checkpoint and receives a page of changes plus the next checkpoint, and persists it inside the operation's lease.
Each provider declares its checkpoint as a record (Google Drive's traversal frontier, SharePoint's delta link,
Lark's page token and last modified time), so a crash or lease loss resumes at the last committed page rather than
restarting the Source. Step 1 maps today's frontier and delta state onto these records.

"Complete" covers every provider except `FILE`, which has no remote side; the registry declares that exclusion.

### What stays per provider

- **Configuration and scope forms.** A Drive selection (folders, files, linked documents) and a SharePoint scope
  (sites, libraries, exclusions) are different product surfaces, as Onyx's per-connector forms are. Their services,
  controllers and tables stay per provider, renamed to the convention.
- **Provider state tables** (`google_drive_*`, `sharepoint_*`, later `lark_*`) stay per provider; the adapter owns
  them through its own `persistence` package. `JdbcOperationDispatchRepository` stops choosing tables by provider
  and asks the adapter.
- Whether any per-provider table (selection operations, credentials) should merge into a neutral one is decided in
  step 1 from the inventory, with a data-preserving migration ([ADR 0018](../../../decisions/0018-schema-changes-preserve-data.md)).

### Names

| Today | After |
| --- | --- |
| `GoogleDriveProvider`, `SharePointProvider` (gateway interfaces) | `GoogleDriveGateway`, `SharePointGateway`, as `IdentityProviderGateway` |
| Engine `switch` on `SourceType` | `SourceSyncAdapterRegistry.require(type)` and the other registries |
| New | `GoogleDriveSourceAdapter`, `SharePointSourceAdapter`, `LarkSourceAdapter` (each implements the functions it offers; `<Vendor><Family>Adapter`) |

Vendor spelling: `GoogleDrive`, `SharePoint`, `Lark` (the international Lark Suite; Feishu, if ever needed, is the
same protocol on another domain and a configuration value, not a class name).

### Lark Suite

Built on the adapters above, scope from MEM-128:

1. Lark Drive files through the existing readers and OCR.
2. Lark Docs and Sheets read natively, tables as structured artifacts as Google Docs and Sheets are.
3. Lark Wiki spaces and nodes.
4. Out of the first slice: Messenger, Base, Calendar.

Requirements: Tenant credential stored encrypted as Google Drive's is; scope selection; first and incremental sync
with delete, rename and move; Lark Open API rate limits; permission sync into chunk ACLs; deep links in citations;
sync history and errors on the Sources page; the folder hierarchy through `SourceHierarchyAdapter`.

Open, from MEM-128: Lark international or Feishu, plan tier, whether an internal (tenant) app is allowed or OAuth per
user, which Lark spaces hold the demo data, and the customer's approval of the integration flow.

### Out of scope

The other Sources MEM-118 listed (OneDrive, Teams, Confluence, Jira, Slack, Notion, Gmail/Outlook) get their own
issues and build on these adapters.

## Verification

- Registry tests: completeness and duplicates per function; `FILE` excluded where declared.
- The Google Drive and SharePoint sync, selection, ACL and cleanup tests pass unchanged after step 2; they are the
  proof the refactor preserved behavior.
- `ModulithArchitectureTest`, `CoreDependencyRulesTest`, `SourcesDependencyRulesTest`, `OpenApiContractTest`.
- Lark: adapter tests against recorded Lark Open API responses, then an owner-run acceptance on the customer's
  tenant.

## ADR

Step 2 replaces provider dispatch in `connector` and `ingestion` with registries inside `core`; no allowed module
dependency changes, so no ADR is expected unless the inventory in step 1 finds one. [ADR 0019](../../../decisions/0019-provider-families-use-adapters-behind-a-registry.md) covers the pattern itself.
