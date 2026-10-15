# Verification

Staging, 2026-10-15, `cx/gpt-6-luna`, with Linear selected, as the owner (MCP manager).

- **Always allow.** `mark_notification` (write, not destructive) waited with `alwaysAllowable: true`. The answer
  `approved: true, alwaysAllow: true` ran it and stored the tool's policy as `ALWAYS` (customized).
- **No second question.** The next turn of the same conversation called `mark_notification` again with no
  `tool_approval` event and completed. The notification was set back to unread and the policy reset to its default.
- **Context use.** The answers stored their context use, and the second turn's figure was larger than the first:
  5774 then 9937 of 257427 tokens. On Claude, 5175 then 9287 of 949027. A failed turn stores none.
- A destructive tool (`save_comment`) offered no "always allow" (`alwaysAllowable: false`), as designed.
