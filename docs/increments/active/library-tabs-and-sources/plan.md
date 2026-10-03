# Plan

## Steps

1. **Backend.**
   - `JdbcSourceDocumentRepository.readableSources` over the Source read scope, counting readable documents under
     `DOCUMENT_READ_SCOPE` without its Source-status condition.
   - `SourceSearchService.readableSources`, and `DocumentShelfService.catalog` requiring `SEARCH_READ`.
   - `GET /api/chat/library/sources`.
   - The shared `DERIVED_STATUS` expression.
   - `GET /api/chat/library/meetings` removed with `LibraryShelfService.meetings`, `Owner` and `MeetingQuery`.
   - `openapi.yml` and the web client regenerated.
2. **Web.**
   - `library-tabs.tsx` replaces `library-rail.tsx`; `library-views.ts` holds the view types; the meetings view, its
     `owner` filter and its catalog keys are removed.
   - `features/sources/source-list.tsx` is shared by `/admin/sources` and `ReadableSources`.
   - The `LibrarySources` slot passes from the route through `ChatLibraryPage`.
   - The search schema gains `sources`, `sourceStatus`, `provider` and `access`.
3. **Docs.**
   - The [library hub spec section](../../../specs/chat.md#everything-a-person-can-reach-library-hub) and the route
     table.
   - The [verification matrix](../../../tests/chat.md#file-library-mem-142).

## Verification

| What | Result |
| --- | --- |
| Backend compile (`:core:compileTestJava`, `:api:compileTestJava`) | Pass |
| `OpenApiContractTest` with `MEMORYOS_OPENAPI_WRITE=true`, then `pnpm generate:api` | Pass; the document gains `listChatLibrarySources` and loses `listChatLibraryMeetings` |
| `DocumentShelfIntegrationTest` (7, with the new catalog case), `LibraryShelfIntegrationTest` (7) and `PostgresSourceLifecycleTest` (29, over the shared `DERIVED_STATUS`) against PostgreSQL | 43 tests pass |
| Web `typecheck`, `lint`, `format:check`, `check:i18n`, `check:knip` | Pass |
| Vitest for `features/library`, `features/sources` and `features/chat/library` | 30 files, 164 tests pass |
| Playwright `library-hub.spec.ts` (mocked API), first run | axe found `aria-valid-attr-value` on tab triggers without a panel and low contrast on the default `TabsList`; fixed by rendering the view in `TabsContent` and choosing views with the toolbar's `ToggleGroup` |
| Playwright `library-hub.spec.ts` after the fix | 5 tests pass, axe included |
| `OpenApiContractTest` (read-only) and `ChatSessionApiIntegrationTest` library cases (`libraryListsTheSourcesAReaderMayReadFromOnlyUnderSearchAuthority`, `fileLibraryListsOwnUploadsAndNamesWhatBlocksDeletingOne`, `aReaderThroughAnAssistantDownloadsItsFileAndFindsItInTheLibraryUntilTheShareEnds`) | 4 tests pass. The whole `ChatSessionApiIntegrationTest` exceeds the 10-minute `:api:test` timeout on this laptop; CI runs it |
| The page in a browser (mocked API): tabs, *Của tôi* views, *Tổ chức › Nguồn dữ liệu*, a Source name opening *Tài liệu* narrowed to it, and a 390 px phone | As designed; the Source table scrolls sideways in its own region on a phone |

## Open

- Live acceptance with a real Source a member reads through a Group, observed after signing in through Keycloak.
- No Linear issue exists yet for this increment.
