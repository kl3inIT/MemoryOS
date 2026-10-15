# MCP tool policy: defaults from annotations, bulk changes, tools listed on connect

Linear: [MEM-264](https://linear.app/memory-os/issue/MEM-264). Plan: [plan.md](plan.md). Follows
[MEM-251](../mem-251-mcp-server-setup/design.md) and [MEM-253](../mem-253-tool-approval/design.md).

## Problem

Connecting Linear on staging (2026-10-15) showed three gaps:

- Linear lists 76 tools: 41 read-only, 6 that write and 29 that may overwrite or delete (`save_issue` among them).
  MEM-252 started every tool not declared read-only switched off, so the assistant could not create an issue until
  an administrator turned on, one by one, the tools they wanted. The approval card already guards every such call,
  so starting them off guards them twice.
- Tools appear only after an administrator presses "Lấy công cụ" on the server page, even right after a
  successful connection.
- A turn offers at most 64 MCP tools and drops the rest silently, in name order.

## References

Source read on 2026-10-15:

- **Goose** keeps Always allow / Ask before / Never allow per tool; its smart mode allows a tool whose
  `readOnlyHint` is true and asks for the rest (`permission_inspector.rs`).
- **LobeHub** sets `auto` / `needs_approval` / `disabled` per connector tool, changes a whole read, write, update or
  delete group at once (`ToolPermissionGroup.tsx`), resets a connector to its defaults, and keeps the person's
  choices when the tool list is synchronised again (`connector/sync.ts`).
- **LibreChat** sets one approval mode for all tools listed in a server's header menu.
- **Claude** sets read-only tools to Always allow and the rest to Needs approval, by group.
- Every one of them lists tools when the server connects and refreshes them on `tools/list_changed`.

## Decision

### One policy per tool

`mcp_server_tool.policy` is `ALWAYS`, `ASK` or `OFF` and replaces the `enabled` and `approval_required` pair
(V166). `policy_customized` marks a policy an administrator chose.

The tool's **risk** comes from its MCP annotations:

| Risk | Annotations | Default | Allowed |
| --- | --- | --- | --- |
| `READ` | `readOnlyHint: true` | `ALWAYS` | all |
| `WRITE` | anything else | `ASK` | all |
| `DESTRUCTIVE` | `readOnlyHint` not true and `destructiveHint: true` | `ASK` | `ASK` or `OFF` |

A tool whose model-facing name cannot be built stays `OFF`. The MCP specification lets a missing
`destructiveHint` mean destructive; MemoryOS reads only an explicit `true`, as Goose and LobeHub do, so a server
without annotations gets `ASK` for every tool that is not read-only rather than a locked set.

A refresh sets the default on every tool that is not customized, so changed annotations take effect, and keeps a
customized policy, lowering `ALWAYS` to `ASK` when the tool became destructive. V166 keeps what an administrator
had chosen: a read-only tool set to ask or off, or a tool that may change data turned on without approval, becomes
customized; every other tool takes its default, so Linear's 35 tools that change data become `ASK`.

### Changing policies

- One tool: `PUT /api/mcp/servers/{s}/tools/{t}/policy` with the policy, at the tool's revision.
- A risk group: `PUT /api/mcp/servers/{s}/tools/policy` with the risk and the policy; a destructive group refuses
  `ALWAYS`.
- Back to defaults: `DELETE /api/mcp/servers/{s}/tools/policy` clears every customization.

All three are audited as `MCP_TOOL_CHANGE`, which now declares the `policy` and `risk` details; MEM-253's approval
endpoint recorded an undeclared `approvalRequired` detail and failed with a server error, which no one had hit on
staging. They replace the enable, approval and enable-all endpoints, whose only client is the web app.

### Tools listed on connect

`McpServerService.discoverTools` lists a server's tools with the caller's usable credential when the server has no
stored tools yet. The OAuth callback calls it after a connection completes, and the server endpoints call it after
an administrator creates or saves a server with a shared credential. It never fails the request that triggered it:
a failure is logged as `mcp.tools.discovery_failed` and the server page still offers the refresh. A person who is
not an administrator can fill an empty snapshot this way, because the administrator already chose to offer the
server and the defaults decide what the tools may do. MemoryOS's MCP sessions are short-lived, so
`tools/list_changed` is not received; refreshing an existing snapshot stays the administrator's "Làm mới công cụ".

### No silent cut

A turn still offers at most 64 MCP tools, below the provider limits. Tools left out are counted per server and the
model is told which apps lost tools this turn, so the reply can say so. Each turn records the number of MCP tools
offered and the characters of their descriptions and schemas (`memoryos.chat.mcp.tools.offered`,
`memoryos.chat.mcp.tools.definition.chars`), which sizes the later tool search.

### The server page

Tools are grouped Chỉ đọc / Ghi / Ghi đè hoặc xóa. Each group header has a level selector that sets the group, each
tool has its own, a destructive tool offers no "Luôn cho phép", and a customized tool is marked. "Về mặc định"
appears while any tool is customized. The `…` menu's refresh reads "Làm mới công cụ"; "Lấy công cụ" stays as the
next step only when the listing on connect failed. A pending button shows its spinner in place of its leading icon
instead of beside it, which fixes the doubled icon on every such button.

## Not in this increment

- Tool search or deferred loading, which needs its own design.
- A per-person narrowing of a tool's policy.
- Catalog overrides of a tool's risk; added when a catalog app's annotations prove wrong.
