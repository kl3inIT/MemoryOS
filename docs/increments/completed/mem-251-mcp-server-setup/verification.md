# Verification

Staging, 2026-10-15.

- **OAuth with automatic discovery.** Linear was added through `/admin/mcp` with automatic discovery and a client
  metadata document (CIMD). It connected at 06:24 UTC after the empty-`scope` fix (PR #652) and listed 76 tools.
- **Refresh.** Linear issues 24-hour access tokens. At 16:39 UTC the stored expiry was set to the past, and the next turn
  refreshed the token: credential revision 1 → 2, new expiry 2026-10-16 16:35 UTC, status `ACTIVE`. The turn then
  called `list_teams` and completed. This is the path an hour-old Google token takes.
- **Not run.** Google Drive MCP on staging: the deployment has no Google OAuth client for it. Owner review of the
  screens was deferred by the owner on 2026-10-15 ("ui ux để sau"); it is [MEM-274](https://linear.app/memory-os/issue/MEM-274).
