# Chat internal-document grounding

## Problem

On 2026-09-18 the builtin agent received the Vietnamese request “cam kết bảo mật thì cam kết cái gì đấy theo các tài liệu đã có”. The three relevant SharePoint Documents were Search-ready and a direct OpenSearch query returned the agreement passages, but the selected model made no `search_knowledge` call. The completed assistant message persisted no activity steps and no sources, then answered from general knowledge.

The observable contract is therefore narrower than generic RAG quality: when a user explicitly asks for an answer according to existing, connected or internal documents, silently substituting model knowledge is incorrect. The answer must first search the authorized knowledge base, then cite returned evidence or state that grounded evidence was not found.

## Reference and scope decision

Onyx snapshot `06aa2b0`, `backend/onyx/prompts/tool_prompts.py`, gives the model general advice to search statements that may refer to a document. `backend/onyx/chat/llm_loop.py` otherwise uses automatic tool choice; only a client-supplied `forced_tool_id` narrows the first cycle to one tool and marks tool choice required. MemoryOS ported the automatic prompt behavior but does not currently expose Onyx's forced-tool control.

Spring AI's portable `ChatOptions` has no tool-choice field. OpenAI and Anthropic expose different provider-specific forcing APIs. Adding provider branches would make identical agent behavior depend on the selected model, while adding the full Onyx forced-tool request/UI contract would introduce a new durable Chat command and composer mode beyond this demonstrated regression.

This increment retains automatic tool choice and strengthens the provider-neutral internal-search instruction: an explicit request to answer from existing/connected/internal documents, a named internal Source/provider, or a named document is never treated as an existing-knowledge question. `search_knowledge` must run before answering; missing evidence must be reported rather than replaced with general knowledge. The instruction is present only when the callable internal-search tool is present, so agents that disable Search and Web-only configurations never receive an impossible instruction.

This is an intentional small departure from the exact Onyx prompt text, justified by the reproduced live-model failure. It does not claim that a natural-language instruction is equivalent to protocol-level forced tool choice. A future forced-tool control requires its own accepted command, replay and browser contract.

## Verification

- A focused prompt test proves internal-only and combined tool prompts carry the mandatory grounding rule.
- The same test proves Web-only and tool-disabled prompts do not advertise internal Search.
- Existing prompt tests preserve Persona instructions, one Tools heading, last-cycle behavior and actual-tool discovery.
- The checked-in Gradle gate remains the repository-wide verification boundary.
- Live acceptance repeats the Vietnamese question with Search-ready SharePoint documents and requires a `search_knowledge` activity plus at least one persisted citation. A model answer without those facts remains a failed acceptance result even if its prose is plausible.

## Out of scope

- Automatic routing among agents.
- A new forced-tool API or composer toggle.
- Retrieval ranking, SharePoint ingestion, Search projection or document parsing changes.
- Treating uncited model prose as grounded evidence.
