# Connector capability contract

## Ownership

Connector owns Tenant-scoped Connector, NO_AUTH Credential, ConnectorCredentialPair, ConnectorItem/version, index/cleanup operation association, and Pair-to-Document provenance. The public product read model is Source; `sourceId` is the operational Pair identifier and never exposes persistence association names.

FILE is the only implemented provider. One active Tenant OWNER creates and manages it. Creation serializes on the Tenant row, creates or reuses exactly one NO_AUTH Credential, and creates one PUBLIC Pair. Members cannot manage Sources.

## Browser product model

The browser separates implemented Source types from configured Source instances. `/admin` is a configured-Source list without an embedded detail pane; each row navigates to `/admin/sources/{sourceId}`. `/admin/sources/new` groups implemented providers into compact icon-and-label tiles, and a provider-owned setup flow creates one Source before returning to its dedicated detail route. The reusable wizard shell owns a lightweight progress rail and navigation but no provider fields or validation. FILE supplies only one `configuration` step. Unimplemented providers, placeholder steps, persistence concepts, and “coming soon” tiles are not product surfaces.

Source identity is route-owned through the detail path parameter, so creation, direct navigation, and browser history address one deterministic configured Source. This browser navigation contract introduces no provider registry or additional HTTP resource.

## Application and persistence boundary

`DefaultSourceManagementService` owns authorization, validation, upload adoption, orchestration, transaction boundaries, transition decisions, and typed failures. It injects concrete Connector persistence repositories plus the public `ObjectUploadService`; Connector persistence never imports object-storage persistence. `JdbcSourceUploadRepository` binds a generic upload to one Pair and persists its finalized item/version/attempt receipt. Application code contains no SQL, row mapping, locks, claims, or bulk updates; single internal JDBC implementations have no repository interfaces.

## File and operation lifecycle

A FILE upload declares 1 byte–10 MiB, a normalized display filename, media type, and lowercase SHA-256. The API commits a Pair-bound generic upload intent, returns a provider-neutral presigned PUT authorization, and receives no file bytes. Finalization verifies provider metadata outside the Connector transaction, then adopts a new immutable `StoredObject` or discards a duplicate staged object. Duplicate content in one FILE Pair converges on one item/version/live attempt. A finalized receipt makes lost-response replay idempotent. Detection and extraction never run in the API transaction.

Reindex is idempotent while work is nonterminal. Item removal and source deletion are explicit POST commands that immediately invalidate retrieval mappings and return durable cleanup operations. Connector owns adopted FILE-object cleanup: provider deletion is completed before upload associations, versions/items, object metadata, and unreferenced Documents are removed in dependency order. Cleanup results remain queryable after target deletion; `SUCCEEDED` and `SUPERSEDED` are terminal success states.

## Access

`SourceDocumentAccessResolver` grants PUBLIC read clearance only when the actor has current active Tenant membership and a live retrieval-eligible mapping whose Pair, Connector, and Document are active/eligible. Source-management authority does not imply access, and inactive/deleting state fails closed.
