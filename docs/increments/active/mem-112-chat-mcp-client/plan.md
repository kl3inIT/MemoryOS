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
- [x] `V69__mcp_client.sql` (renumbered from V63 when main took V63–V68) (servers, Group access, OAuth clients keyed by issuer, tool snapshots, owner/shared credentials) and `MCP_MANAGE` (enum, `CAPABILITY_REGISTRY`, CHECK constraint, OpenAPI enum, generated client, `group-capability-copy.ts`, identity spec and matrix). `V62` was taken by MEM-110 on main.
- [x] Closed `mcp` module (`ModulithArchitectureTest`, `AGENTS.md`, `ARCHITECTURE.md`); entities and Spring Data repositories registered in API and test JPA scanning; context-bound `McpSecrets` with `MEMORYOS_MCP_CREDENTIAL_ENCRYPTION_KEY` (redirect URI and CIMD URL arrive with the flows that use them).
- [x] Streamable HTTP client (`McpClients`, `McpSession`): endpoint and header policy, per-credential headers, deadline-bounded timeouts, paginated bounded tool listing, result conversion, typed failures without upstream detail.

Evidence: [verification.md](verification.md).

## Phase 2 — administration

- [x] Create `MEMORYOS_MCP_CREDENTIAL_ENCRYPTION_KEY` in the Infisical dev and staging environments before the first admin secret write; without it writes fail with `MCP_NOT_CONFIGURED`. Verified 2026-09-17: both environments hold a 32-byte key and `MEMORYOS_MCP_REDIRECT_URI`.

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

- [x] `V70` (was V64): `token_endpoint_auth_method`, `iss_parameter_required` on `mcp_oauth_client`.
- [x] Discovery (protected-resource metadata from `WWW-Authenticate` or well-known, authorization-server metadata in spec order, `S256` required) returning a review; nothing persisted until a client is created.
- [x] Clients: pre-registered (`KNOWN_PROVIDER` only source), CIMD document route, DCR with sealed secrets.
- [x] Administrator connect: `/login/oauth2/code/mcp` with its own security chain, session state bound to actor and revisions, PKCE, `resource`, `iss` validation, token exchange; `MEMORYOS_MCP_REDIRECT_URI` set like `MEMORYOS_GOOGLE_DRIVE_REDIRECT_URI`.
- [x] Token refresh shared with Phase 3 (revision fencing, rotation, `invalid_grant` → `REAUTH_REQUIRED`), best-effort revocation on disconnect; tool refresh for `ADMIN` OAuth servers.
- [x] Integration tests with a stub authorization server (see design).
- [x] Infisical (project `90ae5a61`, environments `dev` and `staging`) holds `MEMORYOS_MCP_CREDENTIAL_ENCRYPTION_KEY` (a fresh 32-byte key per environment) and `MEMORYOS_MCP_REDIRECT_URI` at the same browser origin as `MEMORYOS_GOOGLE_DRIVE_REDIRECT_URI`.

Evidence: [verification.md](verification.md#phase-2b--2026-09-16).

## Phase 3 — User credentials

- [x] Access predicate: active Tenant member with `CHAT_WRITE` and an organization-wide server or a granted Group; inaccessible servers are not found.
- [x] One connect path: `Pending` carries `ownerActorId` and `returnPath`; `complete` serves administrator and User; per-User connections leave the server status alone.
- [x] User OAuth connect with client choice, `iss` validation, refresh with revision fencing, `REAUTH_REQUIRED`, disconnect and revoke.
- [x] User API key (the single `{api_key}` substitution), probed against the server before it is stored.
- [x] Tool refresh of a per-User server uses the administrator's own credential and leaves the status unchanged on an authorization failure.
- [x] `GET /api/mcp/connections` and the connect, API-key and disconnect routes.
- [x] Tests: state replay, actor mismatch, changed revisions, wrong issuer, `invalid_grant`, cross-User isolation, Group-restricted access, API-key probe, return addresses.
- [x] Tests: a User's end-to-end OAuth connect, the concurrent-refresh race, and access withdrawn while an authorization is pending.

Evidence: [verification.md](verification.md#phase-3--2026-09-16).

## Phase 4 — Chat

- [x] `chat` may depend on `mcp`; `McpTurnService.open` resolves access, credentials and OAuth refresh before the model runs, and the turn owns the sessions.
- [x] One Embabel `Tool.create` per enabled tool with the snapshotted schema; per-turn call limit, per-call timeout and result cap reuse the existing guard.
- [x] Failures are mapped from MCP error codes, never matched text, and carry no upstream body to the model or the logs.
- [x] `mcpServerIds` on send, edit and regenerate.
- [x] An end-to-end turn against the fixture MCP server, including Stop closing the sessions.
- [x] Activity rendering for MCP steps and the `auth_required` connect action (with Phase 5): a failed step carries `failure` (`AUTHORIZATION_REQUIRED`, `TIMEOUT`, `UNAVAILABLE`) on the stream and in history.
- [x] Observability: `memoryos.chat.mcp.call` records a timer per server, tool and outcome, with the label set pinned by a test.
- [ ] Per-turn bounds measured against real Google Drive schemas; the current 10 calls and 60s are placeholders.
- [x] Integration coverage for a tool error, a rejected credential and a per-call timeout inside a real turn.

## Phase 5 — web

- [x] Admin `web/src/features/mcp/`: servers, authentication, tools and Groups at `/admin/mcp`, with the trust acknowledgement on creation.
- [x] Admin OAuth clients: discovery review, DCR, the metadata-document route, per-organization pasted clients, and the administrator's own connect/disconnect.
- [x] Composer: server list with status, OAuth connect with account choice, API-key entry.
- [x] Vietnamese and English copy for every new string.
- [x] MCP activity renderer in the timeline: tool and server names, the failure category, Connect in place for the person's own credential, and the step group left open while it needs action.
- [x] Rare server row actions (edit, delete) moved into one menu so the row fits 390px.
- [x] Unit tests for the composer submenu.
- [x] Chromium scenario over stubbed responses, with screenshots reviewed and the defects it exposed fixed.

## Phase 6 — documentation and acceptance

- [x] `docs/specs/chat.md` (MCP tools), `docs/tests/chat.md` (evidence table), `ARCHITECTURE.md` (`CHAT --> MCP`).
- [x] Self-host runbook at `docs/runbooks/mcp-servers.md`, linked from README and AGENTS.
- [ ] Live acceptance: the nine observations in [acceptance.md](acceptance.md). Owner-run; it also settles whether Google accepts `resource` and what the per-turn bounds should be.
- [x] Repository gate run module by module (see verification): `connector`, `worker`, `api` and `core` all pass. The single-command `gradlew clean check` is left to CI, because `:core:test` exhausts its 1 GB test JVM on this host.
- [x] Frontend `pnpm check` passes in one run.

## Phase 7 — pass-through OAuth (gated)

Tracked separately as [MEM-133](https://linear.app/memory-os/issue/MEM-133). Editing existing Drive files, which the official Drive MCP server cannot do, is [MEM-132](https://linear.app/memory-os/issue/MEM-132).

- [ ] Only after the Phase 0 confirmation, a concrete Keycloak-trusting MCP server and its ADR.
- [ ] Session-bound encrypted Keycloak token retention removed at sign-out; Keycloak token exchange (RFC 8693) to the MCP server audience per call, as OrgMemory `apps/mcp` does, instead of forwarding the login token.

## Evidence

Record commands, results and live receipts in `verification.md`.
