# MEM-152 plan — file library v2

## Phase 1 — reuse

- [x] **Server-side copy.** `ObjectUploadService.write` (verified server write on the browser lifecycle); V92 `copied_from_source`/`copied_from_id` with the live-copy unique index; `ChatLibraryService.copy`; `POST /api/chat/library/{source}/{id}/copy`; `messageId` on library rows; `openapi.yml` and the generated client.
- [ ] **Composer picker.** "Choose an existing file" opens the library dialog (search, source and category filters, multi-select, *Attach*); artifacts go through the copy route.
- [ ] **Conversation panel actions.** Attach to next message, show in conversation, filter and search within the panel, count refreshed after a turn.
- [ ] **Add to Project.** Row and selection action on `/library` and in the panel; remove from Project on the usage label.
- [ ] **Tests.** `ObjectUploadLifecycleIntegrationTest.aServerWriteIsAVerifiedUploadThatIsAdoptedOrReclaimedLikeABrowserOne`, `ChatSessionApiIntegrationTest.aGeneratedImageIsCopiedIntoOneReusableUploadThatOutlivesIt`, the `messageId` assertions in `ChatPersistenceIntegrationTest`, and web tests for the picker, panel actions and Project action.
- [ ] **Docs.** Chat spec file-library section, object-storage spec, verification matrix rows, `AGENTS.md` active increment entry.
- [ ] **Evidence.** `./gradlew clean check`, `pnpm check`, CI green on the pull request.

## Phase 2 — bring in and find

Not started.

## Phase 3 — many files

Not started.

## Phase 4 — limits and safety

Not started.
