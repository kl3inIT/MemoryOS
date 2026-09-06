# Connector capability contract

## Ownership

Connector owns Tenant-scoped Connector, NO_AUTH Credential, ConnectorCredentialPair, ConnectorItem/version, index/cleanup operation association, Pair-to-Document provenance and Source–Group associations. The public product read model is Source; `sourceId` is the operational Pair identifier and never exposes persistence association names. IAM owns Groups and authority, not Source persistence.

FILE is the only implemented provider. Creation requires global `SOURCES_MANAGE`, serializes on the Tenant authorization row, creates or reuses exactly one NO_AUTH Credential, and creates one PUBLIC Pair. Omitted or empty creation Group selection defaults to Admin. Membership role alone never authorizes Source management.

## Browser product model

The browser separates implemented Source types from configured Source instances. `/admin` is a semantic full-width configured-Source table without an embedded detail pane: a stacked icon/title header and right-aligned action lead into search, expand/collapse, and status/access filtering; expandable six-column provider summaries expose total, active, and public Source counts plus indexed-document totals; expanded Source rows expose Name, Last indexed, Status, Permissions / Access, Total docs, and icon-only Manage before navigating to `/admin/sources/{sourceId}`. `/admin/sources/new` mirrors that header/search composition and groups implemented providers into exact Onyx `w-40` icon-and-label tiles with `16px` padding and intrinsic content height that wrap without stretching. FILE setup is a single form with source name, drag/drop or browsing, a removable selected-file summary, and one Upload and create action. A blank name is populated from the filename. Selection validates one supported file of 1 byte–10 MiB before creating a Source. One guarded submit creates the Source, uploads and finalizes, then opens its detail route for indexing status. Upload retries retain the created Source; finalization retries reuse the stored upload without another PUT. There is no setup progress rail or Previous/Continue navigation. Unimplemented providers, placeholder steps, persistence concepts, and “coming soon” tiles are not product surfaces.

Source identity is route-owned through the detail path parameter, so creation, direct navigation, and browser history address one deterministic configured Source. Pending finalization state lives above the Source routes and retains only the initiating Source/upload identifiers plus filename, never the presigned URI or bytes. Navigation to another Source exposes a return link; retry occurs against the initiating Source and emits no second provider PUT. This browser navigation contract introduces no provider registry or additional HTTP resource.

The browser consumes server-projected Source actions (`upload`, `reindex`, `remove_items`, `delete`, `manage_groups`). A scoped manager sees only associated Sources and upload/reindex controls; global capability holders receive their applicable actions. Creation/detail Group selectors persist real associations, and Group detail exposes only authorized Source associations. Authority-revision changes reset mounted private queries before removal, including when capability tokens remain unchanged.

## Application and persistence boundary

`DefaultSourceManagementService` owns authorization, validation, upload adoption, orchestration, transaction boundaries, transition decisions and typed failures. It injects concrete Connector persistence plus public `ObjectUploadService`, `IamAuthorization` and Group-scope contracts; it never imports another capability's persistence. `JdbcSourceUploadRepository` binds a generic upload to one Pair and persists its finalized item/version/attempt receipt. `JdbcSourceGroupRepository` owns Tenant-qualified associations. Application code contains no SQL, row mapping, locks, claims or bulk updates; single internal JDBC implementations have no repository interfaces.

## File and operation lifecycle

A FILE upload declares 1 byte–10 MiB, a normalized display filename, media type and lowercase SHA-256. The API authorizes the concrete Source, commits a Pair-bound generic upload intent, returns a provider-neutral presigned PUT authorization and receives no file bytes. Finalization verifies provider metadata outside the Connector transaction, then reacquires shared Tenant authority and rechecks concrete Source scope before adopting a new immutable `StoredObject` or discarding a duplicate staged object. Revocation during provider IO therefore prevents adoption. Duplicate content in one FILE Pair converges on one item/version/live attempt; finalized receipts make lost-response replay idempotent. Detection and extraction never run in the API transaction.

Reindex is idempotent while work is nonterminal. Item removal and source deletion are explicit POST commands that immediately invalidate retrieval mappings and return durable cleanup operations. Connector owns adopted FILE-object cleanup: provider deletion is completed before upload associations, versions/items, object metadata, and unreferenced Documents are removed in dependency order. Cleanup results remain queryable after target deletion; `SUCCEEDED` and `SUPERSEDED` are terminal success states.

Source status/error reconciliation uses the newest attempt per Connector item. A successful retry clears that item's historical failure without hiding another item's current failure. Historical attempts remain queryable; reconciliation does not delete evidence or treat an old failed attempt as current.

## Management authority and Group associations

All management requires current active IAM authority. Global access is capability-derived; scoped access requires a manager edge in an ordinary Group associated with the concrete Source.

| Operation | Authority |
| --- | --- |
| Source list/detail, items, attempt history and operation polling | Global `SOURCES_READ` or associated managed-Group scope |
| Source creation | Global `SOURCES_MANAGE` |
| Upload initiation/finalization and reindex | Global `SOURCES_MANAGE` or associated managed-Group scope |
| Item removal and Source deletion | Global `SOURCES_DELETE` only |
| Source–Group replacement | Global `SOURCES_MANAGE` only |

Out-of-scope reads and concrete Source operations fail without revealing the target; global-only denied commands do not fall back to scoped management. Source and operation queries enforce Tenant/scope predicates before exposing results. Scope is checked again on replay/finalization, not inferred from a previously issued upload intent or operation ID.

V16 gives Source–Group rows Tenant-qualified foreign keys to both resources and seeds existing Sources to Admin. Replacement requires a nonempty validated selection; Group deletion can cascade its associations without deleting Sources. Globally authorized administrators retain access. Association changes serialize exclusively on the same Tenant authority row and advance its revision; protected Source commits use the shared lock. No cross-capability JPA relationship or provider credential is added.

## Document-content access

`SourceDocumentAccessResolver` grants PUBLIC read clearance only when the actor has current active Tenant membership and a live retrieval-eligible mapping whose Pair, Connector, and Document are active/eligible. Source-management authority does not imply access, and inactive/deleting state fails closed.
