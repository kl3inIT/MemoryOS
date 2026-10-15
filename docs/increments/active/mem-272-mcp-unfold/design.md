# MCP tools opened on demand

Linear: [MEM-272](https://linear.app/memory-os/issue/MEM-272). Plan: [plan.md](plan.md). Follows
[MEM-264](../mem-264-mcp-tool-policy/design.md) (tool policy) and
[MEM-269](../../completed/mem-269-prompt-cache/design.md) (prompt cache).

## Problem

With Linear selected, every model call carried 64 tools, about 30k tokens on Claude
([MEM-269 verification](../../completed/mem-269-prompt-cache/verification.md)). The other 12 enabled tools were cut in
alphabetical order. From the second call on, the prompt cache carries most of that cost. Three costs remain:

- the first call of a conversation pays in full, and Claude bills that write at 1.25 times input;
- a second large server cannot fit under the cut;
- the model chooses among dozens of tool definitions.

## Decision

Use Embabel's progressive disclosure as it ships: `UnfoldingTool.selectable` and the default
`UnfoldingToolInjectionStrategy` of the streaming tool loop. MemoryOS writes no dispatcher.

- **Small server.** A server with at most 12 enabled tools offers them directly, as before.
- **Large server: one opening tool, `mcp_<slug>`.**
  - Its description names every enabled tool by its MCP name.
  - Its input is `tools`: one to 12 of those names, as a JSON Schema enum.
  - A call with names makes Embabel replace the opening tool with those tools, built exactly as the direct ones, so
    policy, approval cards, steps and metrics are unchanged.
  - The selection returns the opening tool with them, so the model can open more later in the same turn. Unknown names
    open nothing.
- **No per-turn cap.** The 64-tool cut, `McpTurnTools.Omitted` and its notice to the model are gone. A turn still
  takes at most 8 servers.
- **Steps.** An opening call is no step of its own: `ChatToolActivity.quiet` skips the opening tools' names, and the
  calls of the tools they open are the steps people see.

The list a turn starts with is the same from turn to turn, so it stays in the prompt cache. Opening tools changes the
list, which loses the cached prefix **once** per turn that opens a server. The later calls of that turn read it again
(MEM-269 probe: a list back to a cached state is read whole).

## Not in this increment

- **Keeping the cache across an opening.** Anthropic `tool_addition` (beta `inline-tools-2026-09-15`) and OpenAI
  Responses `additional_tools` both kept the cache in a probe on 2026-10-15: Claude read 13056 of 13247 tokens, OpenAI
  5923 of 6090. They would add opened tools inside the conversation instead of the top-level list. Using them needs the
  adapters to render injected tools themselves; it waits until per-call logs show the one miss matters.
- Search across servers (BM25 or embeddings).
