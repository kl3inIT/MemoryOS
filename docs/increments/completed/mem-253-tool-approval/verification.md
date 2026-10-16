# Verification

Staging, 2026-10-15 16:34 UTC, Claude Haiku 5.5, with Linear selected.

- Asked to comment on MEM-272, the model called `save_comment`. Linear marks the tool destructive, so it asks first.
- The stream carried `tool_approval` with the server, the tool and the call's arguments.
- `POST …/tool-calls/{id}/decision` with `approved: true` let the call run, and the reply completed with the step
  `mcp_linear_save_comment`.
- The test comment was deleted afterwards.
