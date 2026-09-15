# MEM-112 plan

Design: [design.md](design.md). MemoryOS as an MCP server is [MEM-114](https://linear.app/memory-os/issue/MEM-114).

## Phase 0 — decisions and live probe

- [x] Owner confirmed 2026-09-15: no HTTP+SSE transport (Deprecated in `2026-07-28`) unless a required server offers nothing else.
- [x] Owner confirmed 2026-09-15: `PT_OAUTH` is Phase 7, implemented with Keycloak token exchange, and starts only with a concrete MCP server that trusts Keycloak plus an ADR changing the "provider tokens are discarded" identity contract.
- Owner deferred 2026-09-15: the POC Google account type and Developer Preview enrollment. The live Drive probe below waits for it; Phases 1–5 proceed against in-process MCP and stub authorization servers. ADR 0011 is written when Phase 1 implementation starts, as `AGENTS.md` requires.
- [x] Owner decided 2026-09-15: the proof of concept uses the owner's own Google Cloud OAuth client; Tasco organizations are not required for the POC. Multi-client selection is verified with a stub authorization server; two-organization live acceptance stays for Tasco rollout.
- [ ] Confirm Drive MCP access for the owner's account and record its user type and publishing status (`Internal` needs a Workspace organization; `External` + Testing limits test users to 100 and expires grants after 7 days).
- [x] UX flow research: Onyx MCP UI, Claude/ChatGPT connector flows, Mobbin; draft flow recorded in `design.md` (2026-09-15). Visual design and screenshots follow in Phase 5.
- [ ] Probe `drivemcp.googleapis.com/mcp/v1` with the Java SDK from a disposable test: negotiated protocol version, `tools/list` (annotations, schema sizes), one read call; record in `verification.md`.
- [x] Java SDK protocol support (2026-09-15): the resolved SDK `2.0.0` declares protocol versions only up to `2025-11-25`; no release claiming `2026-07-28` was found. Chosen `2.0.0`, aligned with `spring-ai-mcp` `2.0.1`. Re-check before Phase 4.
- [x] [ADR 0011](../../../decisions/0011-mcp-client-in-chat.md) superseding "MCP remains deferred" in ADR 0001 (client side; MEM-114 references it).

## Phase 1 — persistence, IAM, client core

- [x] Declare `io.modelcontextprotocol.sdk:mcp` `2.0.0` in `gradle/libs.versions.toml` (the Spring AI BOM does not manage it); `tomcat-embed-core` for in-process test servers.
- [x] `V63__mcp_client.sql` (servers, Group access, OAuth clients keyed by issuer, tool snapshots, owner/shared credentials) and `MCP_MANAGE` (enum, `CAPABILITY_REGISTRY`, CHECK constraint, OpenAPI enum, generated client, `group-capability-copy.ts`, identity spec and matrix). `V62` was taken by MEM-110 on main.
- [x] Closed `mcp` module (`ModulithArchitectureTest`, `AGENTS.md`, `ARCHITECTURE.md`); entities and Spring Data repositories registered in API and test JPA scanning; context-bound `McpSecrets` with `MEMORYOS_MCP_CREDENTIAL_ENCRYPTION_KEY` (redirect URI and CIMD URL arrive with the flows that use them).
- [x] Streamable HTTP client (`McpClients`, `McpSession`): endpoint and header policy, per-credential headers, deadline-bounded timeouts, paginated bounded tool listing, result conversion, typed failures without upstream detail.

Evidence: [verification.md](verification.md).

## Phase 2 — administration

- [ ] Create `MEMORYOS_MCP_CREDENTIAL_ENCRYPTION_KEY` in the Infisical dev and staging environments before the first admin secret write; without it writes fail with `MCP_NOT_CONFIGURED`.

### 2a — servers, API-key credentials, tools

- [x] Server CRUD: auth type (`NONE`, `API_TOKEN`, `OAUTH`), performer, sealed header template (`{api_key}` only), organization-wide or Groups, immutable slug, revision fencing. Changing URL, auth type or performer removes stored credentials; a URL change also removes the tool snapshot.
- [x] Shared API key for `API_TOKEN` + `ADMIN`, sealed per credential row; responses expose header names and `sharedCredentialConfigured` only.
- [x] Tool refresh with the administrator credential outside transactions (status, `last_refreshed_at`), typed snapshot bounds, enablement per tool and for all. Model name `mcp_<slug>_<tool>` must fit `[A-Za-z0-9_.-]{1,64}`; slugs have no `_`, so composed names cannot collide.
- [x] `/api/mcp/...` controller, OpenAPI (`MEMORYOS_OPENAPI_WRITE=true`), `pnpm generate:api`.
- [x] Tests: `McpServerRulesTest`; API integration in the shared `ChatSessionApiIntegrationTest` context against an in-process MCP server.
- Refresh for `OAUTH` servers needs 2b; refresh for `PER_USER` servers needs the administrator's own credential from Phase 3.

Evidence: [verification.md](verification.md#phase-2a--2026-09-16).

### 2b — OAuth setup

Design: [OAuth setup (Phase 2b)](design.md#oauth-setup-phase-2b).

- [ ] `V64`: `token_endpoint_auth_method`, `iss_parameter_required` on `mcp_oauth_client`.
- [ ] Discovery (protected-resource metadata from `WWW-Authenticate` or well-known, authorization-server metadata in spec order, `S256` required) returning a review; nothing persisted until a client is created.
- [ ] Clients: pre-registered (`KNOWN_PROVIDER` only source), CIMD document route, DCR with sealed secrets.
- [ ] Administrator connect: `/login/oauth2/code/mcp` with its own security chain, session state bound to actor and revisions, PKCE, `resource`, `iss` validation, token exchange; `MEMORYOS_MCP_REDIRECT_URI` set like `MEMORYOS_GOOGLE_DRIVE_REDIRECT_URI`.
- [ ] Token refresh shared with Phase 3 (revision fencing, rotation, `invalid_grant` → `REAUTH_REQUIRED`), best-effort revocation on disconnect; tool refresh for `ADMIN` OAuth servers.
- [ ] Integration tests with a stub authorization server (see design).

## Phase 3 — User credentials

- [ ] User OAuth connect with client choice, `/login/oauth2/code/mcp`, `iss` validation, refresh with revision fencing, `REAUTH_REQUIRED`, disconnect and revoke.
- [ ] User API key and header substitutions.
- [ ] Tests: state replay, actor mismatch, changed revisions, wrong issuer, refresh race, `invalid_grant`, cross-User isolation.

## Phase 4 — Chat

- [ ] `mcpServerIds` and validation in `ChatTurnService`.
- [ ] Per-turn toolset: `Tool.of` with schema `InputSchema`, client reuse, closed at turn end.
- [ ] Bounds from measured schemas, cancellation, activity events, metrics.
- [ ] Integration tests: streamed turn with call, tool error, auth required, timeout, Stop.

## Phase 5 — web

- [ ] Admin `web/src/features/mcp/`: servers, auth, discovery review, clients, tools, Groups.
- [ ] Composer: server list, status, OAuth connect with client choice, API-key entry.
- [ ] Generic MCP activity renderer; Vietnamese and English copy.
- [ ] Unit tests and Chromium scenario with fixture servers.

## Phase 6 — documentation and acceptance

- [ ] `docs/specs/chat.md`, `docs/tests/chat.md`, `ARCHITECTURE.md`.
- [ ] Self-host runbook: outbound domains, redirect-URI rules, CIMD reachability, `Internal` client per organization, MEM-60 client reuse.
- [ ] Live acceptance: two organizations' clients on Google Drive MCP, wrong-organization `org_internal`, read tools and `create_file` on a test folder.
- [ ] `gradlew clean check` and frontend gate.

## Phase 7 — pass-through OAuth (gated)

- [ ] Only after the Phase 0 confirmation, a concrete Keycloak-trusting MCP server and its ADR.
- [ ] Session-bound encrypted Keycloak token retention removed at sign-out; Keycloak token exchange (RFC 8693) to the MCP server audience per call, as OrgMemory `apps/mcp` does, instead of forwarding the login token.

## Evidence

Record commands, results and live receipts in `verification.md`.
