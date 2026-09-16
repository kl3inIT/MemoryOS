# MEM-112: Chat MCP client

Linear: [MEM-112](https://linear.app/memory-os/issue/MEM-112). Plan: [plan.md](plan.md).

Chat tools that MemoryOS writes itself (`web_search`, `open_url`, `generate_image`, `searchKnowledge`) cover fixed capabilities. Ingestion covers content copied into the index ahead of time. Neither answers live system state, structured queries, actions, or sources that should be read with the asking User's own permissions. This increment ports Onyx's MCP client ("MCP actions") to MemoryOS without reducing its behavior. The first live acceptance integration is the official Google Drive MCP server.

## Reference

Onyx `40eb240df` (checkout `.tmp/onyx`, 2026-09-10).

### Observed behaviour

- Transports: Streamable HTTP and SSE; stdio declared but unsupported (`backend/onyx/db/enums.py:189`, `server/features/mcp/client.py:138`).
- Authentication types `NONE`, `API_TOKEN`, `OAUTH`, `PT_OAUTH` (pass-through of the User's login OAuth token) (`db/enums.py:177`). Performer `ADMIN` (one shared credential) or `PER_USER` (`db/enums.py:197`). Resolution in `resolve_mcp_credentials` (`server/features/mcp/credentials.py:232`).
- API tokens use an admin header template such as `{"Authorization": "Bearer {api_key}"}` with per-User substitutions and extra headers; denylisted headers are stripped (`credentials.py:115,153,184`).
- OAuth provider modes `AUTO_DISCOVERY` (protected-resource metadata, Dynamic Client Registration, Client ID Metadata Document at `/api/mcp/oauth/client-metadata`) and `KNOWN_PROVIDER` (admin endpoints and client) (`server/features/mcp/oauth_flow.py:74,190,229`, `client_metadata.py`). PKCE, state stored with TTL, callback rejects the grant if server configuration or headers changed while pending (`oauth_flow.py:159`). Admin and User connect routes (`api.py:620,632`); User API-key routes (`api.py:998,1098`).
- `MCPServer` (`db/models.py:5811`): URL, transport, auth type and performer, OAuth endpoints, scope override, extra authorization parameters, status (`CREATED`, `AWAITING_AUTH`, `FETCHING_TOOLS`, `CONNECTED`, …), `last_refreshed_at`, `is_public` plus linked users and user groups. `MCPConnectionConfig` (`models.py:5931`) is encrypted JSON per (server, `user_email`) or admin. One OAuth client per server.
- Tool snapshots: admin discovers tools from the server and syncs them to `Tool` rows (`api.py:1413`); agents (personas) reference individual tool rows; chat builds `MCPTool` per enabled row (`tools/tool_constructor.py:446`).
- `MCPTool.run` (`tools/tool_implementations/mcp/mcp_tool.py:144`): resolve credentials, refresh OAuth, call, JSON-wrap the result, emit a tool packet, record an outcome metric. Missing or rejected credentials become an LLM-facing error telling the User to connect. Auth failures are detected by substring matching on the exception text, and the original exception text is included in the model-facing message (`mcp_tool.py:292-301`).
- Each tool call opens a new transport and `ClientSession` and runs `initialize` before `call_tool` (`client.py:144-167,246`).
- Result flattening: text blocks, embedded text resources, resource links; `structuredContent` only as a fallback (`client.py:223`).
- Outbound SSRF guard on every request including URLs derived from server responses; strictness is an admin setting (`server/features/mcp/ssrf.py`).
- No approval before a tool runs (no `approval` match under `backend/onyx/tools`). Write tools run when enabled.
- Onyx also ships its own MCP server exposing `search_indexed_documents`, `search_web`, `open_urls` and resources, authenticated by delegating the bearer token to `/me` (`mcp_server/`). That is a separate capability from the client.

### Evidence gaps

- Google Drive MCP tool annotations (`readOnlyHint`) and Developer Preview enrollment. Verified in Phase 0.

## Protocol baseline

- MCP `2026-07-28` ([changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)) removes protocol sessions, `Mcp-Session-Id` and the `initialize` handshake; adds `server/discover`, Multi Round-Trip Requests and `resultType`; formally deprecates the HTTP+SSE transport and Dynamic Client Registration (in favor of Client ID Metadata Documents); requires validating a present `iss` authorization-response parameter (RFC 9207) and keying client credentials by issuer. Clients speaking `2026-07-28` fall back to the handshake for older servers.
- MCP Java SDK `2.0.0` (current classpath) declares protocol versions up to `2025-11-25` only (`io.modelcontextprotocol.spec.ProtocolVersions`); `2.0.1` (2026-08-19) release notes do not claim `2026-07-28`. Java is not among the Tier 1 SDKs with `2026-07-28` betas. Phase 0 records which version Google Drive MCP negotiates and whether a Java SDK release supports `2026-07-28` before implementation starts.
- Consequently the client targets `2025-11-25` servers now and keeps protocol handling inside `io.memoryos.mcp` so the SDK upgrade does not reach Chat.

## Parity map

| Onyx behavior | MemoryOS | Note |
|---|---|---|
| Streamable HTTP, SSE | Streamable HTTP; SSE only for servers that offer nothing else | HTTP+SSE is Deprecated in `2026-07-28` ("new implementations should not adopt"). Owner decides whether legacy SSE servers are required. |
| `NONE`, `API_TOKEN`, `OAUTH`, `PT_OAUTH`; `ADMIN`/`PER_USER` | Same | `PT_OAUTH` needs retained login tokens, see Departures. |
| Header template, substitutions, extra headers, denylist | Same | |
| `AUTO_DISCOVERY`, DCR, CIMD, `KNOWN_PROVIDER` | Same, CIMD preferred over DCR | DCR is Deprecated but kept for authorization servers without CIMD. Credentials keyed by issuer; `iss` validated. Endpoint handling adapted to MemoryOS policy, see Departures. |
| Pending-grant configuration snapshot check | Same | Using entity revisions instead of fingerprints. |
| Server status and tool refresh from server | Same | |
| Admin enables tool subset; write tools allowed | Same | |
| `is_public` + users + groups | Tenant-wide flag + Groups | MemoryOS authorizes through Groups ([ADR 0007](../../../decisions/0007-unified-jpa-iam-and-group-authorization.md)); a single User is a Group of one. |
| Persona references tools | Composer selects servers per turn | MemoryOS has no personas. |
| `available_in_craft` | Absent | MemoryOS has no Craft consumer. |
| MCP result flattening | Same, plus explicit `structuredContent` | |
| Outcome metrics | Same | |
| Onyx MCP server | [MEM-114](https://linear.app/memory-os/issue/MEM-114) | Separate capability and increment. |

## Departures

Each row follows [reference-based design](../../../conventions.md#reference-based-design-and-scope-control).

### Multiple OAuth clients per server

- Requirement: Tasco organizations may be separate Google Workspace accounts. An `Internal` app authorizes only its own organization (`org_internal`).
- Reference: one client per server.
- Proposal: a server has 1..N OAuth clients, each labelled. The User chooses the label when connecting; with one client there is no choice. MemoryOS does not infer the organization from the Keycloak email domain (`IdentityContext` carries only `actorId`; secondary domains break domain matching). DCR-registered clients are stored the same way, one per registration.
- Benefit: each organization keeps an `Internal` app, avoiding Google verification, the 100-user cap and CASA.
- Cost: one table and a choice at connect time.
- Simpler baseline: register the same URL as several servers. Rejected: duplicate tool sets for the model and Users guessing which copy is theirs.

### Endpoint policy and discovery

- Requirement: accepted provider endpoint policy ([chat-models](../../../specs/chat-models.md#credentials-and-provider-extension)) allows internal HTTP(S) under trusted management authority, and treats calls to a destination other than the configured endpoint as defects.
- Reference: SSRF guard with admin-selected strictness; runtime follows URLs from server responses.
- Proposal: no private-host blocking. Discovery (protected-resource metadata, authorization-server metadata, registration) runs only in the `MCP_MANAGE` setup flow; the discovered authorization, token, registration and revocation endpoints are shown to the admin, persisted and become configured endpoints. Runtime calls only persisted endpoints; redirects to another origin are not followed.
- Benefit: keeps auto-discovery and DCR while satisfying the accepted policy; a compromised MCP server cannot steer runtime requests.
- Cost: re-running discovery when a provider changes endpoints.
- Simpler baseline: Onyx's runtime discovery. Rejected by the accepted policy.

### Pass-through OAuth

- Requirement: parity with `PT_OAUTH`.
- Reference: uses `user.oauth_accounts[0].access_token`.
- MemoryOS today discards login authorized clients (`api/.../security/DiscardingOAuth2AuthorizedClientRepository.java`, `SessionSecurityConfiguration.java:72`), so no login token exists server-side.
- Proposal (gated last phase): never forward the login token; exchange it per call with Keycloak token exchange (RFC 8693) for a token whose audience is the MCP server, as OrgMemory `apps/mcp/.../McpDownstreamOAuthConfiguration.java` does. MemoryOS still needs a subject token server-side: Retain the session's Keycloak access and refresh tokens encrypted, bound to the HTTP session, removed at sign-out and Keycloak back-channel logout, refreshed before use. Audience must include the MCP server; configure a Keycloak client scope/audience mapper per server rather than forwarding a token whose audience is MemoryOS.
- Benefit: internal MCP servers behind the same Keycloak need no second consent.
- Cost: reverses the accepted identity contract "provider tokens are still discarded" ([identity spec](../../../specs/identity.md)), adds a token store to the identity threat model, and must be reconciled with the active sign-out increment, which rejected storing the ID token for the same reason. Upstream IdP (Google) tokens are not passed; that would need Keycloak token exchange and is not Onyx behavior.
- Evidence to choose: owner accepts retaining login tokens; interaction with [logout-without-keycloak-page](../logout-without-keycloak-page/design.md).

### Improvements over the reference

Each fixes a concrete failure at low cost; none adds a new mechanism.

1. For `2025-11-25` servers, reuse one initialized client per (server, credential) within a turn instead of `initialize` on every call. Failure: repeated handshakes spend the 2-minute turn deadline. `2026-07-28` servers have no handshake, so this reduces to reusing the HTTP client.
2. Detect authentication failure from HTTP 401/403, `McpHttpClientTransportAuthorizationException` and OAuth `invalid_grant`, not substrings. Failure: a tool result containing "unauthorized" forces a reconnect prompt.
3. Model-facing errors use categories (`auth_required`, `timeout`, `tool_error`, `unavailable`) without raw exception text. Failure: internal hostnames or upstream response bodies enter the transcript.
4. Key User credentials by `actorId`, not email. Failure: an email change orphans or crosses connections.
5. Ciphertext bound to tenant, record and purpose. Failure: a copied ciphertext decrypts under another User. Spring Security `AesGcmBytesEncryptor` has no additional-authenticated-data API, so the binding is a verified plaintext prefix, the existing `ProviderCredentials` pattern.

### Candidate, not in scope

Approval before write tools run. Onyx has none. It would need suspension of the synchronous Embabel tool loop under the turn deadline (`ChatModelExecutor.java:179`). Record separately if the owner wants it.

## UX flow (draft)

Sources: Onyx web `40eb240df` (`sections/actions/*`, `lib/tools/components/MCPLineItem.tsx`, `components/chat/MCPApiKeyModal.tsx`, `app/mcp/oauth/callback/page.tsx`, `CustomToolRenderer.tsx`); Claude connectors (per-conversation toggle under `+`, per-tool Always allow / Needs approval / Blocked); ChatGPT developer-mode connectors. Mobbin (2026-09-15): Claude connector detail with grouped tool permissions ("Interactive tools", "Read-only tools", per-tool allow / needs approval / blocked) and "Disconnect"; Perplexity "Add custom connector" with MCP server URL, "Advanced" authentication and client ID/secret, and a required "I understand custom connectors can introduce risks" acknowledgement; Manus and Mistral composer menus listing connectors with inline "Connect"; Mistral "My Connectors" / "Admin Controls" tabs with connected cards; Lovable "Shared connectors" (configured by admins) versus "Personal connectors" (only you can access); WRITER "Login required" + "Connect" + "N tools available"; ChatGPT app detail with "Disconnect" and a "{app} is now connected" toast; Higgsfield inline approval card with "Always allow", "Stop", "Approve".

Refinements taken from that research:

- Admin add-server dialog ends with a trust acknowledgement checkbox before "Add".
- Settings "Connections" separates servers the admin configured with shared credentials from the User's personal connections.
- Server detail groups tools by annotation (read-only, write/destructive) with a group-level control and per-tool control; first delivery exposes enabled/disabled only, leaving "needs approval" to the approval candidate.
- Composer MCP rows show inline "Connect" when the User has no credential, instead of a separate status icon only.
- A successful callback returns to the chat with a "{server} connected" toast.

Admin, route `_authenticated.admin.mcp` next to `models` and `web-search`:

1. List: search, "Add MCP server", cards with name, URL, status (`Needs setup`, `Fetching tools`, `Connected`, `Disconnected`), enabled/total tool count, actions allowed by `MCP_MANAGE`. Empty and loading states as Onyx.
2. Add server, step 1: name, description, URL (Streamable HTTP only), trust helper text, tenant-wide or Groups (reuse `source-groups-section` pattern).
3. Step 2, authentication: `OAuth` (default), `API key` (per User or shared, header template editor), `None`. OAuth shows the redirect URI with copy, then provider mode: `Auto discovery` runs discovery and shows the found issuer and endpoints for explicit confirmation (MemoryOS departure); `Known provider` takes endpoints, scopes and extra parameters.
4. OAuth clients (MemoryOS departure): list of labelled clients; "Add client" accepts pasted/uploaded Google client JSON through the existing `google-drive-oauth-client-input` pattern, or client ID/secret for other providers; CIMD/DCR clients appear as registered rows.
5. Tools panel: search, refresh with "last refreshed", per-tool switch, enable all / disable all, read-only/destructive badges from annotations, unavailable tools disabled.
6. Disconnect and delete use confirmation dialogs with consequences stated.

End user in Chat:

1. Composer `+` menu (`chat-composer-menu.tsx`) gains an "MCP" submenu beside Web and image: one row per accessible server with status icon (connected, needs connection, unavailable) and a per-conversation switch; a server row opens its tool list with per-conversation tool switches.
2. "Connect": with one OAuth client, redirect immediately; with several, a dialog asks "Which organization's account?" listing client labels; API-key servers open a credentials dialog with masked fields and server-side validation.
3. Callback page `/login/oauth2/code/mcp` → processing, success ("Connected to {server}", auto-return to the originating chat), or specific errors (cancelled, invalid link, expired session, wrong organization/`org_internal`, token exchange failed).
4. Settings "Connections": list the User's MCP connections with account, client label, status, reconnect and disconnect.
5. In the activity timeline: running/completed/failed per tool with server label and duration; `auth_required` renders a card "{server} is not connected" with "Connect" that returns to the same chat.

## Integration with MemoryOS

### Libraries

- `io.modelcontextprotocol.sdk:mcp:2.0.0` is on the `core` runtime classpath through `embabel-agent-api:1.5.1 → spring-ai-mcp:2.0.1`. Declare it explicitly in the version catalog at that version.
- Transports built per credential with `httpRequestCustomizer(McpSyncHttpClientRequestCustomizer)` setting headers per request, `authorizationErrorHandler` mapping 401/403, `connectTimeout`. `McpSyncClient.requestTimeout` per call.
- Embabel tools: `Tool.create(name, description, Tool.InputSchema, Tool.Metadata, Tool.Handler)` (verified against `embabel-agent-api-1.5.1`). `Tool.InputSchema` is an interface over `toJsonSchema()` and `getParameters()` with factories for classes only, so a small implementation returns the snapshotted schema and no parameters. `Tool.Handler` takes the raw argument JSON and returns `Tool.Result.text` or `Tool.Result.error`, which is where the per-call guard, deadline and result cap live. `SpringAiMcpToolFactory` is not used; it binds fixed clients and bypasses the Chat guard.
- OAuth: authorization-code with PKCE implemented like the MEM-60 flow; DCR (RFC 7591) and metadata discovery (RFC 9728, RFC 8414) with `RestClient`. The SDK's client OAuth helpers are used where the Java SDK provides them; verified in Phase 1.

### Capability placement

New `core` package `io.memoryos.mcp`: servers, OAuth clients, tool snapshots, credentials, client sessions. Chat depends on it for per-turn tools. Persistence follows the [persistence policy](../../../guidelines/persistence.md).

### Data (Flyway V63)

- `mcp_server`: tenant, name, description, `slug` (`[a-z0-9]{1,16}`, unique per tenant), URL, transport, auth type, performer, provider mode, scope override, extra authorization parameters, encrypted header template, status, `tenant_wide`, `last_refreshed_at`, revision.
- `mcp_oauth_client`: server, label, source (`ADMIN`, `REGISTERED`, `METADATA_DOCUMENT`), issuer (credentials are never reused with another issuer), client ID, encrypted secret, authorization/token/revocation/registration endpoints, registration access token (encrypted), revision.
- `mcp_server_tool`: server, name, description, input schema (`jsonb`, bounded), annotations, `enabled`, snapshot time.
- `mcp_server_group`.
- `mcp_credential`: tenant, server, owner (`actorId`, or null for admin), OAuth client, encrypted payload (tokens, API token, substitutions, headers), expiry, status (`ACTIVE`, `REAUTH_REQUIRED`), revision.
- `mcp_login_token` (only if `PT_OAUTH` is accepted): session-bound encrypted Keycloak tokens.
- Capability CHECK constraint adds `MCP_MANAGE`.

### Authorization

- `MCP_MANAGE`: servers, clients, admin credentials, discovery, tool refresh and enablement, group access.
- Using a server: `CHAT_WRITE` plus tenant-wide flag or Group membership. Per-User credentials are never replaced by another User's.
- Administration (implemented in 2a): a server is either organization-wide or restricted to Groups, not both, which keeps one access mode visible in the form. Changing URL, authentication type or performer removes stored credentials; a URL change also removes the tool snapshot. The administrator-typed header template is kept.

### OAuth setup (Phase 2b)

Follows MCP authorization `2025-11-25` (the negotiated target) plus the `2026-07-28` `iss` and issuer-keyed credential rules. Each step names its reference.

**Callback and state.** `GET /login/oauth2/code/mcp` with its own `@Order(0)` security chain, as `GoogleDriveCallbackSecurityConfiguration` does for `/login/oauth2/code/google-drive`; otherwise Spring's OAuth2 login filter would claim the path. `MEMORYOS_MCP_REDIRECT_URI` is configured like `MEMORYOS_GOOGLE_DRIVE_REDIRECT_URI` and must end in that path. Pending state lives in the HTTP session, as in `GoogleDriveAuthorizationSessionState`:
- It holds actor, Tenant, server, OAuth client, the server and client revisions, `state`, PKCE verifier and a 10-minute expiry.
- It is consumed once and requires the same authenticated actor.
- The callback rejects the grant when either revision changed (Onyx parity).
- The browser returns to the originating page with an outcome code only.

**Discovery** (administrator action, outside transactions; `java.net.http.HttpClient` without redirects; bounded responses and timeouts):
1. An unauthenticated MCP request is sent. On `401`, `WWW-Authenticate` `resource_metadata` and `scope` are read. Otherwise the client probes `/.well-known/oauth-protected-resource/<path>` and then the root.
2. The protected-resource metadata `resource` must identify the server URL (RFC 9728 §3.3), and `authorization_servers` must be non-empty. With several servers, the administrator chooses.
3. Authorization-server metadata is probed in the spec order:
   - Issuer with a path: RFC 8414 path insertion, then OIDC discovery with path insertion, then OIDC discovery with path appending.
   - Issuer without a path: RFC 8414, then OIDC discovery.
4. The metadata `issuer` must equal the requested issuer, and `code_challenge_methods_supported` must contain `S256`; otherwise setup refuses.
5. The result (issuer, endpoints, registration options, suggested scopes) is returned for review and persisted only when the administrator creates a client from it.
- Discovered endpoints must pass the MCP endpoint policy; authorization endpoints may carry a query.

**Clients**, in spec priority:
1. Pre-registered (`ADMIN`): the administrator enters client ID and secret, for example a Google `Internal` Web client. `KNOWN_PROVIDER` servers use only this source.
2. Client ID Metadata Document (`METADATA_DOCUMENT`), when `client_id_metadata_document_supported` is true and the redirect origin is HTTPS.
   - The `client_id` is `<redirect origin>/mcp/oauth/client-metadata.json`, served publicly.
   - The document carries `client_id`, `client_name`, `redirect_uris`, the `authorization_code` and `refresh_token` grant types and `token_endpoint_auth_method` `none`.
3. Dynamic Client Registration (`REGISTERED`), when `registration_endpoint` exists. `client_secret` and `registration_access_token` are sealed with their purposes, keyed by the client row.
- A new `V64` adds `token_endpoint_auth_method` (`none`, `client_secret_basic`, `client_secret_post`) and `iss_parameter_required` to `mcp_oauth_client`.

**Authorization request.**
- Parameters: `response_type=code`, `client_id`, `redirect_uri`, `state`, `code_challenge` (`S256`), `resource` and additional parameters. `resource` is the canonical server URL (RFC 8707), sent always; whether Google tolerates it is a live-probe item.
- Scope is the server's configured `oauth_scopes`; the review suggests the challenge `scope`, then `scopes_supported`, for the administrator to save. Empty scopes are omitted. Starting an authorization never runs discovery, so runtime uses only persisted configuration.

**Callback validation and tokens.**
- An `error` parameter ends the flow with its category only.
- If `iss` is present it must equal the client issuer. It is required when `iss_parameter_required` is set (RFC 9207, MCP `2026-07-28`).
- Token request: `code`, `redirect_uri`, `code_verifier` and `resource`, with the stored client authentication. The response must be a `Bearer` token.
- The credential payload (access token, refresh token, scope) is sealed with purpose `CREDENTIAL`, `access_expires_at` is recorded, and upstream bodies are never kept.

**Refresh**, one implementation for administrator (2b) and User (Phase 3) credentials:
- It runs before use when the access token expires within 60 seconds, sending `refresh_token` and `resource` and accepting a rotated refresh token.
- The write is fenced by credential revision. A concurrent refresh that loses reloads and uses the winner's token.
- `invalid_grant` sets `REAUTH_REQUIRED` and maps to `MCP_AUTHORIZATION_REQUIRED`; transport failures map to `MCP_UNAVAILABLE`.
- Disconnect revokes at `revocation_endpoint` best effort (RFC 7009), as MEM-60 does.

**Administrator connect.** `ADMIN`-performer OAuth servers store the shared credential through this flow; tool refresh then uses it. `PER_USER` connect is Phase 3.

**API.**
- `POST /api/mcp/servers/{id}/oauth/discovery` returns the review and persists nothing.
- CRUD under `/api/mcp/servers/{id}/oauth/clients` (create from review, pre-registered or DCR).
- `POST /api/mcp/servers/{id}/oauth/authorization` takes `{clientId}` and returns the authorization URL.
- `GET /mcp/oauth/client-metadata.json` (public) and the hidden callback.

**Tests** use a stub authorization server and the fixture MCP server:
- `401` with `resource_metadata` and well-known fallbacks.
- Path-insertion order and OIDC fallback.
- Refusal without `S256`.
- Issuer mismatch and redirects not followed.
- DCR sealing, CIMD document shape.
- State replay, changed revision, wrong or missing required `iss`.
- Token exchange with `resource`, refresh rotation, `invalid_grant`.

### User connections (Phase 3)

Reuses the Phase 2b protocol, callback chain and refresh; only ownership and authorization differ.

**Access.** A User may use a server when they are an active Tenant member holding `CHAT_WRITE` (a Basic child) and the server is organization-wide or grants one of their Groups. A server outside that set is reported as not found, so restricted servers are not enumerable. The predicate is one SQL fragment in `mcp/persistence`, following `SourceScopeSql`: `mcp` may read `iam_group_memberships` directly, as `chat` and `connector` do.

**One connect path.** `Pending` gains `ownerActorId` (null for the shared administrator credential) and `returnPath`, and one `complete(actor, pending, code, verifier, iss)` serves both. The completing actor must equal `ownerActorId`, and a pending whose ownership disagrees with the server's current performer is a conflict. Per-User connections never change the server status, which reports the shared credential only. `uq_mcp_credential_owner` already keeps one credential per User and server.

**Return.** `returnPath` is captured when the authorization starts, restricted to a relative application path (`/`, `/chat/…`, `/projects/…`, `/admin/mcp`), and the callback returns there with the outcome code. An expired or replayed callback has no pending state, so it returns to `/` rather than to administration, which a User may not open.

**User API key.** The header template allows only `{api_key}`, so the dialog has exactly one field. The key is probed before it is stored: the server is listed with the resolved headers, and a rejection returns `MCP_AUTHORIZATION_REQUIRED` with nothing persisted, the `refreshTools` shape.

**Administrator refresh of a per-User server.** Tool refresh uses the administrator's own credential for that server, not a shared one, and an authorization failure there leaves the server status unchanged. A missing own credential surfaces as `MCP_CREDENTIAL_REQUIRED` for an API-key server and as `MCP_AUTHORIZATION_REQUIRED` for an OAuth one; the administration UI reads both as "connect your account first".

**API.** `GET /api/mcp/connections` lists accessible servers with the User's own connection state, enabled tool count and the OAuth client labels (identifier and label only). `POST /api/mcp/connections/{serverId}/authorization`, `PUT /api/mcp/connections/{serverId}/api-key` and `DELETE /api/mcp/connections/{serverId}/connection` own the rest.

### Chat turn

- Turn command gains `mcpServerIds`; `ChatTurnService` validates access, model `toolCalling()` and credential readiness, like `withWeb`/`withImage`. Composer lists accessible servers with status and connect/API-key actions; ready servers are selected by default.
- One Embabel tool per enabled tool. Model-facing name `mcp_<slug>_<tool>`, `[A-Za-z0-9_.-]{1,64}`; collisions rejected at enablement.
- Bounds follow existing Chat guards: MCP schemas count toward request input tokens with a cap on schemas per turn; calls per turn capped; per-call timeout `min(configured, remaining deadline)`; result text capped by `guard::availableContextTokens`; `guard.checkActive()` around calls; Stop closes the turn's sessions. Values are configuration with defaults set in Phase 4 from measured Drive schemas.
- Activity: existing `ChatToolEvent` stages with a generic MCP renderer in `chat-activity-view.tsx` (server label, tool, duration, connect action on `auth_required`). Arguments and results are neither logged nor streamed.

### Observability

Counter and timer per server/tool with outcome `success`, `tool_error`, `auth_required`, `timeout`, `cancelled`, `transport_error` per the [observability conventions](../../../guidelines/observability.md).

## Scope

In scope: everything in the parity map marked Same or mapped, the departures, improvements 1-5, admin UI (`web/src/features/mcp/`), composer integration, OpenAPI and generated client, ADR 0011 superseding "MCP remains deferred" in ADR 0001, Chat spec and verification matrix, self-host runbook.

Out of scope: stdio; MCP resources, prompts, sampling, elicitation (Onyx client does not use them); Craft; MemoryOS as MCP server ([MEM-114](https://linear.app/memory-os/issue/MEM-114)); write-tool approval (candidate).

## Deployment constraints

- Outbound HTTPS to configured MCP/OAuth endpoints. Google: `accounts.google.com`, `oauth2.googleapis.com`, `drivemcp.googleapis.com`.
- Redirect URIs: HTTPS, no raw IP, host under a public suffix; internal-only DNS is sufficient because the browser performs the redirect.
- CIMD differs: the authorization server fetches MemoryOS's client metadata document, so that URL must be reachable by it. Self-hosted deployments without inbound access use `KNOWN_PROVIDER` or DCR.
- Each Google Workspace organization creates an `Internal` Web client, enables Drive API and Drive MCP API, adds the MCP callback; Workspace admins may need to trust the app under API controls.
- Google issues a refresh token only with `access_type=offline`, and on reconnection only with `prompt=consent`. Administrators put both in the server's additional authorization parameters. Without them a connection expires after about an hour and becomes `REAUTH_REQUIRED`.

## Verification

- Unit: name mapping, schema bounds, result conversion, header templates and denylist, OAuth state validation, discovery persistence, secret binding, error categories.
- Integration without Google: in-process MCP servers built with `HttpServletStreamableServerTransportProvider` and the SSE provider; a stub authorization server for discovery, DCR, PKCE, refresh, `invalid_grant`; session reuse; timeout; Stop; Tenant and Group isolation.
- API: `MCP_MANAGE`, performer rules, OpenAPI contract.
- Web: admin and composer unit tests, Chromium scenario with fixture servers.
- Live (manual, `verification.md`): Google Drive MCP with two organizations' `Internal` clients; wrong-organization User gets `org_internal`; a write tool (`create_file`) on a test folder.
