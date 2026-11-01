# MCP server runbook

Operating [MCP servers](../specs/chat.md#mcp-tools) in a MemoryOS deployment. Registering a server is an
administrator action under `MCP_MANAGE`; this runbook covers what the deployment must provide first.

## Configuration

| Variable | Where | Purpose |
| --- | --- | --- |
| `MEMORYOS_MCP_CREDENTIAL_ENCRYPTION_KEY` | API only | Base64 random 32-byte AES key sealing OAuth client secrets, header templates and every stored credential. Missing configuration disables MCP secret reads and writes. Preserve it with database backups: changing it without re-sealing makes stored credentials unreadable. |
| `MEMORYOS_MCP_REDIRECT_URI` | API only | The OAuth callback. HTTPS, or loopback HTTP for local development, and it must end with `/login/oauth2/code/mcp`. |

On a server the key is the `mcp_credential_encryption_key` secret file Compose mounts
(`/apps/memoryos/secrets/encryption/mcp-credential-key.txt` by default), and the redirect URI is set in the environment
file Compose reads. The redirect URI belongs to the browser origin,
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

MCP Apps views (Canva's design picker, for example) run in the reader's browser on origins under
`scf.auiusercontent.com`, assistant-ui's sandbox domain, and then reach the domains each view declares. A browser that
cannot reach that domain shows the answer without the view; the tool's text result is unchanged (MEM-275). After a
server starts offering views, refresh its tools so MemoryOS records which tools have one.

## One OAuth application per organization

A Google `Internal` application authorizes only its own Workspace, so each organization keeps its own OAuth
application and MemoryOS stores several per server, each labelled. People choose the label when connecting.
For each organization:

1. Create an `Internal` Web application in that organization's Google Cloud project.
2. Enable the Drive API and the Drive MCP API in the project, and configure its consent screen.
3. Register `MEMORYOS_MCP_REDIRECT_URI` exactly as the authorized redirect URI.
4. In MemoryOS, open the server under `/admin/mcp` and choose **Thêm ứng dụng OAuth**. Google does not register
   clients automatically, so the form opens with Google's endpoints filled in: paste the label, client ID and
   secret. The secret is sealed and never shown again.

Google issues a refresh token only when the authorization request carries `access_type=offline`, and on
reconnection only with `prompt=consent`. Put both in the server's additional authorization parameters (server
dialog › Nâng cao; for a Google API address the dialog offers a button that adds them).
Without them a connection stops working after about an hour and becomes `REAUTH_REQUIRED`.

Workspace administrators may additionally have to trust the application under API controls before members of
that organization can connect.

## After a connection stops working

`REAUTH_REQUIRED` on a credential means the authorization server rejected the stored refresh token. For a
per-User server the person reconnects from the composer; for a shared connection an administrator reconnects
from `/admin/mcp`. A server that answers `403 insufficient_scope` gives the same prompt while the credential stays
`ACTIVE`: connecting again asks for the scope the server named as well as those granted before. Deleting an OAuth application also removes the shared connection that used it and returns
the server to `AWAITING_AUTH`.

`memoryos.chat.mcp.call` carries the server slug, the tool name and the outcome, so a server that starts
failing, timing out or rejecting credentials is visible before people report it.

## Limits

A turn offers at most 8 servers. A server with more than 12 enabled tools is one opening tool, `mcp_<slug>`, until the
model opens the tools it names (MEM-272), so a large server no longer crowds out the others.
`memoryos.chat.mcp.tools.offered` and `memoryos.chat.mcp.tools.definition.chars` show what each turn starts with. As Onyx, the cycle limit (`max-cycles`, 6) bounds the calls in one
turn; `memoryos.chat.execution.mcp-call-limit` is an optional per-turn cap, unset by default. `mcp-call-timeout`
(60 seconds) bounds each call, since a turn has no total deadline; it has not been measured against a live provider.

## Catalog servers

`/admin/mcp` offers the [catalog](../increments/completed/mem-252-mcp-catalog/design.md#the-catalog). Entries whose
OAuth application is automatic need nothing beyond outbound HTTPS and, for the client metadata document, the
inbound route above. Entries made by hand need one application per organization in the provider's console, with
`MEMORYOS_MCP_REDIRECT_URI` registered as its redirect URI; the form links to the console.

| Entry | Checked |
| --- | --- |
| Google Drive | Connected and used in Chat (MEM-112) |
| Linear | Server, discovery and automatic application on staging 2026-10-14; a person's connection pending |
| Others | Unauthenticated discovery only, 2026-10-14 |

