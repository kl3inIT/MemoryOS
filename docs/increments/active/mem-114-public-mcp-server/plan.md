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

## 1. Spike, local and not committed

- [ ] **Starter in `api`.** Add `spring-ai-starter-mcp-server-webmvc` and confirm:
  - no conflict with the MCP client auto-configuration from Embabel;
  - `spring.ai.mcp.server.streamable-http.mcp-endpoint` binds;
  - a SYNC stateless tool reads the actor from `SecurityContextHolder`;
  - `generateOutputSchema` returns `structuredContent` plus text.
- [ ] **Keycloak.** Keycloak 26.8.0 locally with `--features=cimd` and the CIMD policy; Claude Code completes sign-in
  through CIMD.
- [ ] **Record.** Write the results here. Raise any deviation from the design before continuing.
- [x] **Dependency check** (2026-10-01). The `api` runtime classpath already resolves, through Embabel:
  - `spring-ai-starter-mcp-client` 2.0.1;
  - `spring-ai-mcp-annotations` 2.0.1;
  - `io.modelcontextprotocol.sdk:mcp` 2.0.0.

  No `spring.ai.mcp.*` property is set, so the client auto-configuration starts with no connections. The server
  starter adds only the server auto-configuration and the WebMVC transport. Starting both auto-configurations in one
  context is still to be confirmed in the runtime spike.

## 2. Keycloak and routing (pull request 1)

- [ ] **Image.** `infrastructure/keycloak/Dockerfile` pins 26.8.0 by digest and starts with `--features=cimd`. Check
  the theme still renders.
- [ ] **`configure-memoryos-realm.sh`:**
  - client scope `knowledge:read` with consent text and the endpoint audience mapper;
  - CIMD client policy and profile;
  - PKCE policy for public clients;
  - `memoryos-chatgpt`;
  - `offline_access` and the 30-day offline idle for MCP clients.
- [ ] **nginx.** Exact `location` blocks for `/mcp` and `/.well-known/oauth-protected-resource/mcp` in
  `web/nginx.conf`, plus cases in `nginx-config.test.mjs`. Check MEM-112's `/mcp/oauth/client-metadata.json` route.
- [ ] **Runbook.** Database backup, the shared staging container with the OrgMemory image moved to 26.8, the
  production image, replaying the realm script.
- [ ] **Server actions.** Backup, staging upgrade, production upgrade: each only after the owner approves that step.

## 3. Endpoint (pull request 2)

- [ ] **Versions.** `mcp-sdk` 2.0.1. `api` adds the MCP server starter, `bucket4j` and `caffeine`.
- [ ] **Transport configuration.** Stateless and SYNC, `/mcp`, tool capability only, instructions.
- [ ] **Security chain.**
  - Endpoint-audience decoder, `knowledge:read`.
  - Protected-resource metadata with a fixed `resource`.
  - 401 and 403 challenges, Origin check, `JwtToActorAuthenticationConverter`.
- [ ] **Protocol-version filter.** After security; 400 with `-32000` for an unsupported `MCP-Protocol-Version`.
- [ ] **`search` and `fetch`.**
  - Shapes, caps, `sourceNumber`, `url`, annotations and output schemas.
  - The current-generation read on `DocumentSearchService`.
- [ ] **Endpoint switch.**
  - `mcp` settings row and migration.
  - `McpEndpointService` with revision fencing and `MCP_MANAGE`.
  - `AuditAction.MCP_ENDPOINT_CHANGE`.
  - `MEMORYOS_MCP_ENDPOINT_URL`.
  - 404 when off.
- [ ] **Administration API.** The switch, then the OpenAPI snapshot and Hey API client regenerated.
- [ ] **Limits.** Rate limit on `tools/call` per caller and globally, request size limit, failure boundary.
- [ ] **Observability.** `memoryos.mcp.endpoint.call` and the log events.
- [ ] **Tests.**
  - Annotations and object output schemas.
  - Token validation: issuer, audience, expiry, scope, cross-use with `/api/**`.
  - Readability through PUBLIC, PRIVATE and SYNC.
  - The switch off.
  - The protocol-version filter.
  - 429 and 413.
  - The error surface.
  - Single-document YAML.
- [ ] **Gates.** `ModulithArchitectureTest`, `CoreDependencyRulesTest`, `OpenApiContractTest`, then `clean check` (CI
  when local memory is short).

## 4. Web, grants and consent (pull request 3)

- [ ] **Grants.** `iam` reads and deletes the member's consents to MCP clients through the Keycloak admin API; API
  routes and generated client.
- [ ] **`/admin/mcp`.** *MemoryOS MCP endpoint* section with the switch, URL and copy.
- [ ] **`/settings/connections`.** URL, Claude and ChatGPT tabs, grants table with Revoke.
- [ ] **Document link.** `/search?doc=<id>` opens the document dialog.
- [ ] **Keycloak consent page.** Checked under the `memoryos` theme in Vietnamese and English.
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
