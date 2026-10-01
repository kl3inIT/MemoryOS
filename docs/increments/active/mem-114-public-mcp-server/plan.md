# MEM-114 plan

Design: [design.md](design.md). UI references: [ui-references.md](ui-references.md).

Four pull requests after this documentation one. Linear closes MEM-114 when a pull request that names it merges, so
reopen it after each intermediate merge.

## 0. Decisions and increment

- [x] Owner decisions recorded 2026-10-01 (see design).
- [x] Increment, roadmap entry and the MEM-134 egress note.
- [x] Linear:
  - MEM-114 In Progress;
  - MEM-124 changed from blocking to related;
  - decisions comment on MEM-114;
  - Gemini Enterprise issue [MEM-205](https://linear.app/memory-os/issue/MEM-205).

## 1. Spike, not committed

Run on 2026-10-01 on the staging host as throwaway containers bound to loopback, reached through an SSH tunnel, and
removed afterwards.

- A standalone Spring Boot 4.1.1 app with Spring AI 2.0.1 `spring-ai-starter-mcp-server-webmvc`, MCP SDK 2.0.1 and the
  design's security chain, protocol-version filter and two stub tools.
- A throwaway Keycloak 26.8.0 (`start-dev --features=cimd`) with the design's scope, audience mapper and CIMD policy.

Not the MemoryOS `api` itself; the shared Keycloak was not touched.

- [x] **Transport and tools.**
  - SDK 2.0.1 runs under Spring AI 2.0.1's stateless WebMVC transport.
  - `initialize` negotiates `2025-11-25` and returns the instructions.
  - `tools/list` shows `title`, the four hints as set, and object input and output schemas.
  - `tools/call` returns `structuredContent` plus the JSON text.
  - `resources/list` answers JSON-RPC `-32601` instead of 500.
- [x] **Actor.** The SYNC stateless tool read `sub` and `azp` from `SecurityContextHolder` on the request thread.
- [x] **Protocol-version filter.** ChatGPT's exact `server/discover` probe got 400 with the `-32000` body. The
  anonymous probe still got the 401, so the filter runs after security.
- [x] **Resource server.**
  - Anonymous calls get 401 with `resource_metadata`.
  - The RFC 9728 document carries the fixed `resource`, the issuer and `scopes_supported`.
  - A token without `knowledge:read` also lacks the endpoint audience, so it gets 401 for `aud`, not 403. See the
    design.
- [x] **Keycloak discovery.**
  - 26.8.0 with `cimd` advertises `client_id_metadata_document_supported`, `none` and
    `authorization_response_iss_parameter_supported`.
  - It serves both the RFC 8414 path-inserted and the OIDC metadata paths.
- [x] **Claude Code's flow through CIMD**, reproduced by hand: `client_id`
  `https://claude.ai/oauth/claude-code-client-metadata`, loopback redirect `http://localhost:53682/callback`, PKCE
  S256, `resource`, scope `knowledge:read offline_access`, and a public-client code exchange.
  - Keycloak admitted the document and accepted the loopback port the document does not list.
  - It showed a consent page titled "Grant Access to Claude Code" with the scope's Vietnamese text.
  - It returned `iss` on the callback.
  - The access token has `aud` equal to the endpoint, `azp` equal to the document URL, and a 300 s lifetime.
  - The refresh token is an offline token with a 30-day lifetime.
  - The tool call succeeded with that token.
- [x] **Offline refresh** keeps `aud` equal to the endpoint without `resource-indicators` (Keycloak #53261 does not
  apply to this path).
- [x] **Revocation.** Deleting the user's consent through the admin API removed the offline session: the next refresh
  got `invalid_grant` ("Offline user session not found"). An access token already issued kept working until it
  expired, as designed.
- [x] **One client per document.** Keycloak stores the document as one public, consent-required client shared by
  every user, not one per connection.
- [ ] **Not covered.**
  - Starting the server and client auto-configurations in the MemoryOS `api` context, which pull request 2's
    integration tests cover.
  - Claude Code's and Claude web's own user interface.
  - ChatGPT, which needs a public HTTPS endpoint; it is checked at staging acceptance.
- [x] **Dependency check** (2026-10-01). The `api` runtime classpath already resolves, through Embabel:
  - `spring-ai-starter-mcp-client` 2.0.1;
  - `spring-ai-mcp-annotations` 2.0.1;
  - `io.modelcontextprotocol.sdk:mcp` 2.0.0.

  No `spring.ai.mcp.*` property is set, so the client auto-configuration starts with no connections. The server
  starter adds only the server auto-configuration and the WebMVC transport. Starting both auto-configurations in one
  context is still to be confirmed in the runtime spike.

## 2. Keycloak and routing (pull request 1)

- [x] **Image.** `infrastructure/keycloak/Dockerfile` pins 26.8.0 by digest and builds with `KC_FEATURES=cimd`.
  `smoke-test-image.sh` now also fails an image that does not advertise metadata documents.
- [x] **`configure-memoryos-realm.sh`**, everything behind `MEMORYOS_MCP_ENDPOINT_URL`, which must equal the browser
  origin followed by `/mcp`:
  - client scope `knowledge:read` with consent text and the endpoint audience mapper;
  - CIMD client profile and policy, plus a separate S256 PKCE policy for the same documents;
  - `memoryos-chatgpt` when `MEMORYOS_MCP_CHATGPT_CLIENT_SECRET` is set, with its scopes reconciled exactly;
  - the realm's default client scopes without `profile`, `email`, `roles` and `web-origins`, while every client the
    script creates pins the classic set;
  - 30-day offline idle.

  Contract tests: `test_realm_mcp_endpoint.py`. Every new `jq` expression was run against sample data in a `jq` 1.8.1
  container.
- [x] **End to end** (2026-10-01). On the staging host, a throwaway PostgreSQL and the image built from this
  Dockerfile (loopback only, removed afterwards). The shared Keycloak was not touched.
  - **Image.** `start --optimized` advertises `client_id_metadata_document_supported`, so the build-stage
    `KC_FEATURES` is enough.
  - **Runs.** The script ran first without the endpoint, the way staging and production run it today, then three
    times with it.
  - **Two defects found and fixed.**
    - Naming default scopes at creation left the script's clients with no optional scopes, so the optional set is now
      pinned too.
    - Reassigning the realm's optional scope answers 409, so the rerun now checks before assigning.
  - **Realm state after the runs.**
    - The realm's default scopes no longer include `profile`, `email`, `roles` or `web-origins`.
    - `memoryos-web`, `memoryos-integration` and `memoryos-user-provisioner` keep the classic default and optional
      sets.
    - `memoryos-chatgpt` has exactly `acr`, `basic`, `knowledge:read` plus optional `offline_access`, one callback,
      consent required.
    - Audience, resource allow list and 30-day offline idle as designed.
  - **PKCE.** A PKCE policy keyed on `client-id-uri` never enforced: that condition votes only on the
    pre-authorization event, and the enforcer acts on the authorization and token requests. Keyed on
    `client-access-type: public`, a metadata-document request without `code_challenge` is refused with
    `invalid_request` ("Missing parameter: code_challenge_method"). `memoryos-integration` keeps working with S256.
  - **Claude Code.** Its metadata document flow with a member shows a consent page listing only the scope text,
    Offline Access and the client's hostname. The token carries `acr, aud, auth_time, azp, exp, iat, iss, jti, scope,
    sid, sub, typ` and no profile or e-mail claim. `aud` is the endpoint.
  - **ChatGPT client.** The flow asked only for `offline_access` and still received `knowledge:read`, a default
    scope there.
    - The consent page shows "Grant Access to ChatGPT" with the scope text and Offline Access.
    - The callback carries `iss`, and the secret plus PKCE code exchange returns a token with `aud` equal to the
      endpoint.
    - Another `chatgpt.com` callback is refused.
- [x] **nginx.** One exact-path location sends `/mcp`, `/.well-known/oauth-protected-resource/mcp` and MEM-112's
  `/mcp/oauth/client-metadata.json` to the API with a 256 KiB body limit. Before this, MEM-112's metadata document
  fell to the web app's `index.html`. Covered in `nginx-config.test.mjs`.
- [x] **Runbook.** [mcp-endpoint.md](../../../runbooks/mcp-endpoint.md): production upgrades with the next release
  (database dumped first); the shared staging Keycloak needs the OrgMemory image rebuilt on 26.8 with `cimd`; realm
  variables and expected output; removal.
- [x] **Consent page** (moved here from pull request 3 by owner decision, 2026-10-01):
  - the `memoryos` realm speaks Vietnamese first and English;
  - the theme words the consent page in both languages;
  - the permissions are ordered by `gui.order`;
  - the client's own line reads as a note;
  - the language switcher sits under the card;
  - `memoryos-chatgpt` carries its logo.

  Screenshots and the reasoning are in [ui-references.md](ui-references.md#keycloak-consent-page). Re-verified end to
  end on the staging host the same way.
- [ ] **Server actions.** Staging image rebuild and recreate, realm replay, production promotion: each only after the
  owner approves that step.

## 3. Endpoint (pull request 2)

- [x] **Versions.** `mcp-sdk` 2.0.1. `api` adds the MCP server starter, `bucket4j` 8.20.0 and `caffeine`.
- [x] **Transport configuration.** Stateless and SYNC at `/mcp`, tool capability only, server instructions.
  `spring.ai.mcp.server.tool-callback-converter` is off: Spring AI otherwise publishes every `ToolCallback` bean of the
  application, Chat's own tools included, through the endpoint.
- [x] **Security chain** (`McpEndpointSecurityConfiguration`, order 0, exactly `/mcp` and its metadata path).
  - Decoder with the exact issuer, the endpoint URL as audience and a non-blank subject; it is not a bean, so the API's
    decoder stays unambiguous.
  - `knowledge:read` and an active Tenant membership required.
  - RFC 9728 metadata with a fixed `resource`.
  - The 401 names the metadata document and the scope.
  - `JwtToActorAuthenticationConverter` reused.
  - First in the chain, a gate answers 404 while the endpoint is off and 403 for a foreign `Origin`.
- [x] **Protocol-version filter.** A servlet filter after security; 400 with `-32000` for an unsupported
  `MCP-Protocol-Version`.
- [x] **`search` and `fetch`** (`McpEndpointTools`).
  - At most 10 results, with text cut at 2,000 characters per result and 100,000 for `fetch`.
  - `sourceNumber`; `url` is the provider link or `/search?doc=<id>`.
  - Four hints and object output schemas.
  - `DocumentSearchService.currentDocument` reads by Document id at its current generation; `providerUrl` comes from
    the Source mappings the actor may read.
- [x] **Endpoint switch.**
  - `mcp_endpoint_setting` (V135).
  - `McpEndpointService` with revision fencing and `MCP_MANAGE`.
  - `AuditAction.MCP_ENDPOINT_CHANGE` (`mcp_endpoint.change`).
  - `MEMORYOS_MCP_ENDPOINT_URL` through Compose and the environment examples.
  - 404 while off.
- [x] **Administration API.** `GET`/`PUT /api/mcp/endpoint`.
  - `openapi.yml` was edited by hand to springdoc's shape for the two operations and two schemas: this host lacked the
    memory to run the contract test with the write flag. CI's `OpenApiContractTest` checks it.
  - The Hey API client is regenerated.
- [x] **Limits** (`McpEndpointRequestFilter`, after bearer authentication).
  - 413 above 256 KiB, declared or chunked.
  - `tools/call` costs one token per caller (person and client) and one from the endpoint. Defaults are 60 and 600 per
    minute; an empty bucket answers 429 with `Retry-After`.
  - Failures reach the client as fixed sentences without a cause.
- [x] **Observability.**
  - Timer `memoryos.mcp.endpoint.call` with `tool` and `outcome`.
  - Log events `mcp_endpoint.tool.refused` (with `error_code`), `mcp_endpoint.tool.failed` and
    `mcp_endpoint.rate_limited`.
- [ ] **Tests** (`McpEndpointIntegrationTest`: the real filter chains and signed tokens; search itself is replaced).
  - Off switch.
  - The 401 challenge and the metadata.
  - Audience and scope, and an endpoint token refused on `/api`.
  - Exactly two read-only tools with object schemas.
  - Search evidence and its link.
  - A cause-free failure.
  - ChatGPT's probe.
  - Foreign origin, 413 and 429 per caller.
  - The switch through the API, with its audit.

  Readability through PUBLIC, PRIVATE and SYNC is `DocumentSearchService`'s own tested contract. A duplicated YAML key
  already fails every context test, so there is no separate YAML test. Running the tests is left to CI.
- [ ] **Gates.** `ModulithArchitectureTest`, `CoreDependencyRulesTest`, `OpenApiContractTest`, then `clean check` on CI.
- [ ] **Onyx's MCP server**, studied from `.tmp/onyx` (40eb240df): its instructions and tool descriptions are compared
  before the pull request.

## 4. Web, grants and consent (pull request 3)

- [ ] **Grants.** `iam` reads and deletes the member's consents to MCP clients through the Keycloak admin API; API
  routes and generated client.
- [ ] **`/admin/mcp`.** *MemoryOS MCP endpoint* section with the switch, URL and copy.
- [ ] **`/settings/connections`.** URL, Claude and ChatGPT tabs, grants table with Revoke.
- [ ] **Document link.** `/search?doc=<id>` opens the document dialog.
- [x] **Keycloak consent page.** Delivered in pull request 1.
- [ ] **Copy.** Vietnamese and English for every string; no explanatory copy under controls.
- [ ] **Checks.** `pnpm --dir web check`; screenshots with realistic data, reviewed and fixed.

## 5. Acceptance and documentation (pull request 4)

- [ ] **Staging.** Switch on, with a test member who reads only test Documents.
- [ ] **Probes.** The curl probes in the design.
- [ ] **Clients.** Claude web, Claude Code, ChatGPT web; revoke; switch off. If ChatGPT fails, open its own issue and
  record why.
- [ ] **Evidence.** `verification.md`.
- [ ] **Documents.**
  - The ADR for the endpoint.
  - The spec and test matrix.
  - `ARCHITECTURE.md`: the module table, and "no MCP server" under candidates.
  - The roadmap.
  - The MEM-134 design note made concrete.
- [ ] **Production.** Deployed with the switch off.
- [ ] **Close.** After merge and acceptance, move this increment to `completed/`.
