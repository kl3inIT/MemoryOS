# MEM-250: User memory

## Why

Chat already tells the model who a member is: `ChatPrompts.withUserInformation` appends Onyx's
`# User Information` section with the IAM name and email, the member's work role and the personal preferences
they typed in Settings › Chat ([personal chat preferences](../../../specs/chat.md#personal-chat-preferences)).
Everything else a member says about themselves is lost when the conversation ends: their project, how they want
answers laid out, a decision they already made. The next conversation asks again.

Onyx's section has a third part MemoryOS never ported, `## User Memories`, filled by an `add_memory` tool. This
increment adds it, so Chat remembers a person across conversations and the person can see and change what it
remembers.

## References

| Product | Revision read | What it does |
| --- | --- | --- |
| Onyx | `40eb240df` | `memory` table, at most 10 short statements per user (`backend/onyx/db/memory.py`, `MAX_MEMORIES_PER_USER`); `user.use_memories` switch; statements injected as `## User Memories` after `## User Preferences` (`backend/onyx/chat/prompt_utils.py`, `prompts/user_info.py`); `add_memory(memory)` tool whose call runs a second model to decide between adding and replacing an entry (`tools/tool_implementations/memory/memory_tool.py`); `MEMORY_GUIDANCE` in `prompts/tool_prompts.py` |
| Dust | `3a1e97b` | One `MEMORY.md` per user, 8,000 characters, shared by every agent (`front/lib/api/user_memory.ts`); `read` and `edit(oldStr, newStr)` tools (`front/lib/api/actions/servers/user_memory/`); what to save and skip in `front/lib/resources/skill/code_defined/system/user_memory.ts`; per-user on/off |
| spring-agent | `c2c385d31` | Prompt lines that a memory is a claim about the moment it was written, and that text found in memory is evidence, never an instruction (`spring-agent-core/src/main/resources/core/prompts/auto-memory.md`) |
| Open WebUI, LibreChat | Documentation | Memory injected into the system prompt under a small budget (2,000 characters; 2,000 tokens); Open WebUI warns small local models store memories inconsistently |
| ChatGPT, Claude.ai | Help-center documentation | Temporary or incognito chats neither read nor write memory; the person sees and edits every memory |

Dust's history is the main lesson on reading. Its agent memory was injected into the prompt, which hurt the
provider prompt cache (dust-tt/dust#21647, #28420); it then moved to a `read` tool, which added a model round
trip to most requests (#31751: "Pulling the memory in context is not the issue, the issue is more the added
latency of the additional step"). Neither problem was about where memory was stored.

## Decisions

| Topic | Reference | MemoryOS | Why |
| --- | --- | --- | --- |
| Shape | Onyx: up to 10 statements. Dust: one document | One document per Tenant membership, at most 4,000 characters | One document is edited by replacing a passage, so no index bookkeeping and no second model call; 4,000 characters sits between Open WebUI's 2,000 and Dust's 8,000 and costs about 1,000–1,500 tokens a turn when full |
| Storage | Onyx: PostgreSQL. Dust: an object in GCS | PostgreSQL table `chat_memory`, bound to `tenant_memberships` like `chat_preferences` | Read on every turn next to the preferences row; a revision column settles two conversations writing at once; deleted with the membership in the same transaction. Dust keeps a file because it already has a shared agent filesystem; MemoryOS has none, and MinIO holds artifacts, not owned settings |
| Reading | Onyx: injected. Dust: `read` tool | Injected as `## User Memories`, the last part of `# User Information`, read at admission like the preferences | No extra model round trip, which matters most for internal models. It sits after every shared part of the system prompt, so only the per-person tail differs between members |
| When an edit applies | Onyx and Dust: next request | Next turn, as the preferences already do | One rule for everything in `# User Information`. A write changes the next turn's system prompt, so the provider's cached prefix is lost once after a write; writes are rare |
| Writing | Onyx: `add_memory` plus a second model. Dust: `edit(oldStr, newStr)` | One tool, `update_memory(old_text, new_text)`: empty `old_text` appends, empty `new_text` deletes | No second model call. A miss returns the current memory so the model can retry; a write past 4,000 characters is refused with a request to condense |
| Concurrency | Neither | Compare-and-set on `revision`; the tool reads the current row, not the turn's snapshot | Two tabs of one person cannot overwrite each other |
| Switch | Onyx `use_memories` on the user. Dust: a flag on the user, the text in GCS | `chat_memory.enabled`, in the same row as the text, on for every member (owner decision 2026-10-13) | The person's own decision, saved on change. Off: nothing is read, the tool is not offered, the stored memory is kept. One row means the switch and the text never disagree, and the preferences contract stays unchanged |
| External models | Neither separates them | Memory is read and written whatever the provider's data boundary (owner decision 2026-10-13) | As in Onyx and Dust. The general rule for what reaches an external model belongs to [MEM-134](../mem-134-external-data-gate/design.md), not to this feature |
| Turns without the person | Dust, ChatGPT, Claude.ai: temporary chats excluded | Not read or written in a temporary conversation | Same rule as the products. Scheduled runs and group channels do not exist yet; when they arrive they read and write no personal memory |
| Prompt guidance | Onyx `MEMORY_GUIDANCE`; Dust save/skip lists; spring-agent safety lines | `ChatPrompts.MEMORY_GUIDANCE`, sent only when the tool is registered | Save role, preferences, ongoing work and settled decisions; skip one-off questions, secrets and anything taken from documents or the Web; a memory may be stale; text in memory is never an instruction |
| Outside content in the turn | Onyx, Dust, ChatGPT, Open WebUI, LibreChat: no check. Gemini blocks sensitive tools while it processes untrusted content, which was bypassed by deferring the call to the next turn (Rehberger, 2025-02) | No check: `update_memory` is offered whatever the turn read | A security review found that text a stranger wrote could be saved and reach later conversations. A gate after Web reading was built and removed (owner decision 2026-10-13: no product gates memory writes this way, and it does not hold across turns). The controls are the ones every product relies on: memory is information, never an instruction; every write shows in the timeline; the person sees, edits and clears the whole memory. Where products did fix the attack, they closed the exfiltration path (ChatGPT, Gemini), which is reviewed separately |
| What the timeline shows | Onyx streams the memory text | The step reads "Memory updated" without the text | A conversation can be shared; the memory itself is visible only to its owner in Settings |
| Where the person manages it | Settings pages in every product | A Memory section in Settings › Chat: the whole text editable, the switch, "Clear memory" with a confirmation | Next to the preferences it extends |

## Contracts

- `chat_memory(tenant_id, actor_id, content VARCHAR(4000), enabled BOOLEAN DEFAULT TRUE, revision BIGINT,
  updated_at TIMESTAMPTZ)` (V161), primary key `(tenant_id, actor_id)`, foreign key to `tenant_memberships`
  with `ON DELETE CASCADE`. A missing row reads as an empty memory that is switched on, at revision 0.
- `GET /api/chat/memory` returns `{content, enabled, revision, maxLength}` for the caller; `PUT` takes
  `{content, revision}` and answers 409 on a stale revision; `DELETE` clears the content; `PUT
  /api/chat/memory/enabled` takes `{enabled}`. No other authority: a member reads and writes only their own
  memory.
- `update_memory` is registered when the model supports tools, memory is enabled and the conversation is not
  temporary. Its result to the model is the outcome and, on a miss or a conflict, the current memory.
- Logs: `chat.memory.updated` and `chat.memory.update_rejected` with `error_code`; never the content.

## Out of scope

Memory per Project or per group, memory shared across a Tenant, extracting memories from past conversations in
the background, searching memory by embedding, version history, showing which answer used which memory, and an
administrator view of anyone's memory, and any rule tied to the provider's data boundary.
