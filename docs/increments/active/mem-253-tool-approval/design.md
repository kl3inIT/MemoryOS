# Apps in the composer and approval before a tool changes data

Linear: [MEM-253](https://linear.app/memory-os/issue/MEM-253). Plan: [plan.md](plan.md). Builds on
[MEM-251](../mem-251-mcp-server-setup/design.md) and [MEM-252](../mem-252-mcp-catalog/design.md).

## Problem

An MCP tool that sends, edits or deletes runs as soon as the model calls it: an administrator can only switch it
on or off. Gmail, Slack and the other catalog apps make that the difference between "draft this" and "sent". The
composer listed MCP servers as "Công cụ MCP" with text badges and no sign of which were on.

Claude lets a tool be **Always allow / Needs approval / Blocked** (owners set it for the organization). ChatGPT asks
before write actions. Dust pauses its durable agent loop on a `blocked_validation_required` step; Onyx Craft parks
the sandbox request at its egress proxy for up to 180 seconds until the person decides.

## Decision

### Three levels per tool

A tool is **Luôn cho phép**, **Hỏi trước** or **Tắt**: `mcp_server_tool.enabled` stays the on/off switch and
`approval_required` (V163) says whether an enabled tool asks first. Existing tools that may change data become
"Hỏi trước"; a newly listed one starts off and, once turned on, asks first; a read-only one starts on without
asking (MEM-252). The administrator sets the level per tool on the server's page. Narrowing it per person is left
out.

### The turn waits for the person

Neither Embabel nor Spring AI can pause a tool loop and resume it later: Embabel's awaitables rerun the whole
action and drop the loop's messages, which MemoryOS does not persist. MemoryOS therefore keeps the turn RUNNING and
blocks the tool call on its virtual thread, as Onyx Craft parks the request:

1. `McpTools.call` asks the turn's `ToolApprovals` before calling a tool that requires approval.
2. The turn publishes a `tool_approval` stream event with the call, the app, the tool and its arguments (bounded),
   and waits for a decision for at most `memoryos.chat.execution.approval-wait` (10 minutes).
3. `POST /api/chat/sessions/{s}/messages/{a}/tool-calls/{callId}/decision` from the reply's owner completes it;
   Stop completes it as declined. A second `tool_approval` event records the decision, so a reconnecting browser
   and a replay show the card's outcome.
4. Approved: the call runs. Declined or no decision: the model gets "the user declined" (or "no decision") as the
   tool result and answers without it; the step fails with `DECLINED` or `NO_DECISION`.

The lease keeps renewing while the run waits, Stop works as before, and a restart ends the turn as any running
turn ends today. The decision is local to the API process that runs the turn, which is the only one.

In the browser the call is the AI SDK's own approval gate, as assistant-ui's server-side approval gates intend: the
transport turns a waiting `tool_approval` into a `tool-approval-request` chunk on the tool part (the server, tool
and arguments as its `approvalDescriptor`), assistant-ui adds `approval` and `respondToApproval` to the part, the
step renders assistant-ui's Approval card element with each argument as a row, and the runtime's
`onRespondToToolApproval` posts the answer to the decision endpoint, because the server owns the paused call. A
reloaded answer has no gate, only the step's `DECLINED` or `NO_DECISION`.

The arguments are shown to the person because they decide on them; this revises "tool arguments are not streamed"
for an approval only. Turns without a person in the browser (the Lark bot) carry no MCP servers.

### The composer

"Công cụ MCP" becomes "Ứng dụng" in the `+` menu, as Claude and Mistral keep connectors there: the row shows the
logos of the apps on for the conversation, and the submenu lists each app with its logo, name, tool count or what
it needs, a switch or Connect, and a link to Settings › Connections.

## Not in this increment

Per-person narrowing of a level, remembering "always allow" from the card, approval on the Lark bot, and Gmail
write scopes beyond what MEM-252 requests.
