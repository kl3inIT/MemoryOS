# Implementation plan

Design: [design.md](design.md).

## 1. Opening tools

- [x] `McpTools.tools()` groups bindings by server: at most 12 direct, else one `UnfoldingTool.selectable` named
  `mcp_<slug>` whose selection returns the named tools and itself.
- [x] `McpTools.openers()`; `ChatToolActivity.quiet` skips their calls; the executor wires both.
- [x] `McpTurnService` drops `MAX_TOOLS` and `McpTurnTools.Omitted`; the notice no longer names cut tools.

## 2. Verification

- [x] `McpToolsTest`: a large server offers one opener naming its tools; a selection opens the named tools and the
  opener; unknown names open nothing; an opener call is no step.
- [x] Staging with Linear (76 tools), [verification](verification.md): the first call is far below 35k tokens on Claude;
  a tool past the old cut opens and runs; the per-call log shows one cache miss per opening.
- [x] Staging: an opened write tool (`save_comment`) asks for approval, is approved and runs.
- [x] Spec `chat`, runbook `mcp-servers`, roadmap.
