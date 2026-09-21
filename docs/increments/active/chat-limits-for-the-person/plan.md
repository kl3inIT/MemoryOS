# Plan — Chat limits belong to the person

| Step | Change | Verification |
| --- | --- | --- |
| 1. The storage maximum comes from the deployment | `ChatStorageProperties` (`memoryos.chat.storage.library-bytes`, default 2 GiB, `0` = no limit); `ChatStorageQuotaService` reads it and loses its administration methods; `JdbcChatStorageQuotaRepository` and `ChatStorageQuotaController` deleted; `V104` drops `chat_storage_quota` and `chat_settings.storage_quota_bytes` | `ChatSessionApiIntegrationTest.theDeploymentsStorageLimitIsShownToItsOwnerAndRefusesAnUploadBeforeItIsAuthorized` — every member reads the same limit, the administration routes are gone (404), an upload past the ceiling is refused before storage is asked |
| 2. Retention belongs to the owner | `chat_preferences.retention_days` (`V104`, carrying over any Tenant policy); `JdbcChatPreferencesRepository.retentionDays`; `ChatRetentionService`; `ChatRetentionController` at `/api/chat/retention`; retention removed from `ChatSettingsService`, `ChatSettingsEntity`, `ChatSettingsController`, `ChatSettingsResponse` and `AuditAction` | `ChatSessionApiIntegrationTest.retentionIsTheOwnersOwnSettingAndCountsWhatItWouldDeleteFirst`; `ChatSessionPurgeIntegrationTest.theOwnersRetentionPolicyDeletesWhatNobodyHasTouchedAndCountsItFirst` and `oneMembersRetentionPolicyLeavesAnotherMembersConversationsAlone` |
| 3. The worker applies each person's number | `JdbcChatSessionPurgeRepository.policies/applyRetention/affectedByRetention` scoped to `owner_actor_id`; `ChatSessionPurgeService` logs per owner | The two purge cases above; `OpenApiContractTest` for the moved paths |
| 4. The person's storage page | `/settings/storage` + `ChatStoragePage` over a shared `StorageMeter`; `LIBRARY_CATEGORIES` and `CATEGORY_ICONS` shared with the library; `?category=` on `/library`; the two administration pages, their routes and their sidebar tabs removed; `Progress` forwards `value` | `chat-storage-page.test.tsx` (4); `chat-library-page.test.tsx` (17) |
| 4b. The same meter on the library | `LibrarySettingsButton` opens a library settings panel holding the meter (filtering in place), the trash window and the retention section; library rows and the sort control were reworked with it | `chat-library-page.test.tsx` — "keeps the library's own settings on the library" |
| 5. The person's retention section | `ChatRetentionSection` in `/settings/chat` with presets, a typed number, the count and a confirm dialog | `chat-retention-section.test.tsx` (4) |
| 6. A purged artifact keeps its card | `V105` (nullable storage columns, `purged_at`, claim indexes replaced); `JdbcChatArtifactCleanupRepository.remove` tombstones; library listing, restore and re-purge exclude tombstones | `ChatArtifactCleanupIntegrationTest.aPurgedArtifactKeepsItsCardOnTheAnswerAndLeavesTheLibrary`; `chat-images.test.tsx` (3) |
| 7. Documents | This increment, `docs/specs/chat.md`, `docs/tests/chat.md`, `AGENTS.md`, `docs/roadmap.md` | Reviewed with the change |

## Acceptance

- [x] No route, page or table lets anyone set another person's storage limit; the number comes from the environment.
- [x] `/settings/storage` shows used against the maximum, per category, and leads into the library.
- [x] A member sets, changes and clears their own retention; the count of what it would delete is shown first.
- [x] One member's policy never touches another member's conversations.
- [x] An answer still says an image or generated file was deleted after the bytes are released.
- [ ] Staging: upload past the configured ceiling, then the meter and the category rows against real MinIO bytes.
- [ ] Staging: the worker deletes a conversation past its owner's retention window, with the log line naming counts only.
