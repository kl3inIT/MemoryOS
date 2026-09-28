# Plan

Owner decisions (2026-09-25):
1. A full browse of every Source document the person may read (1a).
2. Files of Agents the person uses may be downloaded.
3. Shared meetings appear read-only.
4. Recent and stars on reachable rows are included now.

## Steps

1. **Source document browse.**
   - `FileCategorySql` in `shared`, used by the owned listing too.
   - A browse over `DOCUMENT_READ_SCOPE` in `JdbcSourceDocumentRepository`, reached through `SourceSearchService`.
   - `retrieval.DocumentShelfService`, which requires `SEARCH_READ`, reports the live generation and pages with a
     keyset cursor bound to its filters.
2. **Reachable rows and marks.**
   - The `MeetingShelf` port, implemented by `meeting`.
   - `FileAttachments.agentFiles`, implemented by `chat`.
   - `LibraryShelfService` with the shared, meetings, documents, recent and starred views, stars and opens.
   - V133 `library_mark`.
   - Agent file download and xlsx preview through the Agent grant.
   - Nine routes under `/api/chat/library`, and the regenerated `openapi.yml`.
3. **Web.**
   - Eight rail views, the entry list with reason lines, per-kind actions and stars.
   - Opens are recorded from every preview, download and question.
   - The documents view appears only with `SEARCH_READ`.
4. **Docs.**
   - The chat spec [file library hub section](../../../specs/chat.md#everything-a-person-can-reach-library-hub), the
     route table and the [verification matrix](../../../tests/chat.md#file-library-mem-142).
   - `ARCHITECTURE.md` module responsibilities.

## Verification

| What | Result |
| --- | --- |
| `LibraryShelfIntegrationTest` (7), `DocumentShelfIntegrationTest` (6), and the existing library, meeting repository, architecture, Source original, document original, export and artifact cleanup tests | 82 tests pass against PostgreSQL |
| `ChatSessionApiIntegrationTest` including the Agent-reader download case, and `OpenApiContractTest` after regeneration | Pass |
| Web `typecheck`, `lint`, `format:check`, `check:i18n`; vitest for library, chat library, search and meetings | Pass (22 files, 144 tests) |
| Playwright `library-hub.spec.ts` (mocked API) | See the delivery report |
| Local runtime | The API applied the library mark migration to the development database; API, Worker and web healthy |

## Open

- Live acceptance with real data: a second member in another Group, a shared meeting and a shared Agent, observed
  after signing in through Keycloak.
- No Linear issue exists yet for this increment.
