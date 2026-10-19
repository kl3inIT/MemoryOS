# Verification

The assistant sidebar, topics, *Gần đây* and history ([design](design.md), [plan](plan.md)), merged in
[#699](https://github.com/kl3inIT/MemoryOS/pull/699), [#704](https://github.com/kl3inIT/MemoryOS/pull/704),
[#706](https://github.com/kl3inIT/MemoryOS/pull/706) and [#709](https://github.com/kl3inIT/MemoryOS/pull/709)
(2026-10-15 to 2026-10-16).

## Tests

On zm, throttled:

- #699: `ChatPersistenceIntegrationTest` 51, `ChatLifecycleIntegrationTest` 5, `ChatExportIntegrationTest` 2,
  `ModelCatalogSelectionTest` 10, `ModulithArchitectureTest`, `CoreDependencyRulesTest`, `ChatSessionApiIntegrationTest`
  98 (4 skipped), `OpenApiContractTest` written then compared, `pnpm --dir web check`.
- #709: `io.memoryos.chat.*` with Modulith and dependency rules, 476 tests; `io.memoryos.api.chat.*` with
  `OpenApiContractTest`, 115 tests; e2e `chat-assistants.spec.ts` and `chat-projects.spec.ts`.

CI ran the full gates on each. The test matrix rows are in [docs/tests/chat.md](../../../tests/chat.md) (MEM-273 rows).

## Staging

The empty-topic rule, the three listed projects and the folding sections were accepted on staging with #709
(decision 14). On 2026-10-19, release `396b72f6`, signed in as `admin@memoryos.local` and `test@gmail.com` through
Keycloak impersonation:

- **Old chats are MemoryOS topics.** The admin has 138 live web conversations from before 2026-10-15, 127 of them on
  the built-in assistant and the rest on other assistants (database count, read-only). The oldest, from 2026-09-09,
  opens as a MemoryOS topic with the MemoryOS sidebar.
- **Opening an assistant opens its latest topic.** After one question in a throwaway assistant, `/assistants/{id}`
  opened that conversation.
- **A new topic shows after the first send** in the assistant's sidebar and under *Gần đây* on the home sidebar.
- **An unshared assistant turns its row unusable.** The member pinned the assistant while it was shared with them;
  after the admin unshared it, `GET /api/chat/assistants` returned `usable: false` and the row read
  "Không còn dùng được". The member's pins were restored.
- **History filters by assistant.** `/history?assistant={id}` listed only that assistant's one conversation, as did
  the API.
- No page errors or 5xx responses; no horizontal scroll at 390 px. Screenshots of the home sidebar, the assistant's
  sidebar and history in light and dark at 1440 px and 390 px.

The throwaway assistant and its conversation were deleted.

## Where conversations went

The repository keeps no release notes. That every web conversation is a topic of its assistant, and a conversation
without one a topic of MemoryOS, is stated in the [chat spec](../../../specs/chat.md) and in #704.
