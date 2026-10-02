# MEM-114 verification

The durable checks are in the [verification matrix](../../../tests/mcp-endpoint.md). This page records what ran
against the live deployments and what remains of the [acceptance](design.md#acceptance).

## Staging (`https://staging.vadan.app`, Keycloak `https://auth.kl3in.tech`)

| When | Check | Result |
| --- | --- | --- |
| 2026-10-01 | Claude web: custom connector, sign-in, consent, `search` and `fetch` | Connected and called both tools |
| 2026-10-02 | ChatGPT web, personal plugin through its metadata document | Connected after the image's executor and the optional `email` scope; called the tools |
| 2026-10-03 | Realm reconciled for MEM-207/209 before PR #453 merged | `memoryos-mcp-admin` obtains a token; trusted hosts kept; `memoryos-chatgpt` removed; offline grants 30 days unused and 180 at most |
| 2026-10-03 | `GET /.well-known/oauth-protected-resource/mcp` | 200: `resource` `https://staging.vadan.app/mcp`, `authorization_servers` `https://auth.kl3in.tech/realms/memoryos`, `scopes_supported` `["knowledge:read"]` |
| 2026-10-03 | `POST /mcp` `initialize` without a token | 401, `WWW-Authenticate: Bearer resource_metadata="https://staging.vadan.app/.well-known/oauth-protected-resource/mcp", scope="knowledge:read"` |
| 2026-10-03 | Keycloak discovery | `client_id_metadata_document_supported: true` |
| 2026-10-03 | Release `6ac59f77` deployed | API healthy; no error or reconciliation warning in its log |

## Production (`https://app.vadan.app`, Keycloak `https://auth.vadan.app`)

| When | Check | Result |
| --- | --- | --- |
| 2026-10-03 | Release `6ac59f77` deployed | Keycloak 26.7.0 to 26.8.0; the pre-upgrade dump kept in `/apps/memoryos-backups` |
| 2026-10-03 | Endpoint configured (`MEMORYOS_MCP_ENDPOINT_URL=https://app.vadan.app/mcp`), realm script run with it, release deployed again | The API reads the URL; Keycloak advertises metadata documents; no error in the API log |
| 2026-10-03 | `GET /.well-known/oauth-protected-resource/mcp` while the Tenant switch is off | 404, as the gate answers |

The owner decided on 2026-10-03 to configure the endpoint on production while it holds no organization data; the
Tenant switch is theirs to turn on.

## Not yet run

- With a member token: the 2026-07-28 probe (400 with `-32000`), `initialize`, `tools/list` and `resources/list`, a
  `memoryos-api` token on `/mcp` and an endpoint token on `/api/**`. The integration tests cover each; the live run
  needs a token from a real sign-in.
- Claude Code (`claude mcp add --transport http`).
- Revoking a grant in *Cài đặt › MemoryOS MCP* forces a new sign-in; switching off refuses the next call.
- A test member who reads only test Documents.
