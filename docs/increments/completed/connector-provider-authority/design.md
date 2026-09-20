# Connector provider authority

## Context

MemoryOS already shares discovery, extraction, indexing, run history and pruning across the implemented `FILE`, `GOOGLE_DRIVE` and `SHAREPOINT` source types. The indexing persistence path, however, still asks `GoogleDriveConnectionService` whether every provider-backed version is current. A SharePoint version therefore becomes stale even when its SharePoint credential and scope are valid. A terminal supersede also leaves the Source aggregate in `INDEXING` because its status is not recomputed.

Onyx uses shared connector contracts and a common indexing lifecycle, while its connector factory selects an explicit provider implementation. MemoryOS will apply the same boundary to its existing connectors without importing Onyx's Python runtime or predeclaring unsupported providers.

## Scope

- Route provider-backed indexing authority by the persisted `SourceType`.
- Keep credential and scope-current checks inside the owning Google Drive and SharePoint services.
- Make the common source lock cover the credential attached to any implemented connector, not only Google OAuth credentials.
- Recompute aggregate Source status when an indexing attempt becomes terminally superseded.
- Add regression coverage for SharePoint replay/currentness and terminal supersede status.

## Design

### Explicit provider authority registry

A small application service maps each implemented provider type to its existing connection service. The mapping is intentionally explicit: adding a provider requires adding its source model, connection authority and registry branch. `FILE` remains outside provider-credential validation because uploaded file versions have no provider file identity.

The common indexing repository reads the connector type and provider-specific scope eligibility in the same locked transaction, then delegates credential revision validation through the registry:

- Google Drive requires the captured source scope revision and eligible, non-excluded membership.
- SharePoint requires the captured SharePoint scope revision.
- Both require the credential revision captured by the version to remain usable and current.

Unknown or mismatched provider state fails closed.

### Locking and lifecycle

The generic Source lock first locks the credential referenced by the Source pair, regardless of credential kind, and then locks the Source pair. Provider services may re-read or re-lock their provider row within the same transaction to verify currentness.

When a claimed indexing attempt is no longer current, the attempt becomes `SUPERSEDED`. The repository then recomputes the Source aggregate from all attempts so the UI does not remain indefinitely in `INDEXING` after all work is terminal.

## Out of scope

- Adding connector types beyond `FILE`, `GOOGLE_DRIVE` and `SHAREPOINT`.
- Changing extraction, OCR or malformed-document policy.
- Changing SharePoint permissions or the current `PUBLIC`/`RESTRICTED` access model.
- Schema changes or temporary operational endpoints.

## Acceptance

- A current SharePoint version can be replayed and can reach the normal publication path.
- A stale SharePoint credential or scope fails currentness checks.
- Google Drive authority behavior remains unchanged.
- Terminal supersede no longer leaves the Source aggregate in `INDEXING`.
- Focused connector tests and the repository verification gate pass.
