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

The Chat source panel currently reads passages through `/api/search/documents/{id}`, which requires `SEARCH_READ`. A conversation owner who may read the conversation should open a citation it already contains through a Chat-scoped read that requires `CHAT_READ` and still applies current document eligibility. This is a follow-up commit in this increment.

## Document access list (later phases)

- Index each chunk with an access list (`user:<actorId>`, `group:<groupId>`) and a public flag; the query adds `public OR terms(access list, user tokens)` in every read path.
- Source Group changes, access-type changes and user-file lifecycle enqueue metadata-only updates through the existing search work queue, reusing vectors.
- Google Drive per-file tokens follow the MEM-88 contract (verified e-mail identity linking, Google groups, fail closed).
- The current post-query database recheck and expansion recheck are kept only if measurement shows their cost is small. The reference model accepts index-sync lag instead of a per-result recheck.

## Measurement

Before the index access list, record the real-database cost of `memoryos.search.stage.duration` stages `prefetch` and `authorization` against `hybrid` and `expansion` on the actual Search path, plus the Chat capability check. The decision rule: if the database recheck is a material share of Search latency, follow the reference (index filter only, sync lag accepted) rather than keeping the stricter recheck.
