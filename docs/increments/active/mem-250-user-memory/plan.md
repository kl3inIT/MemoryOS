# Plan — MEM-250 User memory

Design: [design.md](design.md).

Status 2026-10-13: steps 1–7 implemented on `dathip04/mem-250-user-memory`. Verified on the staging host in a JDK
container against Testcontainers PostgreSQL: `ChatUserInformationTest` (2), `ModulithArchitectureTest`,
`CoreDependencyRulesTest` (2), the two memory cases of `ChatSessionApiIntegrationTest` and `OpenApiContractTest`
(which regenerated `openapi.yml`, applied as additions only). Web: `typecheck`, `lint`, `check:i18n` and the
settings and activity Vitest files (28 tests) pass locally. The full `clean check` is left to CI. The Settings
section was reviewed in screenshots at 1440 and 390 px against the fixture backend with a realistic memory;
the staging acceptance below is open.

| Step | Change | Verification |
| --- | --- | --- |
| 1. Storage | `V161` creates `chat_memory` with its `enabled` switch (default true); `JdbcChatMemoryRepository` in `chat.persistence` reads one row and writes with compare-and-set on `revision` | Repository integration test: a missing row reads empty; a stale revision changes nothing; deleting the membership deletes the memory |
| 2. Service | `ChatMemoryService`: read, replace a passage (empty old text appends, empty new text deletes), whole-text update from Settings, clear; the 4,000-character bound; typed `BusinessException`s for a missing passage, a stale revision and an over-long memory | Covered in `ChatSessionApiIntegrationTest.theMemoryToolEditsOnePassageAndOnlyInTheOwnersOwnConversations` against PostgreSQL rather than a mocked repository: append, replace, delete, a missing passage and the bound |
| 3. Prompt | `ChatPrompts.withUserInformation` gains the memory and renders `## User Memories` after `## User Preferences`; `ChatTurnPersistence` reads it at admission next to the preferences, and leaves it out for a temporary conversation and a switched-off memory, whatever the provider. Check whether `ResearchExecutor` builds its prompt through the same path and apply the same rule there | `ChatUserInformationTest.theMemoryIsTheLastPartOfTheUserInformation`; the temporary and switched-off omissions through `usableIn` in the API case. Deep research builds its own Onyx research prompts and carries neither the preferences nor the memory, unchanged by this increment. Open: a persistence case that a write appears in the next turn's request |
| 4. Tool | `UpdateMemoryTool` (`@LlmTool update_memory`) registered in `ChatModelExecutor` under the design's conditions; `ChatPrompts.MEMORY_GUIDANCE` sent only with it; the timeline step says "Memory updated" without the text | Registration conditions through `usableIn` in the API case. Tool events carry only the tool name (`ChatToolActivity`), so no memory text can reach the stream. Open: an executor-level test of the registration |
| 5. API | `ChatMemoryController` at `/api/chat/memory` (`GET`, `PUT`, `DELETE`) with `contract/` records; `PUT /api/chat/memory/enabled` for the switch; `openapi.yml` and the Hey API client regenerated | `ChatSessionApiIntegrationTest` cases: a member reads and edits only their own memory, a stale revision answers 409, clearing empties it; `OpenApiContractTest` |
| 6. Web | Memory section in `/settings/chat`: the editable text with its character count, the switch saved on change, "Clear memory" with a confirmation; vi/en strings | Component tests for edit, conflict, clear and the switch; screenshots reviewed before asking the owner |
| 7. Documents | `docs/specs/chat.md` (personal chat preferences and tools), `docs/tests/chat.md`, `docs/roadmap.md`, this increment | Reviewed with the change |

## Acceptance

- [ ] A fact a member states in one conversation shapes an answer in the next one, without their repeating it.
- [ ] The member sees, edits and clears the whole memory in Settings › Chat, and turning it off stops reading and writing.
- [ ] A temporary conversation neither reads nor writes memory.
- [ ] A new member has memory on without visiting Settings.
- [ ] No memory text appears in logs, the activity timeline or a shared conversation.
- [ ] Staging: three conversations on the staging Chat model, then the memory page shows what was saved and nothing taken from a searched document.
