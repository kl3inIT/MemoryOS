# Implementation plan

Design: [design.md](design.md).

## 1. Approval answers

- [x] `McpTurnTools.alwaysAllowable`, set for an administrator and a tool that asks without being destructive.
- [x] `tool_approval.alwaysAllowable`; `decision` accepts `alwaysAllow`; `McpServerService.allowAlways` before the
  approval.
- [x] Transport: options, `tool-approval-response`, `resolution: "expired"`; the runtime passes `optionId`.
- [x] Card: "Luôn cho phép", expired receipt; history rebuilds declined and unanswered calls as gates.

## 2. Context use

- [x] `ModelGuard.contextTokens`, `ModelAccounting.withContext`; V169; `recordContext` after the terminal write.
- [x] `outcome` and history carry `contextTokens` and `contextLimit`.
- [x] Context display element (ring) in the composer.

## 3. Answer timing

- [x] `useMessageTiming` start and total time beside the answer's time; its speed is measured but not shown.

## 4. Verification

- [x] Core: always allow before approving and a refused change keeps the call waiting; context accounting; context
  read back with the answer.
- [x] Web: transport gates and context, history gates, the card's options and expiry; e2e with screenshots.
- [ ] Staging: a Linear write tool answered with "Luôn cho phép" stops asking; the gauge after a long conversation.
