# MEM-152 plan — file library v2

## Phase 1 — reuse

- [x] **Server-side copy.** `ObjectUploadService.write` (verified server write on the browser lifecycle); V92 `copied_from_source`/`copied_from_id` with the live-copy unique index; `ChatLibraryService.copy`; `POST /api/chat/library/{source}/{id}/copy`; `messageId` on library rows; `openapi.yml` and the generated client.
- [x] **Composer picker.** "Choose an existing file" opens the library dialog (search, source and category filters, multi-select, *Attach*); artifacts go through the copy route.
- [x] **Conversation panel actions.** Attach to next message, show in conversation, filter and search within the panel, count refreshed after a turn.
- [x] **Add to Project.** Row and selection action on `/library` and in the panel; remove from Project on the usage label.
- [x] **Tests.** `ObjectUploadLifecycleIntegrationTest.aServerWriteIsAVerifiedUploadThatIsAdoptedOrReclaimedLikeABrowserOne`, `ChatSessionApiIntegrationTest.aGeneratedImageIsCopiedIntoOneReusableUploadThatOutlivesIt`, the `messageId` assertions in `ChatPersistenceIntegrationTest`, and web tests for the picker, panel actions and Project action.
- [x] **Docs.** Chat spec file-library section, object-storage spec, verification matrix rows, `AGENTS.md` active increment entry.
- [ ] **Evidence.** Local 2026-09-21: the listed Java tests and `OpenApiContractTest` pass; the web suite passes (500 tests; `chat-code.test.tsx` timed out once under load and passes alone), with typecheck, oxlint, oxfmt and the i18n audit clean. CI on the pull request runs `clean check`.

## Phase 2 — bring in and find

- [x] **Direct upload.** *Upload* button, whole-page drop target and per-file progress tray on `/library`, over the composer's own upload pipeline; `status=PENDING` view with *Retry* and *Remove*, polling while anything is processing.
- [x] **Content search.** `GET /api/chat/library/search`; `JdbcUserFileRepository.searchable`; `ChatLibraryService.searchContent` over `ChatFileSearchService`; a Name/Contents switch with marked passages.
- [x] **Rename and favourite.** V93 `favorite_at`; `PATCH /api/chat/library/{source}/{id}` renaming the file's own name and keeping its extension; `favorite=true` filter, `sort=NAME`, star and rename in the row menu.
- [x] **Tests.** `ChatPersistenceIntegrationTest.theLibraryRenamesStarsAndListsPendingUploadsOnlyForTheirOwner`, `ChatLibraryRenameTest`, `ChatLibraryContentSearchTest`, `ChatSessionApiIntegrationTest.theLibraryRenamesStarsAndShowsUploadsStillBeingProcessed`, and the web tests for upload, pending view, rename/star, content search and highlighting.
- [x] **Docs.** Phase 2 decisions here, chat spec, verification matrix.
- [ ] **Evidence.** Local 2026-09-21: the listed Java tests and `OpenApiContractTest` pass; `chat-library*` and `chat-session-files` web tests pass (25), with typecheck, oxlint, oxfmt and the i18n audit clean. CI on the pull request runs `clean check`.

## Phase 3 — many files

- [x] **ZIP download.** V94 `chat_library_archive`; `JdbcChatLibraryArchiveRepository` (lease, attempts, expiry claim); `ChatLibraryArchiveService` (request, pack, open, sweep); `POST/GET /api/chat/library/archives`, `…/{id}`, `…/{id}/content`; the `memoryos-chat-library-archive-v1` Worker task; the selection's *Tải về ZIP* with polling, auto-download and a named skip list.
- [x] **Richer previews.** Rotation, drag-panning while zoomed and previous/next across the listed files, with arrow keys, in the preview modal; `/library` and the conversation panel pass their files as siblings.
- [x] **Tests.** `ChatLibraryArchiveIntegrationTest` (packing with unique names and skips, expiry release, the request bounds, a request that keeps failing), `ChatSessionApiIntegrationTest.aSelectionIsPackedIntoOneOwnerPrivateZipAndRefusesWhatItCannotPack`, `chat-library-page.test.tsx` (ZIP flow) and `chat-file-preview-gallery.test.tsx`.
- [x] **Docs.** Phase 3 decisions here, chat spec, verification matrix.
- [ ] **Evidence.** Local 2026-09-21: the listed Java tests and `OpenApiContractTest` pass; `chat-library*` and preview web tests pass (20 + 2), with typecheck, oxlint, oxfmt and the i18n audit clean. CI on the pull request runs `clean check`.

## Phase 4 — limits and safety

- [x] **Storage usage and quota.** `JdbcChatLibraryRepository.usage`; V95 `chat_settings.storage_quota_bytes` and `chat_storage_quota`; `ChatStorageQuotaService` (usage, `requireRoom`, administration); `GET /api/chat/library/usage`, `GET/PUT /api/chat/storage-quota`, `PUT /api/chat/storage-quota/{actorId}`; refusals wired into uploads, server-side copies, generated files and generated images; the storage bar on `/library` and the `/admin/file-storage` page.
- [x] **Trash with restore.** [ADR 0014](../../../decisions/0014-file-library-trash.md); V96 `deleted_at`/`purge_after`; `memoryos.chat.retention.trash-after`; `ChatLibraryTrashService`; `status=TRASH`, restore, purge and empty routes; the `memoryos-chat-library-trash-v1` Worker task; the Trash view with restore, delete-for-good, empty-the-trash and a confirmation that follows the window.
- [x] **Tests.** `ChatLibraryTrashIntegrationTest`, `ChatSessionApiIntegrationTest.aStorageLimitIsAdministeredByModelManagersAndRefusesAnUploadBeforeItIsAuthorized`, `ChatSessionApiIntegrationTest.aDeletedUploadWaitsInTheTrashWhereItsOwnerRestoresOrEndsIt`, `chat-library-page.test.tsx` (storage bar, trash) and `chat-storage-quota-page.test.tsx`.
- [x] **Docs.** Phase 4 decisions here, ADR 0014, chat spec, verification matrix.
- [ ] **Evidence.** Local 2026-09-21: the listed Java tests and `OpenApiContractTest` pass; the `chat` web suite passes (175 tests), with typecheck, oxlint, oxfmt and the i18n audit clean. CI on the pull request runs `clean check`.
