# MCP server runbook

Operating [MCP servers](../specs/chat.md#mcp-tools) in a MemoryOS deployment. Registering a server is an
administrator action under `MCP_MANAGE`; this runbook covers what the deployment must provide first.

## Configuration

| Variable | Where | Purpose |
| --- | --- | --- |
| `MEMORYOS_MCP_CREDENTIAL_ENCRYPTION_KEY` | API only | Base64 random 32-byte AES key sealing OAuth client secrets, header templates and every stored credential. Missing configuration disables MCP secret reads and writes. Preserve it with database backups: changing it without re-sealing makes stored credentials unreadable. |
| `MEMORYOS_MCP_REDIRECT_URI` | API only | The OAuth callback. HTTPS, or loopback HTTP for local development, and it must end with `/login/oauth2/code/mcp`. |

Both live with the other application secrets, not in Compose. The redirect URI belongs to the browser origin,
so it matches `MEMORYOS_GOOGLE_DRIVE_REDIRECT_URI` except for the final path segment.

## Network

Outbound HTTPS is required to each registered MCP server and to its authorization server. For the official
Google Drive MCP server that is `accounts.google.com`, `oauth2.googleapis.com` and `drivemcp.googleapis.com`.
Internal HTTP(S) endpoints are permitted under the accepted endpoint policy; MemoryOS never follows a redirect
to another origin and calls only endpoints an administrator has persisted.

Client ID Metadata Documents are the exception to outbound-only traffic: the authorization server fetches
`<redirect origin>/mcp/oauth/client-metadata.json` from MemoryOS. A deployment without inbound reachability
cannot use that route and should register clients by hand or through Dynamic Client Registration instead. The
document is served only when the redirect origin is HTTPS.

## One OAuth application per organization

A Google `Internal` application authorizes only its own Workspace, so each organization keeps its own OAuth
application and MemoryOS stores several per server, each labelled. People choose the label when connecting.
For each organization:

1. Create an `Internal` Web application in that organization's Google Cloud project.
2. Enable the Drive API and the Drive MCP API in the project, and configure its consent screen.
3. Register `MEMORYOS_MCP_REDIRECT_URI` exactly as the authorized redirect URI.
4. In MemoryOS, open the server under `/admin/mcp`, choose **Nhập ứng dụng**, and paste the label, issuer,
   client ID and secret. The secret is sealed and never shown again.

Google issues a refresh token only when the authorization request carries `access_type=offline`, and on
reconnection only with `prompt=consent`. Put both in the server's additional authorization parameters.
Without them a connection stops working after about an hour and becomes `REAUTH_REQUIRED`.

Workspace administrators may additionally have to trust the application under API controls before members of
that organization can connect.

## After a connection stops working

`REAUTH_REQUIRED` on a credential means the authorization server rejected the stored refresh token. For a
per-User server the person reconnects from the composer; for a shared connection an administrator reconnects
from `/admin/mcp`. Deleting an OAuth application also removes the shared connection that used it and returns
the server to `AWAITING_AUTH`.

`memoryos.chat.mcp.call` carries the server slug, the tool name and the outcome, so a server that starts
failing, timing out or rejecting credentials is visible before people report it.

## Limits

A turn offers at most 8 servers and 64 tools. As Onyx, the cycle limit (`max-cycles`, 6) bounds the calls in one
turn; `memoryos.chat.execution.mcp-call-limit` is an optional per-turn cap, unset by default. `mcp-call-timeout`
(60 seconds) bounds each call, since a turn has no total deadline; it has not been measured against a live provider.
