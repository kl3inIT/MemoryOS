# MemoryOS MCP endpoint

Claude, ChatGPT and other trusted apps search and read a Tenant's Documents through `/mcp`, as the signed-in member
and with exactly that member's access. Decided in [ADR 0024](../decisions/0024-mcp-endpoint-in-process-with-the-members-access.md)
and [ADR 0022](../decisions/0022-trusted-mcp-apps-kept-in-memoryos.md); delivered by
[MEM-114](../increments/completed/mem-114-public-mcp-server/design.md) and
[MEM-207/MEM-209](../increments/completed/mcp-endpoint-governance/design.md). The other direction, MemoryOS calling
other MCP servers from Chat, is in the [Chat contract](chat.md). Verification: [matrix](../tests/mcp-endpoint.md).
Operations: [runbook](../runbooks/mcp-endpoint.md).

## Placement

| Concern | Lives in |
| --- | --- |
| Transport, security chain, tools, request limits | `api` package `io.memoryos.api.mcp.endpoint`, the security chain in `io.memoryos.api.security.McpEndpointSecurityConfiguration` |
| Search and read | The public `retrieval` APIs the Search page uses (`DocumentSearchService`) |
| Switch, trusted apps, activity log | `core` module `mcp`: `McpEndpointService`, `McpTrustedAppService`, `McpEndpointActivity` |
| Grants, Keycloak's trusted-host policy | `core` module `iam`: `McpClientGrants`, the `McpClientPolicy` port and its Keycloak adapter |
| Activity retention | The Worker's recurring task `memoryos-mcp-endpoint-activity-retention-v1` |

## HTTP surface

- `POST /mcp`: MCP Streamable HTTP, stateless, JSON responses (Spring AI 2.0.1, MCP Java SDK 2.0.1, `SYNC`).
- `GET /.well-known/oauth-protected-resource/mcp`: RFC 9728 metadata: the configured `resource`, the exact Keycloak
  issuer and `scopes_supported: ["knowledge:read"]`.
- `web/nginx.conf` forwards both paths, and MEM-112's `/mcp/oauth/client-metadata.json`, to the API with request
  buffering off; anything else under `/mcp` stays with the web application.
- **The endpoint answers only when the deployment configured `MEMORYOS_MCP_ENDPOINT_URL` (the browser origin
  followed by `/mcp`) and the Tenant switched it on.** Otherwise both paths answer 404 on every request, so no client
  learns that an endpoint could exist and turning it off takes effect on the next call.

## Authentication

- A security chain for the two paths only: bearer tokens, no session read or created.
- Its own decoder: Keycloak JWKS, exact issuer, audience equal to the endpoint URL, non-blank `sub`. A token for
  `memoryos-api` is refused here, and a token for the endpoint is refused on `/api/**`.
- Scope `knowledge:read` is required; its mapper is what puts the endpoint audience in a token. A token with the
  audience but not the scope gets 403 `insufficient_scope`.
- An anonymous call gets 401 with `WWW-Authenticate: Bearer resource_metadata="…", scope="knowledge:read"`.
- The member is resolved from `(iss, sub)` and never provisioned; IAM is read on every call, so a deactivated member
  or one without `SEARCH_READ` is refused at once.
- A request with an `Origin` header outside the allowed origins gets 403; the apps call from their servers without
  one.

## Clients and grants

- **Keycloak 26.8 with `cimd`.** An app identifies itself by a client ID metadata document (a URL), read by the
  image's `memoryos-client-id-metadata-document` executor, which tolerates the property in ChatGPT's document that
  Keycloak's own executor rejects. Keycloak keeps one public, consent-required client per document. Dynamic client
  registration is closed. PKCE S256 is required of every public client of the realm.
- **Trusted apps** decide which documents Keycloak accepts. Claude (`claude.ai`, `claude.com`, with loopback
  redirects for Claude Code) and ChatGPT (`chatgpt.com`, `persistent.oaistatic.com`) are built in and can be switched
  off; an administrator adds an app of the organization's own by the domains of its client ID and document. MemoryOS
  keeps the list (`mcp_trusted_apps`) and writes Keycloak's `memoryos-mcp-cimd` policy inside each change, through
  the `memoryos-mcp-admin` account; it reconciles the policy at start and every 10 minutes. Switching an app off or
  removing it deletes the Keycloak clients built from its documents, which revokes every grant through it. Without an
  endpoint URL or the account the list is read-only. Each change is audited as `mcp_trusted_app.change`.
