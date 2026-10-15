# Chat on more of assistant-ui: approval answers, context use, answer timing

Linear: [MEM-267](https://linear.app/memory-os/issue/MEM-267). Plan: [plan.md](plan.md). Follows
[MEM-253](../mem-253-tool-approval/design.md) and [MEM-264](../mem-264-mcp-tool-policy/design.md). Follow-up
suggestions are [MEM-266](https://linear.app/memory-os/issue/MEM-266).

## Problem

An audit of the chat against assistant-ui 0.15.22 (2026-10-15) found three places where the library already does
what people miss:

- The approval card offered only Allow and Decline. An answer from another tab or a replay after a reconnect did not
  reach the card, a call nobody answered in ten minutes kept live buttons, and a reloaded answer showed a declined
  call as a line inside the collapsed thinking.
- A conversation that outgrew the model's context failed the turn with `CHAT_CONTEXT_LIMIT`, with no warning before.
- An answer showed only its time.

## Decision

### Approval answers

The tool part's approval gate carries what assistant-ui and the AI SDK define:

- The waiting `tool_approval` event says `alwaysAllowable` when the reply's owner manages MCP servers and the tool asks
  first without being destructive. The transport then declares the options `allow-once`, `allow-always` and
  `reject-once`, and the card shows "Luôn cho phép". The answer `optionId: "allow-always"` posts
  `alwaysAllow: true`; the server sets the tool's policy to `ALWAYS` (customized, audited) before it approves the call,
  so a refused change leaves the call waiting.
- The decided event becomes a `tool-approval-response` chunk (`APPROVED`, `DECLINED`), or the gate again with
  `resolution: "expired"` (`NO_DECISION`), so the card settles wherever the answer came from.
- History rebuilds a `DECLINED` or `NO_DECISION` step as an `output-denied` part carrying its gate, so a reloaded
  answer shows the card's receipt in the answer. An approved call that ran stays an ordinary step: approval is not
  stored on the step.

Only an administrator gets "always allow", since a tool's policy is the organization's; a per-person policy stays out
of scope.

### Context use

The turn's model guard already estimates the input of each call against the model's input limit. Its last call's
estimate and the limit are stored on the answer (`chat_message.context_tokens`, `context_limit`, V168), sent on the
`outcome` event and the history message, and the composer shows them with assistant-ui's Context display element (the
ring preset), amber from 65% and red above 85%. The turn's token totals sum every call, so they would overstate a turn
that called tools; research turns record nothing.

### Answer timing

The answer's time on hover adds `useMessageTiming()`: time to first token, total time and tokens per second, which the
AI SDK runtime measures while it streams. A reloaded answer has only its time.

## Not in this increment

- Follow-up suggestions (MEM-266), which cost a model call per answer.
- A per-person "always allow".
