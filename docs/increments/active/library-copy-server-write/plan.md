# Plan

- [x] V125: `chat_user_file.stored_object_id`, nullable `upload_id`, the ownership check, the unique index and the backfill.
- [x] Readers join the stored object directly; browser finalization records the object; the file work clears it on release.
- [x] `ChatLibraryService.copy`/`publish` stage, adopt and discard through `ObjectWriteService`; a copy's release goes through `releaseAdopted`.
- [x] Remove `ObjectUploadService.write`, its implementation and its tests.
- [x] Tests: backfill, copy and publish, storage failure leaves no pending object, a race discards, releasing a copy removes its object.
- [x] Update the object-storage and Chat specs, the verification matrices and the audit owner decisions.

## Verification — 2026-09-24

The machine was shared with other builds, so only targeted suites ran; the full `clean check` runs in CI.

- `./gradlew :core:compileJava :core:compileTestJava :api:compileJava --no-daemon`: passed.
- `./gradlew :core:test --no-daemon --tests 'io.memoryos.objectstorage.*' --tests ChatLibraryCopyIntegrationTest --tests ChatUserFileStoredObjectMigrationTest --tests ChatLibraryTrashIntegrationTest --tests ChatFileLifecycleIntegrationTest --tests ChatLibraryContentSearchTest --tests ChatSessionPurgeIntegrationTest --tests io.memoryos.ModulithArchitectureTest`: 58 tests; 56 passed and two assertions in the new tests were wrong (the artifact is itself an adopted write; a null column read needed `Optional`). After correcting them, `ChatLibraryCopyIntegrationTest` (4) and `ChatUserFileStoredObjectMigrationTest` (1) passed on rerun.
- `./gradlew :core:test --no-daemon --tests io.memoryos.chat.ChatPersistenceIntegrationTest`: 37 tests passed.
- `./gradlew :api:test --no-daemon --tests 'io.memoryos.api.chat.ChatSessionApiIntegrationTest.aGeneratedImageIsCopiedIntoOneReusableUploadThatOutlivesIt'`: passed; the copy endpoint's HTTP behaviour is unchanged.
- `git diff origin/main -- openapi.yml web/src/lib/hey-api`: empty.