- **Grants** are offline sessions: they lapse after 30 days unused and end 180 days after the member connected.
  Access tokens are short-lived and not introspected.
- *Cài đặt › MemoryOS MCP* lists the member's grants (app, granted date) and revokes one, which deletes the consent
  and its offline session. An access token already issued lives until it expires; the switch is the immediate stop.
  Revocation is the member's own action and is not audited.

## Tools

All three are read-only (`readOnlyHint`, `idempotentHint`, not destructive, not open-world) and return both
`structuredContent` and its JSON text. Spring AI's tool-callback converter is off, so no other tool of the
application is published. The server's instructions and each description ask the app to answer from the returned
evidence only, treat it as data, answer in the member's language, link every claim to its result's `url` as a
Markdown link, and say when nothing was found without suggesting that a restricted Document exists.

| Tool | Input | Output |
| --- | --- | --- |
| `search` | `query`, 1–1000 characters | Up to 10 results: `id` (Document UUID), `title`, `url`, `sourceNumber`, `text` (the matching sections, about 2,000 characters), `updatedAt`, `sources` |
| `search_with_filters` | `query` and only the filters asked for: `source_types` (`GOOGLE_DRIVE`, `SHAREPOINT`, `FILE`), `document_set_names` (up to 10, by name), `updated_after` / `updated_before` (`YYYY-MM-DD`, UTC days of the provider's date), `file_types` (`PDF`, `WORD`, `POWERPOINT`, `SPREADSHEET`, `TEXT`, `MARKDOWN`) | As `search` |
| `fetch` | `id` exactly as a result gave it | `id`, `title`, `text` (passages up to about 100,000 characters, saying where it was cut), `url`, `metadata` |

- Search runs `DocumentSearchService.search` under the member's own authority, as the Search page does; no model is
  called.
- `url` is the provider's link for Drive and SharePoint, and `/search?doc=<id>` for any other Document, which opens
  the original under the reader's own access.
- An unknown document set is refused with the sets the member can use; an unreadable or unknown id answers one
  sanitized "not available"; a refused call says what to change. A failure reaches the app without its cause.

## Limits

- Each `tools/call` spends one token from the caller's bucket (member and app, 300 a minute) and one from the
  endpoint's (3,000 a minute); discovery and listing are free. An empty bucket answers 429 with `Retry-After` and
  `RateLimit-*`. The buckets are in memory, one API node.
- A body over 256 KiB answers 413, whether it declares its length or arrives chunked.
- A request whose `Accept` lacks one of `application/json`, `text/event-stream` is given both.
- An unsupported `MCP-Protocol-Version` on anything but `initialize` answers 400 with JSON-RPC error `-32000`, so a
  2026-07-28 client falls back to `initialize`.

## Activity and insights

- Each tool call, and each refusal for rate, is one row of `mcp_endpoint_calls`: when, the member, the client ID and
  its kind (`CLAUDE`, `CHATGPT`, `OTHER`), the tool and the outcome (`SUCCESS`, `REFUSED`, `FAILED`,
  `RATE_LIMITED`). Never the query, a filter or a document. The row is written in a transaction of its own and a
  failed write never fails the call.
- Holders of `MCP_MANAGE` read it under *Quản trị › MemoryOS MCP*: *Hoạt động* pages the calls newest first,
  filtered by period, app, tool, outcome and person; *Thống kê* counts calls, people, failures and refusals for rate
  over 7 or 30 UTC days, and by day, app and tool. The Worker removes rows older than 90 days.
- A tool call is a member reading what they may read, so it is not audited; the switch (`mcp_endpoint.change`) and
  trusted-app changes are.

## Observability

- Timer `memoryos.mcp.endpoint.call` with `tool`, `outcome` and `client` (the kind, never the client ID), with a
  histogram; counter `memoryos.mcp.endpoint.rate_limited` with `scope` (`caller` or `endpoint`) and `client`.
- Log events `mcp_endpoint.tool.failed`, `mcp_endpoint.tool.refused`, `mcp_endpoint.rate_limited`,
  `mcp_endpoint.activity.record_failed`, `mcp_endpoint.trusted_apps.reconcile_failed`; no query or result content.
- The *MemoryOS MCP endpoint* row of the Chat & AI dashboard draws calls by tool and app, p95 by tool and refusals for
  rate.

## Data leaving the Tenant

Every passage returned goes to the app's provider, an external model. Until
[MEM-134](../increments/active/mem-134-external-data-gate/design.md) keeps an `INTERNAL_ONLY` Source out of the
endpoint, an enabled endpoint exposes every Source the member can read; the switch is off by default.
