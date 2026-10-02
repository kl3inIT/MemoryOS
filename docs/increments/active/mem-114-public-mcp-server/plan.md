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
  application, Chat's own tools included, through the endpoint. Because 2.0.1 builds `@McpTool` specifications only
  while that converter is on, the annotation scanner is off and `McpEndpointConfiguration` registers the two tools,
  with a failure's sentence once rather than twice.
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
  - A cause-free failure, and a refused call that says what to change, each as one sentence.
  - A client that accepts only JSON is served.
  - ChatGPT's probe.
  - Foreign origin, 413 and 429 per caller.
  - The switch through the API, with its audit.

  Readability through PUBLIC, PRIVATE and SYNC is `DocumentSearchService`'s own tested contract. A duplicated YAML key
  already fails every context test, so there is no separate YAML test. Running the tests is left to CI.
- [ ] **Gates.** `ModulithArchitectureTest`, `CoreDependencyRulesTest`, `OpenApiContractTest`, then `clean check` on CI.
- [x] **Onyx's MCP server**, studied from `.tmp/onyx` (40eb240df). Adopted:
  - tool descriptions that say when to call the tool, what it returns and one example call;
  - parameter descriptions that state their limits;
  - a refused call that tells the client what to change, per tool;
  - each search result names its sources;
  - an `Accept` header lacking JSON or event stream is completed rather than refused.

  Not adopted: Onyx's own auth server and API-key fallback, its per-call database session, resources, and a
  configurable result count.

## 4. Grants, reads and web (pull requests 3a and 3b)

Split in two so CI checks the Keycloak side before the screens are built on its generated client.

**3a: backend and contract.**

- [x] **Grants.** `iam` lists and revokes the member's consents to MCP clients through the Keycloak admin API.
  - A grant is a consent whose granted scopes include `knowledge:read`. That scope is one constant in `iam`, which the
    endpoint's security chain also uses.
  - The member's Keycloak user is their binding under the realm's issuer.
  - Revoking accepts only a client from that list; the client ID travels as a query parameter, because Claude's is a
    URL.
  - Probed on Keycloak 26.8 on 2026-10-01 with a URL client ID: `GET …/users/{id}/consents` lists the client, scopes,
    dates and offline token. `DELETE …/consents/{encoded client ID}` answers 204, and the next refresh fails with
    "Offline user session not found".
- [x] **ChatGPT client for administrators.** The API reads `MEMORYOS_MCP_CHATGPT_CLIENT_SECRET` (Compose mounts it as the
  `mcp_chatgpt_client_secret` secret file; every server needs the file before this release deploys);
  `GET /api/mcp/endpoint` returns the client ID and secret, null without one.
- [x] **Member read.** Whether the endpoint is available and its URL, under `SEARCH_READ`.
- [x] **Document read without a generation.** `GET /api/search/documents/{id}` resolves the current generation when
  none is given, so a link carrying only the id can open the document.
- [x] **Contract.** `openapi.yml`, `OpenApiContractTest` paths and the regenerated web client.

**3b: web.**

- [x] **`/admin/mcp-endpoint`.** *MemoryOS MCP* page next to *Máy chủ MCP*: switch, URL, ChatGPT client ID and
  masked secret.
- [x] **`/settings/mcp`.** *MemoryOS MCP* settings item next to *Connections*: URL, Claude and ChatGPT tabs,
  authorized apps with Thu hồi. A status line replaces the URL and tabs while the endpoint is off.
- [x] **Document link.** The Search page accepts `?doc=<id>` and opens that document's dialog.
- [x] **Audit label.** `mcp_endpoint.change` in the audit page's action list, in both languages.
- [x] **Keycloak consent page.** Delivered in pull request 1.
- [x] **Copy.** Vietnamese and English for every string; no explanatory copy under controls.
- [x] **Checks.** `pnpm --dir web check` on CI; sixteen captures (both pages, both themes, 1280 and 390 px) from
  `tests/e2e/mcp-endpoint.spec.ts` with a Tenant's realistic values, reviewed. Fixed from the review: the grant date no
  longer breaks mid-date on a phone, and a capture waits for the selected tab to finish drawing.

## 4b. Search filters (pull request 3c)

- [x] **Owner decisions** (2026-10-02): a separate `search_with_filters` tool; snake_case inputs; enums for sources
  and file types; document sets by name; `updated_before` as well as `updated_after`; a period is the provider's
  update date, in one pull request with the Search page's calendar range.
- [x] **Provider dates.** Drive and SharePoint record their dates on every run; `SourceMetadataChanged` queues the
  index field refresh.
- [x] **Search window.** `SearchRequest.updatedFrom`/`updatedTo` replace `updatedSince`; the index and the readable
  origins both apply `SearchFilters.Interval`; a result shows the provider date. `openapi.yml` and the web client.
- [x] **Search page.** The Updated filter offers the presets beside a calendar range (`from`/`to` in the address).
- [x] **MCP.** `search_with_filters`, the `search` description's pointer to it, and the server instructions.
- [x] **Tests.** `SearchRequestTest`, `DocumentSearchServiceTest`, `PostgresGoogleDriveSyncTest`,
  `SearchIndexWorkIntegrationTest`, `McpEndpointIntegrationTest`, `OpenApiContractTest`, `search-page.test.tsx`,
  `search.spec.ts` with captures at 1280 and 390 px in both themes, reviewed. Fixed from the review: a day after
  today no longer looks selected; the range band and today's mark use the sunken surface, which `muted` was not
  distinct from in the dark theme; a picked day keeps readable colours under the pointer in the dark theme.
