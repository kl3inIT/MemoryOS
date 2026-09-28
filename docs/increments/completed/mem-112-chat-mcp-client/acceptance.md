# MEM-112 live acceptance

What must be observed against the official Google Drive MCP server before this increment is finished. Nothing
here can be proved by the test suite: every step needs a real Workspace organization, so the owner runs it and
records the receipts in [verification.md](verification.md).

Staging already holds `MEMORYOS_MCP_CREDENTIAL_ENCRYPTION_KEY` and
`MEMORYOS_MCP_REDIRECT_URI` (`https://memoryos.72-62-193-33.nip.io/login/oauth2/code/mcp`). Preparation of the
OAuth applications is in the [MCP server runbook](../../../runbooks/mcp-servers.md).

## Preparation

Two Workspace organizations, A and B, each with an `Internal` Web application whose authorized redirect URI is
exactly the staging callback, with the Drive API and Drive MCP API enabled. The Drive MCP server is part of the
Google Workspace Developer Preview Program, so each project must be enrolled first. Google's configuration guide
lists the scopes `https://www.googleapis.com/auth/drive.readonly` and `https://www.googleapis.com/auth/drive.file`.

In MemoryOS: one server, `OAUTH` + `PER_USER` + `AUTO_DISCOVERY`, URL `https://drivemcp.googleapis.com/mcp/v1`,
scopes as the discovery review suggests, and additional authorization parameters
`access_type=offline` and `prompt=consent`. Add both applications under **Ứng dụng OAuth**, labelled by
organization.

## Observations to record

| # | Step | What to record |
| --- | --- | --- |
| 1 | Run discovery on the server | The issuer and endpoints Google returns, and whether `client_id_metadata_document_supported` is present |
| 2 | Refresh tools | The tool names, and their `readOnlyHint`/`destructiveHint` annotations. Phase 0 left this an evidence gap |
| 3 | A member of organization A connects, choosing label A | The connection succeeds and a refresh token is stored (the credential survives step 6) |
| 4 | The same person connects choosing label **B** | Expected: Google refuses with `org_internal`. Record the exact error the person sees |
| 5 | Ask a question that uses a read tool | The tool runs and the answer cites what it returned |
| 6 | Wait past the access-token lifetime, ask again | The turn refreshes silently. If it instead asks the person to reconnect, `access_type=offline` did not take effect |
| 7 | Ask for `create_file` in a dedicated empty test folder | The file appears. Do not run this against real content. The published tool set has no tool that edits an existing file (write tools are `create_file` and `copy_file`) |
| 8 | A member of organization B connects with label B | Succeeds independently of A's connection; the two credentials do not interfere |
| 9 | Disconnect from the composer | The credential is gone and Google shows the grant revoked |

## Open questions this settles

- **Does Google accept the RFC 8707 `resource` parameter?** MCP requires clients to send it to every
  authorization server. If Google rejects the authorization or token request because of it, observed behaviour
  wins over the specification and the departure is recorded in [design.md](design.md).
- **Do the shipped per-turn bounds fit?** `mcp-call-limit: 10` and `mcp-call-timeout: 60s` were chosen without
  measurement. Record the observed call count for a realistic question and the slowest call, then set both.
- **How large are the tool schemas?** They count against the turn's input tokens; record the total for the
  enabled set.
