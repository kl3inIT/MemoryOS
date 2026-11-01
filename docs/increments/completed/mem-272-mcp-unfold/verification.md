# Verification

Staging, release `d7f8cd1f` (MEM-260 and MEM-272 together), 2026-10-15 16:27–16:30 UTC. The owner benchmark role ran
one conversation per model, three turns each, with Linear (76 enabled tools) selected. Each line is one
`ai.model.call.completed` record.

| Call | Model | Input | Cache read | Cache write | Tools | System fingerprint |
| --- | --- | --- | --- | --- | --- | --- |
| Turn 1, first | Claude Haiku 5.5 | 6894 | 0 | 6890 | 4 | `863897805cbc` |
| Turn 1, after opening `list_teams` | Claude Haiku 5.5 | 7526 | 0 | 7384 | 5 | `863897805cbc` |
| Turn 2, first | Claude Haiku 5.5 | 6894 | **6890** | 0 | 4 | `863897805cbc` |
| Turn 2, same tool opened again | Claude Haiku 5.5 | 7531 | **7384** | 0 | 5 | `863897805cbc` |
| Turn 3, first | Claude Haiku 5.5 | 7125 | **7065** | 56 | 4 | `863897805cbc` |
| Turn 3, after opening `search_documentation` | Claude Haiku 5.5 | 7479 | 0 | 7326 | 5 | `863897805cbc` |
| Turn 3, next call | Claude Haiku 5.5 | 13972 | **7326** | 0 | 5 | `863897805cbc` |

luna (`cx/gpt-6-luna`) carried the same system fingerprint on every call, started each turn with 4 tools and about
6.3k input tokens, and read 3840 to 5888 tokens from the cache per call.

## Outcome

- **Input per call.** A turn starts with 4 tools: three of MemoryOS's own plus `mcp_linear`. Its first call carries
  6894 input tokens on Claude, against 35474 with the 64 Linear tools before
  ([MEM-269 verification](../mem-269-prompt-cache/verification.md)).
- **Opening.** Opening adds the named tools beside the opener (5 tools). The opened tools ran: `list_teams`,
  `list_issues` and `search_documentation`. `search_documentation` lies past the old 64-tool cut, so it could not be
  offered before.
- **Cache.** An opening loses the cached prefix once, and the next call reads it again (turn 3: 0, then 7326). Opening
  a tool set the conversation already opened is read from the cache at once (turn 2: 7384).
- **Approval through the opener** (16:34 UTC, Claude, session `94d62ec3`).
  - Asked to comment on MEM-272, the model opened `save_comment` through `mcp_linear`.
  - The stream carried `tool_approval` with the server, the tool and its arguments, and an API decision approved it.
  - The call ran, and the step completed as `mcp_linear_save_comment`. The test comment was deleted afterwards.
  - `alwaysAllowable` was false, as designed: Linear marks its `save_*` tools `destructiveHint: true`, and a destructive
    tool never runs unasked (MEM-264).
- **Unrelated failure.** Claude's second turn failed with `AnthropicIoException` (connection reset by the provider
  mid-stream), after its tool calls.
