# Plan — MEM-250 User memory

Design: [design.md](design.md).

Status 2026-10-13: steps 1–7 implemented on `dathip04/mem-250-user-memory`. Verified on the staging host in a JDK
container against Testcontainers PostgreSQL: `ChatUserInformationTest` (2), `ModulithArchitectureTest`,
`CoreDependencyRulesTest` (2), the two memory cases of `ChatSessionApiIntegrationTest` and `OpenApiContractTest`
(which regenerated `openapi.yml`, applied as additions only). Web: `typecheck`, `lint`, `check:i18n` and the
settings and activity Vitest files (28 tests) pass locally. The full `clean check` is left to CI. The Settings
section was reviewed in screenshots at 1440 and 390 px against the fixture backend with a realistic memory;
the staging acceptance below is open.

Merged 2026-10-13 in PR #633 (merge `08d34369`; CodeRabbit's two findings, a missing `enabled` read as off and a tool
write after the switch was turned off, fixed in `cd7c725f`). A write gate after Web or MCP content was built and
reverted on the owner's decision (design, "Outside content in the turn"). Main's MEM-246 channels arrived meanwhile,
so channel conversations keep memory out as well. Closed 2026-10-14.

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

- [x] A fact a member states in one conversation shapes an answer in the next one, without their repeating it.
- [x] The member sees, edits and clears the whole memory in Settings › Chat, and turning it off stops reading and writing.
- [x] A temporary conversation neither reads nor writes memory.
- [x] A new member has memory on without visiting Settings.
- [x] No memory text appears in logs, the activity timeline or a shared conversation.
- [x] Staging: three conversations on the staging Chat model, then the memory page shows what was saved and nothing taken from a searched document.

## Staging acceptance — 2026-10-14

Run through the API as the benchmark `hr` role, with the Tenant's answer-from-documents setting switched off for the
run and restored after it, the role's memory restored and the test conversations deleted. Scenarios: A states a role,
a deadline and a style; B asks for help in a new conversation; C asks about a document (search); D is temporary; E runs
with memory off.

| Model | A write | B use | C no document text | D temporary | E off |
| --- | --- | --- | --- | --- | --- |
| 9Router `cx/gpt-6-luna`, before its fix | pass | **fail** | pass | pass | pass |
| 9Router `cx/gpt-6-luna`, 9Router 0.5.99 with decolua/9router#4813 | pass | pass | pass | pass | pass |
| Claude `claude-haiku-5-5` | pass | pass | pass | pass | pass |
| OpenAI `gpt-6-luna` (Responses) | pass | pass | turn failed | pass | turn failed |

- **B on 9Router** failed because its Chat-to-Responses translator kept only the first system message, and Chat sent two:
  the tool guidance first, the agent prompt with `# User Information` second. The model did not know the date, the
  email or the memory. Fixed on both sides: 9Router (our build, upstream PR decolua/9router#4813) and MemoryOS
  ([MEM-254](https://linear.app/memory-os/issue/MEM-254), PR #637: one system message).
- **C and E on OpenAI** failed after `search_knowledge` with a provider refusal unrelated to memory
  ([MEM-255](https://linear.app/memory-os/issue/MEM-255)).
- With the Tenant's answer-from-documents setting on, a personal statement is answered "the documents do not cover
  it" without any tool, so memory is never written in that mode. Left as an owner decision.

Not built: a persistence test that a write appears in the next turn's request and an executor-level registration test;
the API cases and the staging runs cover both behaviours.