- [ ] **CI**, including `OpenSearchRetrievalIntegrationTest`, which needs more memory than the workstation had.
- [ ] **After deployment.** Until each Drive and SharePoint Source runs once, its items have no date: a recent window
  or one with an end leaves them out. Items synchronized before V35 carry their ingestion time until that run.
  Connected Claude and ChatGPT clients see the new tool after they refresh their tool list.

## 4c. Linked citations and instruction review (2026-10-02)

- [x] Citations in Claude and ChatGPT showed as `[1]` and could not be opened: the instructions asked for a bare
  `[n]`. As Onyx and Glean do, the server instructions and every tool description now ask for a Markdown link to the
  result's `url`; a test keeps `[n]` out.
- [x] Instruction review: `fetch` and the invalid-id sentence name both search tools; `search` says what `url` and
  `updatedAt` mean (`updatedAt` is the provider's date since pull request 3c).
- [ ] After deployment, refresh the ChatGPT plugin and reconnect Claude, then check a cited answer opens its sources.

## 4d. Document links open the original (2026-10-02)

- [x] A citation from Claude or ChatGPT to an uploaded PDF opened MemoryOS on the extracted text only: the document read
  returned no media type. `SearchDocument` and the read now carry `mediaType` (the index's header hit), and
  `/search?doc=<id>` opens on the original; download-only formats keep opening on the text.
- [x] Tests: `DocumentSearchServiceTest`, `OpenApiContractTest`, `search-page.test.tsx` (a PDF link and a
  download-only one), `search.spec.ts` with the 12-page PDF fixture, captures at 1280 and 390 px reviewed.
- Opening at the cited passage moved to [MEM-209](https://linear.app/memory-os/issue/MEM-209).

## 5. Acceptance and documentation (pull request 4)

- [ ] **Staging Keycloak from the release.** Staging ran the 26.7 image it once shared with OrgMemory, which has no
  `cimd`. OrgMemory is retired, so staging takes the release's `memoryos-keycloak` like production.
  - [x] Repository: `deploy.sh` no longer lets an environment file name a Keycloak image; the staging overlay drops
    the mounted theme and the `orgmemory-keycloak`, `shared-keycloak` and `memoryos-shared-keycloak` aliases. Checked
    on the host on 2026-10-01: no running container's environment or command names them, and only the proxy host of
    `auth.kl3in.tech` forwards to `orgmemory-keycloak`.
  - [ ] Host, before the merge: point that proxy host at `memoryos-keycloak`.
  - [ ] Host, with the deployment: `MEMORYOS_MCP_ENDPOINT_URL` in `.env.staging`, so one deployment recreates
    Keycloak and gives the API its endpoint. Keep the deployment's `keycloak.dump`.
  - [ ] Realm script with the endpoint URL and the ChatGPT secret; the realm then advertises metadata documents.
  - [ ] Remove what OrgMemory left on the host: the `orgmemory` realm once its clients are confirmed unused, the
    `orgmemory-docs` project, its volumes and network, `/apps/orgmemory*`, the old Keycloak image and the proxy hosts
    of `om.kl3in.tech` and `docs.kl3in.tech`. `zeromail-postgres` runs an image named after OrgMemory and stays.
- [x] **Staging Keycloak takeover done** on 2026-10-01: proxy repointed, release deployed (26.8 with `cimd`), realm
  script run, `keycloak.dump` kept in `/apps/memoryos-backups`. Claude web connected and called both tools.
- [x] **ChatGPT through its metadata document.** ChatGPT's personal plugin was refused (`client_not_found`): the
  policy did not trust `chatgpt.com`, and Keycloak 26.8 cannot read its document anyway (#51236).
  - [x] The image's `memoryos-client-id-metadata-document` executor (upstream fix #51235); `chatgpt.com` and
    `persistent.oaistatic.com` trusted; the smoke test requires the executor.
  - [x] The ChatGPT tab of *Cài đặt › MemoryOS MCP* gives a personal plugin's steps.
  - [x] The sign-in page's 26.8 strings in Vietnamese ("hoặc", the Tasco button without "Sign in with") and the
    language menu with its caret inside the control.
  - [x] Staging: deployed 2026-10-01 (the first attempt timed out on SSH from the runner and was rerun); realm
    script rerun; an authorization request with ChatGPT's client ID reaches the sign-in page.
  - [x] ChatGPT passed the client check and was refused `invalid_scope`: it asks for `email`, which a client built
    from a metadata document lacked. `email` becomes an optional scope of such clients, existing ones included.
  - [x] A personal ChatGPT plugin connects and calls the tools (staging, 2026-10-02).
  - [x] Removing `memoryos-chatgpt`, its secret file and the administration page's ChatGPT fields moved to
    [MEM-207](../mcp-endpoint-governance/plan.md), with the administrator's list of trusted apps; Dynamic Client
    Registration for Gemini is MEM-207's second part.
- [ ] **Staging.** Switch on, with a test member who reads only test Documents.
- [ ] **Probes.** The curl probes in the design.
- [ ] **Clients.** Claude web, Claude Code, ChatGPT web; revoke; switch off. Confirm that Claude, whose client comes
  from a metadata document, appears in the authorized apps list. If ChatGPT fails, open its own issue and
  record why.
- [ ] **Evidence.** `verification.md`.
- [ ] **Documents.**
  - The ADR for the endpoint.
  - The spec and test matrix.
  - `ARCHITECTURE.md`: the module table, and "no MCP server" under candidates.
  - The roadmap.
  - The MEM-134 design note made concrete.
- [ ] **Production.** Deployed with the switch off: endpoint configured on 2026-10-03 (`https://app.vadan.app/mcp`,
  realm reconciled, see the [governance plan](../mcp-endpoint-governance/plan.md)); the owner turns the switch on.
- [ ] **Close.** After merge and acceptance, move this increment to `completed/`.
