# Verification

Staging, 2026-10-15.

- **Tools listed at connection.** A fresh Linear connection (06:24 UTC) filled the tool list by itself: 76 tools, no
  manual refresh.
- **Default policies without manual changes.** No tool is customized. Read-only tools run; tools that change data ask
  first; Linear's `save_*` tools are destructive (`destructiveHint: true`) and ask on every call, including through the
  opener (`save_comment`, [MEM-272](../mem-272-mcp-unfold/verification.md)).
- **Reset.** "Always allow" turned `mark_notification` into `ALWAYS` (customized). Resetting the server's policies
  (`DELETE …/tools/policy`) returned it to `ASK`, not customized.
