# Library hub: every file a person can see or use

Status: **accepted 2026-09-25; backend and web implemented** on `phamnhatanh811/library-hub`, awaiting live acceptance
with real shared data. No Linear issue yet. Reference research with sources: [references.md](references.md). The rail
and the meetings view described below were replaced by tabs in
[library tabs and Sources](../library-tabs-and-sources/design.md) (2026-09-28).

## Problem

The owner wants `/library` to be the one place where a person finds and manages everything they can see or use.
Today it lists only what they **own**. Every branch of the `JdbcLibraryRepository` `SOURCES` union binds
`owner_actor_id = :actor` (lines 76, 91, 100), so nothing that reaches a person through a Group, a share or an Agent
appears.

## Inventory: what a member may see or use

Each row states what authorizes it today and what this increment does with it.

| Asset | Authority today | Decision |
| --- | --- | --- |
| Own uploads | Owner | Listed, unchanged (**Tệp của tôi**) |
| Own `run_python` files, own generated images | Owner | Listed, unchanged |
| Own meetings | Owner | **Listed** (Cuộc họp) |
| Meetings shared with them | `MeetingAccessSql.READS`: a member share, or a share to one of their Groups | **Listed, read-only** (Được chia sẻ với tôi, Cuộc họp) |
| Knowledge files of Agents they use | `AgentAccessSql.USES` without the manager grant (`usableThroughAgents`) | **Listed, read-only, downloadable** (owner decision 2) |
| Source documents (FILE, Google Drive, SharePoint) | `SEARCH_READ` and `DOCUMENT_READ_SCOPE`: PUBLIC, PRIVATE through a granted Group, SYNC through provider grants | **Listed, read-only** (Tài liệu tổ chức), a full browse (owner decision 1a) |
| Agent avatars | Readable through the Agent | Not listed: they are the Agent's icon, not content |
| Projects | Owner only | Not listed as rows; their files are own uploads, labelled by `usedBy` |
| Agents, Document Sets | `USES` | Not listed: they are containers with their own pages, not files |
| Shared conversations | Same-Tenant link; transcript only | Not listed: their artifacts stay owner-only ([MEM-142 decision 13](../../completed/mem-142-file-library/design.md)) |
| Chat exports, library ZIP archives | Owner; short-lived | Not listed: downloads with their own notices |
| MCP results, Web search results, voice | Nothing is persisted as a file | Not applicable |

## Reference behaviour

1. **Two axes.** A relationship axis (recent, mine, shared with me, starred) crossed with a kind axis. Used by Glean,
   Notion, Microsoft 365 Copilot, Dropbox Dash and Google Drive.
