# MEM-93 — Chat and Search authorization

## Goal

Enforce the Basic child capabilities that Chat carried only as vocabulary, and move document access checks for Search and Chat retrieval to a per-document access list stored with each indexed chunk. Follow the validated reference model rather than adding checks stricter than it: the user decision is "do what the reference does, measure, and optimize the same way when a stricter check costs too much".

## Capability layer

`SYSTEM_BASIC` implies `SEARCH_READ`, `CHAT_READ` and `CHAT_WRITE` (`CHAT_WRITE` implies `CHAT_READ`). Basic access in the reference is always granted to an authenticated member, so operations mapped to it need only the existing active-membership and ownership checks; no `SYSTEM_BASIC` check is added.

| Operation | Requirement |
|---|---|
| List, search, get conversation, history, branches, project conversation list, reply event stream, shared transcript and its history | `CHAT_READ` + existing ownership/sharing checks |
| Create conversation (plain, workspace, project), send, edit, regenerate, stop | `CHAT_WRITE` + existing ownership checks |
| Rename, generated title, delete, select branch, Persona/Project/settings changes, sharing toggle, Projects/Personas CRUD | Active membership + ownership (Basic in the reference) |
| Feedback read/write | Active membership + ownership |
| Search and document passage reads | `SEARCH_READ` (unchanged) |

Checks run in the application services at the lowest shared entry, before ownership lookup and the per-session command lock. A capability denial is `403 IAM_ACCESS_DENIED`; an unowned or missing conversation stays `404 CHAT_UNAVAILABLE`. The Chat Search tool keeps its existing `SEARCH_READ` check inside `DocumentSearchService`; in the reference the Chat tool is not gated separately, which is a later alignment decision, not part of this change.

The UI is not locked. Hiding actions from per-resource permission maps is split into MEM-94.

## Citation reads

The Chat source panel read passages through `/api/search/documents/{id}`, which requires `SEARCH_READ`. It now uses `GET /api/chat/documents/{documentId}?generation&from` (`readChatDocumentPassages`), which requires `CHAT_READ` and applies the same document eligibility and generation checks before and after the index read (`DocumentSearchService.citation`). The Search page keeps `/api/search/documents`. Owner-private file citations keep `/api/chat/files/{fileId}/passages`.

## Document access list

What it buys, stated plainly: per-document authority in the index, so Google Drive per-file permissions have a place to live and the query has the reference shape. It is not a latency win; the actor's Group tokens still come from PostgreSQL at request time (the measured PREFETCH is ≈ 6 ms) and the post-query recheck stays.

- **Index time.** `JdbcSourceDocumentRepository` resolves, for the Sources a document maps to, a public flag (any mapped Source is `PUBLIC`) and `group:<groupId>` tokens from `source_group_grants`. Every chunk stores `access_public` (boolean) and `access_control_list` (keyword array). Both enter `metadata_hash` (`v2:`), so an access change makes `contains()` false and the existing projection repair re-indexes with vector reuse.
- **Query time.** `SourceSearchScope` carries the actor's tokens (`group:<id>` for current ordinary Group memberships), computed in `SourceSearchService.scope`. Every Source query adds `bool.should[term(access_public,true), terms(access_control_list, tokens)]` with `minimum_should_match: 1`. The existing `source_metadata.source_id` filter stays because Persona Source narrowing, Source-type and time filters use the same nested origin.
- **Out of scope.** Owner-private chat files are already excluded from Source queries and read only through explicit owner file mappings, so no `user:<actorId>` token is added for them.
- **Propagation.** `reconcile()` scans 32 documents per minute (≈ 26 hours for 50,000 documents), too slow for access changes. Replacing a Source's Groups or changing its access type enqueues its current documents for re-index in the same transaction; the worker rewrites chunks with reused vectors and the new access fields. Group membership changes need no index write because tokens are resolved per request.
- **Google Drive per-file permissions** follow the MEM-88 contract (verified e-mail identity linking, Google groups, fail closed). Main has no per-file permission tables yet, so this phase waits for MEM-88.

## Measurement

Before the index access list, record the real-database cost of `memoryos.search.stage.duration` stages `prefetch` and `authorization` against `hybrid` and `expansion` on the actual Search path, plus the Chat capability check. The decision rule: if the database recheck is a material share of Search latency, follow the reference (index filter only, sync lag accepted) rather than keeping the stricter recheck.

Result and accepted decision: the ranked recheck costs ≈ 14 ms for 400 candidates and stays. Only repeated per-read expansion rechecks were material (up to ≈ 225 ms per Chat Search call); they are replaced by `DocumentSearchService.authorizedSections`, one batched recheck after LLM selection and one before evidence is returned. `window` reads neighbors without its own recheck.
