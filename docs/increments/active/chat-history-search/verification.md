# Verification — 2026-09-13

## Backend

- `ChatSessionApiIntegrationTest.searchChatHistoryUsesIndexesAllVersionsAndOwnerFilteredPagination` passed against real PostgreSQL (Testcontainers) with Flyway V44 applied: case-insensitive `simple` token matching in titles and unselected historical ASSISTANT versions, `limit`/`offset`/`hasMore` pagination without duplicates, deleted session exclusion, another actor sees nothing, special characters return no error, >200-character query / `limit=51` / `offset=-1` rejected with 400, search does not select or mutate branches, a >64 KiB answer with 100,000 distinct tokens is stored and still matched at its tail, both GIN indexes exist, and inactive Tenant membership returns 403.
- `:core:compileTestJava :api:compileTestJava` passed. JetBrains `get_file_problems` (warnings included) reported no problems for `JdbcChatSearchRepository`, `DefaultChatSessionService`, `ChatSessionService`, `ChatSessionController` and `ChatSessionSearchResponse`. V44 reports only IDE-without-data-source findings: "no data sources configured" and unresolved `deleted_at`, which V36 adds to `chat_session`; the PostgreSQL test applied V44 and asserted both indexes. `openapi.yml` inspection timed out twice (5,600+ lines); `OpenApiContractTest` and `check:api` cover that contract.
- OpenAPI `searchChatSessions` and the generated hey-api client/query helpers are present; `OpenApiContractTest` passed and `pnpm check` (including `check:api` generated-client stability, `check:routes` and production build) passed. The build keeps the existing >500 kB chunk notice.
- Whole-repository `clean check` has not been run for this slice; run it before any PR.

## Frontend

- Web `typecheck`, `lint`, `check:i18n` (0 findings), `format:check` passed; `test:unit` 37 files / 197 tests passed.
- The obsolete loaded-title filter was removed: `groupThreadTitles` only groups by local-calendar day, its four translation keys were deleted, and the unit test asserts grouping order only.
- `search-page.test.tsx` was stale after the edit/navigation slice removed the duplicate sidebar Search link; it now asserts that link is absent instead of expecting it.
- Chromium, local frontend with fixture responses: `chat-history-search.spec.ts` 3/3 and `chat-ui-polish.spec.ts` 4/4 passed (7 in 25.9s). Covers a server message-only hit absent from loaded sidebar rows, no cmdk local filtering (20 options), "Xem thêm kết quả" requesting offsets 0 then 20, navigation to the selected session and dialog close at 1440/390px without horizontal overflow; collapsed-rail trigger, stale debounced query never rendered as current, 503 alert with retry, Escape restoring focus to the trigger.

## Boundary

Browser checks mock the search endpoint; the real SQL path is proven only by the PostgreSQL integration test. No live staging acceptance, OCR change, commit, PR, push, deployment or Linear mutation in this slice.
