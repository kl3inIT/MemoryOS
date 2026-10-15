# Implementation plan

Design: [design.md](design.md). Rides on the MEM-251 pull request.

## 1. Composer

- [x] "Ứng dụng" in the `+` menu with the marks of the apps on; the submenu with logos, state, switch or Connect,
  and a link to Settings › Connections.

## 2. Levels per tool

- [x] V164 `mcp_server_tool.approval_required`, set for tools not declared read-only; a newly listed tool that
  may change data starts off and asks once turned on.
- [x] `PUT /api/mcp/servers/{s}/tools/{t}/approval` at the tool's revision, audited; `approvalRequired` on the tool
  view.
- [x] The server page sets each tool to Luôn cho phép / Hỏi trước / Tắt, grouped into read-only tools and tools
  that may change data.

## 3. The turn waits

- [x] `McpTurnTools.Approvals`; `McpTools` asks before a call that requires approval and returns a declined or
  no-decision result to the model; `DECLINED` and `NO_DECISION` step failures.
- [x] `ChatTurnService`: the `tool_approval` stream event before and after, the wait bounded by ten minutes, Stop
  declines, `decide` for the reply's owner; `POST .../tool-calls/{callId}/decision`.
- [x] `McpToolsTest` for approved, declined and no decision.
- [x] A `ChatTurnServiceTest` for decide, Stop while waiting and the second decision refused (run on zm).
- [x] OpenAPI contract and web client regenerated (the changed paths and schemas only).

## 4. The card

- [x] The AI SDK approval gate on the tool part, assistant-ui's Approval card with each argument, and
  `onRespondToToolApproval` to the decision endpoint; a reloaded answer shows the step's declined or no-decision line.

## 5. Documents and checks

- [x] Chat spec (MCP tools, streaming), matrix, roadmap.
- [ ] CI gates; staging with Linear's `create_issue` asking first.
