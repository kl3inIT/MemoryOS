# MEM-153 — plan

One pull request for the whole issue, committed feature by feature. Every step keeps `./gradlew clean check` green.

## Phase 1 — ordering conversations (done)

1. V97: `archived_at`, `branched_from_*`, partial indexes for the sidebar and the archive. ✔
2. Repository, service and API for archive, unarchive, archive-all and `?archived=true`; a turn in an archived conversation unarchives it. ✔
3. `ChatBranchService`: the selected path copied into a new conversation, with each generated file and image written as the branch's own object; bounds and refusals as designed. ✔
4. Web: the conversation menu's Archive/Unarchive, the thread-list adapter's real archive hooks, the archived page, the Ctrl/⌘+K mark, Archive-all in Settings, the branch action on a message and the origin link in the header. ✔
5. `ChatLifecycleIntegrationTest`, the adapter test and the archived-page test. ✔

## Phase 2 — deletion and retention (done)

6. `deleted-after` on the purge claim, with the waiting count in its log line. ✔
7. V98: `chat_session.temporary`, `chat_user_file.temporary_session_id`, `chat_settings.chat_retention_days`, and the indexes each sweep reads. ✔
8. Temporary conversations: created with the flag, left out of every listing, refused a share link and a Project, expired by `memoryos-chat-temporary-session-v1`, purged with their uploads. ✔
9. The Tenant policy: read/save/preview under `MODELS_MANAGE`, applied by `memoryos-chat-retention-policy-v1`. ✔
10. Web: the temporary-chat toggle with its one-time explanation, the header mark and banner, and `/admin/chat-retention` with its before-you-save count. ✔
11. Purge tests for the window, the temporary lifecycle and the policy. ✔

## Phase 3 — taking data out (done)

12. V99 `chat_export` plus the repository, with the partial unique index that admits one export per person. ✔
13. `ChatExportService`: transcripts as JSON and as an escaped page, then files until the 30 MiB budget runs out, with what was left out named; lease, attempts, expiry and the byte sweep as the library archive has them. ✔
14. `memoryos-chat-export-v1`, the export routes, and the Settings section that asks, polls and downloads. ✔
15. `ChatExportIntegrationTest` and the export-section test. ✔

## Verification

- `./gradlew clean check` for the repository gate; `web`: `tsc -b`, `vitest run`, `oxlint --deny-warnings`, `oxfmt --check`, the i18n audit.
- Staging, once merged: archive and unarchive a conversation, branch one that holds a generated file and delete the origin, hold a temporary conversation and watch it and its upload go, set a retention policy on a spare Tenant, and open an export's HTML in a browser.
