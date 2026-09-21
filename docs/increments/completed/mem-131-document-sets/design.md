# MEM-131 — Onyx-compatible Document Sets

Linear: [MEM-131](https://linear.app/memory-os/issue/MEM-131).

## Accepted scope

A **Document Set** is a Tenant-owned named group of existing Sources. It is the Onyx-compatible answer selected on 2026-09-19, not the broader knowledge-space proposal from the original issue. A set narrows where an agent or Search request may look; it never grants access to a Source, Document, or provider-backed original.

The user selected:

- Onyx-style Document Sets, not a new departmental knowledge-space role model.
- Onyx-style direct user/ordinary-Group shares; there is no parallel viewer/contributor/admin membership system.
- Additive agent attachment: existing direct `persona_source` attachments remain supported. An agent's candidate Sources are the union of its direct Sources and attached Document Sets.

Published-versus-draft document lifecycle, personal spaces, subscriptions, per-document attachment, intent routing, and automatic agent selection remain out of scope. A future publication-state capability must filter a set only through the canonical Document/Source eligibility path; this increment must not invent a document-status field.

## Reference and terminology

The requested baseline is Onyx `document_set` with the `document_set__connector_credential_pair` association. The referenced local checkout (`D:\MemoryOS\.tmp\onyx`) is absent from this worktree, so this design records the Linear issue's observed schema names and selected behavioral boundary, not unverified endpoint or authorization details. The implementation uses MemoryOS names: **Document Set** maps to Onyx `document_set`; **Source** maps to Onyx connector-credential pair.

## Domain and authority

A set is owned by the Chat capability because agents consume it and its shares govern only agent/Search narrowing. Connector remains the sole owner of Sources, Source eligibility, and document access. IAM remains the sole owner of Actors, Groups, membership, and capabilities.

- A set has a Tenant-qualified identity, name (200 characters), description (2,000 characters), owner Actor, optimistic revision, timestamps, and soft deletion.
- A set contains at most 100 distinct Source IDs. Each added Source must be currently selectable through `SourceSearchService.scope(actor)` at the time of mutation; retained Sources may survive later revocation so they keep narrowing instead of widening a future query.
- The owner may share a set directly with active Actors or ordinary Groups. Shares have one `VIEWER` permission because sets have no content-contributor or administrative role. `AGENTS_CREATE` creates a set; its owner and `AGENTS_MANAGE` may mutate or administer it. A viewer can use a set but cannot alter it.
- A Set may be public, like Onyx `is_public`: every Tenant member may then use it without a share. Public is off by default (Onyx defaults it on) because `AGENTS_CREATE` is broader here than Onyx's document-set admin right, and only an editor (owner or `AGENTS_MANAGE`) may change it. Public never widens retrieval: each user is still intersected with their own `SourceSearchScope`.
- A non-editor's view lists only the Set Sources that actor may itself select and reports the rest as a `hiddenSources` count, so a public or shared Set cannot leak foreign Source names.
- Builtin visibility and public *permissions* do not apply to Document Sets: a Set has one VIEWER level, and a share or the public flag is an explicit narrowing constraint, never a replacement for Source authority.
- Every use recomputes current access. Effective readable Sources are: `set Sources ∩ current SourceSearchScope.sources`. Document eligibility/ACL remains enforced by the existing retrieval rechecks.
- Deleting a set soft-deletes it, removes its agent attachments, and makes it unavailable to Search/agent admission. History retains the set ID/name snapshot only where an existing Chat contract needs it; no deleted set is silently replaced by unrestricted Search.

## Read and command model

`DocumentSetService` owns set lifecycle, share checks, source selection validation, revision conflicts, and attachment resolution. Concrete `JdbcDocumentSetRepository` owns set/share/source/agent association SQL and locks. It uses public `SourceSearchService` and IAM/Tenant APIs only; Connector and IAM do not import Chat persistence.

The service exposes:

- paged usable-set listing and a full usable snapshot by ID, each trimmed to the viewer's own selectable Sources;
- create, update (including the public flag), soft-delete and share replacement with expected revision;
- usable Set IDs to a Chat agent under agent-use authority;
- a `SourceSearchScope` narrowing helper for direct Search that resolves at most ten requested set IDs under the active Actor and intersects their Sources with the current scope.

HTTP exposes `/api/chat/document-sets` for lifecycle/share/list/detail and `/api/search` accepts at most ten distinct `documentSetIds`. A denied, foreign, deleted, malformed, or unavailable ID fails closed with the existing typed Chat/Search problem contract; Search never reveals which one existed. The generated OpenAPI snapshot and Hey client are regenerated with the implementation.

## Agent and Search behavior

`persona_document_set` is additive to `persona_source`. `PersonaInput`/view retain direct `sourceIds` and add at most ten `documentSetIds`. Agent mutation validates each selected set under set-use authority. Turn resolution reads both associations under the existing Persona lock and turns them into one distinct candidate Source set, resolved under the asking actor's own set authority. Direct Sources and sets are both allowlists; no empty or unavailable set expands to all Sources. Because a resolved allowlist may legitimately be empty, the turn carries an explicit `sourcesRestricted` flag: an agent that attaches nothing searches every authorized Source, while an agent whose attachments resolve to nothing for this actor searches nothing.

Search without `documentSetIds` retains its current behavior. With them, direct Search performs one current set-use check and searches only the union of those set Source IDs, intersected with the actor's current `SourceSearchScope`. Sets are administered like Onyx `/admin/documents/sets`: `/admin/document-sets` (Knowledge, next to Sources) lists usable sets in a table (name, Sources, access, edit/delete actions by per-set permission), and `/admin/document-sets/new` and `/admin/document-sets/$documentSetId` hold one form for name, optional description, direct user/Group shares and at least one Source. Onyx's public flag and sync-status column have no MemoryOS counterpart: sets are never Tenant-wide and resolve at query time. Saving shares uses the separate revisioned sharing command after the set write; if only sharing fails, the saved set is kept and the user continues on its edit page. The Agents page links there for actors who can read Sources. The Search page offers a bounded Set filter using the existing shadcn selection primitive; it does not change the Source rail or grant any Source visibility.

## Persistence and concurrency

One append-only Flyway migration creates Tenant-qualified tables for `document_set`, `document_set_source`, `document_set_user_share`, `document_set_group_share`, and `persona_document_set`. Foreign keys, unique constraints, ordinary-Group validation, and Tenant-qualified joins prevent cross-Tenant links. Set updates lock the set row and compare its revision. Agent settings continue using their current Persona optimistic revision and row lock; set resolution at turn admission is fresh.

## Verification

- PostgreSQL integration covers owner creation, direct user sharing and additive direct Source plus Document Set Persona attachment in `ChatPersistenceIntegrationTest.documentSetsShareAndAttachToPersonasWithoutReplacingDirectSources`.
- `ChatPersistenceIntegrationTest.publicDocumentSetsAreUsableWithoutSharesAndHideSourcesTheViewerCannotSelect` proves a public Set is usable without a share, hides Sources the viewer cannot select, and stops being listed once it is made private again.
- `DocumentSearchServiceTest.documentSetFilterUsesOnlyTheCurrentNarrowedSourceScope` proves direct Search invokes Set narrowing before OpenSearch; `SearchRequestTest` covers request validation.
- `SearchToolTest.anAgentWhoseAttachedSourcesAllResolveToNothingSearchesNothing` and `ChatPersistenceIntegrationTest.anAgentWhoseDocumentSetIsUnusableSearchesNothingInsteadOfEveryAuthorizedSource` cover the shared-agent case where the viewer cannot use the attached Set.
- `OpenApiContractTest` generated and verified lifecycle/share/Search filter contracts. The Hey client snapshot is stable.
- `pnpm check` validates formatting, translation coverage, types, unit/browser-contract tests and generated route consistency. Browser verification reached the authenticated `/admin/document-sets` page with its Knowledge sidebar entry in the Orca browser; creating, sharing and attaching a set through the UI remains to be exercised.