2. **Connected-source documents are references with live permissions.** Anything the reader cannot open is hidden,
   not shown locked (ChatGPT Library over Drive, Dash, OneDrive's People view).
3. **Recent and Starred mix owned and shared items** (Google Drive Recent and Starred, Glean Library).
4. **Only owned bytes count toward storage; only the owner trashes** (Google Drive).
5. Onyx `f9e3de36c` keeps user files owner-only (`backend/onyx/server/manage/users.py` L1451-1468), so it is the
   baseline only for owned files.

## Design

### Owned rows and reachable rows

**Owned rows** are today's listing, and nothing about them changes:
- Actions: rename, star, trash, restore, ZIP, copy, add to Project, ask Chat, and the `409 CHAT_FILE_IN_USE` guard.
- They alone count toward `memoryos.chat.storage.library-bytes` and are governed by trash and ADR 0014.

**Reachable rows** are references to assets another capability owns:
- **Authorized per read** by that capability's own predicate. A revoked share, Group or Agent share removes the row at
  the next read.
- **Never copied, never counted** toward the viewer's storage.
- **Never renamed, trashed or purged** by the viewer.

The owned union is not widened, because its window totals and quota sums must stay the owner's own. Each reachable
view is its own read, behind the capability that owns the asset.

### Views (rail)

| View | Content | Paging |
| --- | --- | --- |
| **Gần đây** | Every kind the viewer opened, newest first, re-authorized on read | The last 100 opens |
| **Tệp của tôi** | Owned files (today's *Tất cả tệp*); a *Chỉ tệp gắn sao* filter keeps bulk actions for starred owned files | Offset, unchanged |
| **Được chia sẻ với tôi** | Meetings shared with the viewer, and files of Agents they use, not owned by them | Bounded set (≤ 200 meetings, ≤ 1000 Agent files), filtered, sorted and paged on the server |
| **Cuộc họp** | Every meeting the viewer may read, with a *Tất cả / Của tôi / Được chia sẻ* filter | Bounded (≤ 200, `MeetingService.MAX_LIST`) |
| **Tài liệu tổ chức** | Source documents under the viewer's authority; filter by Source, category and name; sort newest or by name | Keyset cursor, as the Source views page |
| **Có gắn sao** | Owned favourites and starred reachable rows | Bounded (≤ 1000 owned + ≤ 500 reachable), merged by star time |
| Đang xử lý, Thùng rác | Unchanged | Unchanged |

*Tài liệu tổ chức* needs `SEARCH_READ`. Without it the view is absent, and documents are dropped from Gần đây and Có
gắn sao.

### One entry shape

Every view except the owned listing returns `ChatLibraryEntryResponse`:
- `kind`: one of `UPLOAD`, `GENERATED`, `IMAGE`, `MEETING`, `AGENT_FILE`, `DOCUMENT`.
- Common fields: `id`, `name`, `mediaType`, `sizeBytes`, `category`, `at`, `owned`, `starred`, `openedAt`, `ownerName`.
- `reason`: why the row is visible (below).
- Per-kind links: the conversation of an artifact, `meeting` state, the granting `agents`, and `document` (generation,
  Source, provider link).

The reason is a projection of the predicate that admitted the row:

| Kind | `reason.kind` | Shown as |
| --- | --- | --- |
| Owned | `OWNER` | nothing |
| Meeting shared to the viewer | `MEMBER_SHARE` | "Chia sẻ bởi {owner}" |
| Meeting shared to a Group | `GROUP_SHARE` | "Qua nhóm {groups}" |
| Agent file | `AGENT` | "Qua trợ lý {agents}" |
| Source document | `PUBLIC_SOURCE` / `GROUP_SOURCE` / `PROVIDER_SOURCE` | "Nguồn {Source} · Công khai / Qua nhóm {groups} / Quyền từ {provider}" |

### Per-viewer marks (stars on reachable rows, Recent)

V133 adds `library_mark(tenant_id, actor_id, kind, item_id, starred_at, opened_at)`, owned by `library`.

- **Stars:** an owned kind keeps its existing `favorite_at` column, so starring it through the entry route delegates
  to the existing update. Stars on reachable kinds live in `starred_at`, at most 500 per viewer.
- **Opens:** every open (preview, download, ask) records `opened_at` for any kind. Only the newest 200 opens per
  viewer are kept.
- **Re-authorization:** a mark is never authority. Every read resolves marked ids through the owning capability and
  drops what no longer resolves.

### Per kind

**Meetings.** The `meeting` module implements the library port `MeetingShelf`, as `chat` implements
`FileAttachments`. ADR 0015 forbids `library → meeting`.
- **Row:** the meeting itself.
- **Actions:** open the meeting page; download the biên bản (minutes `READY`) and the transcript (utterances exist),
  through the existing export routes that readers may already call; ask Chat.
- **Ask Chat:** the existing publish-then-attach path. Publishing makes the viewer's own Markdown copy of the minutes,
  never the transcript ([meeting spec](../../../specs/meeting.md)).

**Agent files.** `FileAttachments` gains `agentFiles`, implemented by `chat`: knowledge files of non-deleted Agents
the viewer uses, excluding the manager grant, as `usableThroughAgents` already rules.
- **Excluded:** files the viewer owns (already owned rows) and avatars.
- **Preview:** image, text, PDF, DOCX and xlsx.
- **Download:** decision 2 opens the download route (`/api/chat/files/{id}/content`) and the xlsx preview to readers
  through Agents. Until now both were owner-only, although the same bytes already reached those readers through the
  Agent's turns, text and thumbnail.
- **Ask Chat:** attaches the file directly, because turn admission already honours the Agent grant.

**Source documents.** `retrieval.DocumentShelfService` requires `SEARCH_READ` and calls a `connector` browse over the
same `DOCUMENT_READ_SCOPE` Search rechecks with.
- **Row filters:** only eligible, retrieval-eligible documents of active searchable Sources.
- **Duplicates:** a document mapped by several Sources is listed once, under its first readable mapping.
- **Preview:** the Search reader (`DocumentPreviewDialog`) with the document's served generation. A document not yet
  served has no generation; it is listed as being indexed and cannot be previewed.
- **Download:** `/api/search/documents/{id}/original`.
- **Ask Chat: not offered.** Chat has no per-document turn scope. Copying a PRIVATE document into the viewer's
  library would detach it from its Group authority, and an Agent holding that copy would expose it to the Agent's
  audience.
- **Category filter:** uses the same media-type rule as owned files, moved to `shared` as `FileCategorySql`. It is a
  domain-free MIME/extension rule now needed by `library` and `connector` ([ADR 0017](../../../decisions/0017-shared-kernel-holds-technical-utilities.md)).

### Module and API placement

- **Dependencies:** `library → retrieval` (Source documents), and ports implemented by `meeting` and `chat`. No new
  module dependency.
- **Owned tables only:** the library adds no SQL reads of another module's tables.
- **Routes:** stay under `/api/chat/library` with `CHAT_` codes (ADR 0015 step 3). Reachable views are new GET
  operations; the owned listing contract is unchanged.

| Route | Result |
| --- | --- |
| `GET /shared` | Entry page (`query`, `kinds`, `categories`, `sort`, `offset`, `limit`) |
| `GET /meetings` | Entry page (`query`, `owner` = ALL/MINE/SHARED, `sort`, `offset`, `limit`) |
| `GET /documents` | Entry page with `nextCursor` (`query`, `sourceIds`, `categories`, `sort` = NEWEST/NAME, `cursor`, `limit`) |
| `GET /documents/sources` | Sources the viewer may filter by |
| `GET /recent` | Up to 100 entries |
| `GET /starred` | Entry page (`query`, `kinds`, `offset`, `limit`) |
| `PUT` / `DELETE /entries/{kind}/{id}/star` | 204; 404 when not reachable now |
| `POST /entries/{kind}/{id}/opened` | 204; 404 when not reachable now |

## Departure from the accepted scope

| | |
| --- | --- |
| Requirement | One place for everything a person can see or use (owner, 2026-09-25) |
| Accepted baseline | MEM-142/MEM-152: an owner-private library; another person's Agent files out of scope |
| Reference | ChatGPT Library, Glean Library, Google Drive and Notion Library show reachable items with live permissions |
| Difference | Read-only reachable views and per-viewer marks; Agent files become downloadable by their readers |
| Benefit | A member browses the Tenant's documents, shared meetings and Agent knowledge without knowing a search term, and returns to them through Recent and Starred |
| Cost | Three authorized reads, a mark table, a Source browse over the full readable corpus. Leakage is the main hazard, so every predicate is the owning capability's own and is covered by revocation tests |
| Owner decisions | 1a full Source browse · 2 Agent files downloadable · 3 meetings read-only · 4 Recent and stars on reachable rows now |

## Excluded

- **Sharing and folders:** sharing library files and folders; visibility only.
- **Copies of Source documents:** copying Source documents into the library, and Ask Chat on a Source document.
- **Artifacts of shared conversations.**
- **Containers as rows:** Agents, Projects and Document Sets.
- **Short-lived downloads:** Chat exports and archives.
- **Nothing persisted:** MCP, Web search, voice.

## Reused entry points (web)

- **Previews.** Owned files, Agent files and meetings' published minutes open in the existing `ChatFilePreviewModal`.
  Source documents open in Search's `DocumentPreviewDialog` with no matches; a selection without matches now shows
  the original without the citation rail.
- **Listing controls.** Paging uses `TablePagination`/`PageSizeSelect` as the owned list does. Kind, category and
  owner filters are `FilterChips`. The document view's keyset paging is TanStack `useInfiniteQuery` with *Tải thêm*.
- **Toolbar.** Parts of the owned toolbar were extracted and shared by both listings: `LibrarySearchField`,
  `LibraryLayoutToggle`, `LibrarySortSelect`, `LibraryPicture`. `features/library` imports neither `features/chat`
  nor `features/meetings`. Meeting exports and publishing call the generated SDK directly, and the file-name `slug`
  moved to `lib/meeting-file-name.ts` so both features share it.
