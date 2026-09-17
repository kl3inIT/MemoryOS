# MEM-112 verification

## Phase 1 — 2026-09-15

Branch `mem-112/chat-mcp-client`, based on main `82ef9050` (MEM-110 merged). JDK 25.0.2, Gradle 9.7.1, Docker 29.7.2 for Testcontainers.

| Check | Command | Result |
| --- | --- | --- |
| Core compile | `gradlew :core:compileJava :core:compileTestJava` | Passed |
| MCP client against a real SDK server | `McpClientsIntegrationTest` (embedded Tomcat, `HttpServletStreamableServerTransportProvider`, bearer check in front) | 7 tests passed: tool listing with schemas/annotations; text, `isError` and structured-content conversion (text starting with `[` is not mistaken for an omission marker); rejected bearer (401) and `insufficient_scope` (403) → `MCP_AUTHORIZATION_REQUIRED`; 700 ms deadline → `MCP_TIMEOUT`; closed port → typed failure without host detail; endpoint/header policy (internal hosts allowed; credentials, query, fragment, reserved and CR/LF headers rejected) |
| Secret sealing | `McpSecretsTest` | 4 tests passed: Tenant/record/purpose binding, fresh IV, tamper and wrong-key rejection, missing/invalid key handling |
| V69 schema and entity mapping | `McpPersistenceIntegrationTest` (PostgreSQL 18.4, Hibernate `validate` over IAM, Chat and MCP entities) | 4 tests passed: several labelled OAuth clients per server, Group access, tool snapshot, per-User credential; slug uniqueness, OAuth mode and `NONE` performer checks, cross-Tenant Group rejection, one credential per owner and one shared, client bound to its own server, cascade on server delete, object-only tool schema, `MCP_MANAGE` grantable and unknown capabilities rejected |
| Capability fan-out | `IamCapabilityTest`, `DefaultGroupServiceAuthorizationTest`, `GroupSchemaIntegrityTest`, `GroupMigrationSeedTest` | 24 tests passed |
| Module boundaries | `ModulithArchitectureTest`, `CoreDependencyRulesTest` (`mcp.persistence` owned by `mcp`) | Passed with eight closed modules |
| OpenAPI contract | `MEMORYOS_OPENAPI_WRITE=true gradlew :api:test --tests io.memoryos.api.OpenApiContractTest` | Passed; `GroupCapability` enums gained `MCP_MANAGE`. The first attempt's test JVM was killed by host native-memory exhaustion (`hs_err` "insufficient memory … G1 virtual space"); the unchanged rerun passed and the crash log was removed |
| Web client and types | `pnpm generate:api`, `pnpm exec tsc -b --noEmit` | Generated types updated; typecheck passed |
| Web capability copy | `vitest run src/i18n/app-translation.test.tsx src/features/groups`, `pnpm check:i18n`, `oxlint --deny-warnings`, `oxfmt --check` on changed files | 9 tests passed; Vietnamese translations added for the new label and description; audit, lint and format clean |

Repository gate on commit `e46c6c8d`, on a host with 14 GB RAM and 1.5–3 GB free:
- **First runs:** `--max-workers=2` died of native-memory exhaustion (`hs_err` in the `:api:test` executor and the `:connector:test` Tika subprocess). A `--max-workers=1` rerun was killed by the OS for low memory.
- **`gradlew clean check --no-daemon --max-workers=1 --continue`:**
  - `:api:check`, `:connector:check` and `:worker:check` passed.
  - `:core:test` lost one test JVM to `OutOfMemoryError: Java heap space` (1 GiB fork heap). The error pointed at the 100 MiB allocation in `ObjectWriteLifecycleIntegrationTest.binaryWritesRejectOneByteBeyondOneHundredMiB`, although that suite's report records 8/8 passed.
  - 81 of 94 core suites reported, with no failures. The 13 that never ran were the remaining `objectstorage` and `retrieval` suites.
- **Rerun:** `gradlew :core:test --max-workers=1 --tests 'io.memoryos.mcp.*' --tests 'io.memoryos.objectstorage.*' --tests 'io.memoryos.retrieval.*'` passed 18 suites and 95 tests, with 0 failures and 1 opt-in measurement skipped. The heap error did not reproduce.

Every Gradle test suite has therefore passed, but across two runs rather than one uninterrupted `clean check`. CI remains the single-run gate.

## Phase 2a — 2026-09-16

| Check | Command | Result |
| --- | --- | --- |
| Server rules | `gradlew :core:test --tests io.memoryos.mcp.McpServerRulesTest` | 7 tests passed: slug, header template (`{api_key}` only on API-key servers, reserved and duplicate headers), default bearer header and key substitution, OAuth scope/parameter rules, model tool-name limits, snapshot bounds and hints, fail-closed stored JSON |
| Module boundaries | `ModulithArchitectureTest`, `CoreDependencyRulesTest` | 3 tests passed |
| IAM effective authorization | `gradlew :core:test --tests 'io.memoryos.iam.group.*'` | 41 tests passed |
| Administration API | `ChatSessionApiIntegrationTest.mcpAdministrationSealsSecretsRefreshesToolsAndFencesRevisions` (shared context, in-process Streamable HTTP server requiring the resolved headers) | Passed: `MCP_MANAGE` required; key and static header value sealed (`v1:`) and absent from responses and request `toString`; duplicate slug 409; refresh lists tools with the sealed key and template, status `CONNECTED`; over-long composed tool name refused at enablement; stale revision 409 `MCP_CONFLICT`; URL change removes credentials and tools (`AWAITING_AUTH`); userinfo URL 400; delete then 404. The catalog test in the same class still passes |
| OpenAPI contract | `MEMORYOS_OPENAPI_WRITE=true gradlew :api:test --tests io.memoryos.api.OpenApiContractTest` | Passed with seven `/api/mcp` paths; the other diff is operation reordering only (no operation removed) |
| Web client | `pnpm generate:api`, `tsc -b --noEmit`, `oxlint --deny-warnings` and `oxfmt --check` on changed files, `vitest run src/features/groups src/i18n/app-translation.test.tsx` | Generated; typecheck, lint and format clean; 9 tests passed |

Phase 1 defect found by the API test: the effective-capability SQL in `IamAuthorizationRepository` and `GroupProjectionRepository` listed ordinary grants explicitly without `MCP_MANAGE`, so a granted Group did not authorize. Both lists now include it; the API test is the regression.

| Full API suite after the shared-context property change | `gradlew :api:test --no-daemon --max-workers=1` on `2b68a03e` | 21 suites, 166 tests, 0 failures; no JVM crash |

Not run for 2a: `gradlew clean check` and the full web `pnpm check`.

## Phase 2b — 2026-09-16

| Check | Command | Result |
| --- | --- | --- |
| V70 and IAM | `gradlew :core:test --tests 'io.memoryos.mcp.*' --tests io.memoryos.iam.group.PostgresIamAuthorizationTest --tests io.memoryos.ModulithArchitectureTest` | Passed: `McpPersistenceIntegrationTest` (4) validates the new columns under Hibernate `validate`; `PostgresIamAuthorizationTest` (10) now asserts `MODELS_MANAGE` and `MCP_MANAGE` authorize globally |
| OAuth protocol | `gradlew :core:test --tests io.memoryos.mcp.McpOAuthProtocolTest` (JDK `HttpServer` stub) | 8 tests passed:<br>• discovery from the `WWW-Authenticate` challenge, preferring RFC 8414 path insertion<br>• fallback to root protected-resource metadata and OIDC path appending<br>• refusal on resource mismatch, missing `S256` or issuer mismatch<br>• redirects not followed<br>• DCR request shape and issued client<br>• code exchange with PKCE, `resource` and form-encoded basic authentication<br>• `invalid_grant` becomes `MCP_AUTHORIZATION_REQUIRED` without upstream text<br>• authorization URL keeps the endpoint query and never lets extra parameters override `state`; challenge parsing |
| Server rules | `McpServerRulesTest` | 7 tests passed, including no `Authorization` header template on OAuth servers |
| End-to-end administrator OAuth | `ChatSessionApiIntegrationTest.mcpOAuthDiscoversRegistersConnectsRefreshesAndDisconnects` (stub authorization server; fixture MCP server publishing RFC 9728 metadata) | Passed:<br>• create `OAUTH`/`AUTO_DISCOVERY` server (`AWAITING_AUTH`)<br>• discovery review<br>• DCR client with sealed secret<br>• issuer mismatch and missing required `iss` rejected<br>• code exchange carries `resource` and a verifier matching the `S256` challenge<br>• stale pending authorization rejected after the revision changed<br>• sealed token payload<br>• tool refresh refreshes the 30-second token first<br>• `invalid_grant` gives 409 `MCP_AUTHORIZATION_REQUIRED`, `REAUTH_REQUIRED` and `AWAITING_AUTH`<br>• disconnect revokes once<br>• client metadata document 404 for a loopback HTTP redirect origin |
| Session-bound start and callback | `gradlew :api:test --tests io.memoryos.api.mcp.McpOAuthCallbackTest` | 4 tests passed:<br>• start requires the actor's session<br>• state and verifier bound to it, only the challenge in the URL<br>• callback completes once with the stored verifier and `iss`<br>• issuer mismatch, conflict, generic failure, `access_denied` and duplicate `iss` map to outcome codes without upstream detail<br>• unknown state, another actor or no session never complete |
| OpenAPI and web client | `MEMORYOS_OPENAPI_WRITE=true` `OpenApiContractTest`; `pnpm generate:api`; `tsc -b --noEmit` | Passed with six new `/api/mcp/servers/{serverId}/oauth/...` paths; the diff removes no operation; typecheck clean |
| Full API suite | `gradlew :api:test --no-daemon --max-workers=1` (after `34a60490`) | Passed: 22 suites, 171 tests, 0 failures |
| Known-provider clients and review fixes | `gradlew :core:test --tests 'io.memoryos.mcp.McpOAuth*' :api:test --tests 'io.memoryos.api.chat.ChatSessionApiIntegrationTest.mcp*' --tests 'io.memoryos.api.mcp.*'` | Passed (`McpOAuthPropertiesTest` 2, `McpOAuthProtocolTest` 8, MCP integration 2, `McpOAuthCallbackTest` 4):<br>• `KNOWN_PROVIDER` refuses discovery<br>• two administrator-entered `client_secret_post` clients, sealed and redacted; duplicate label 409<br>• `KEEP` preserves the secret; `NONE` with a stored secret rejected<br>• authorization carries the organization's client, scopes and `access_type`; the token request sends the secret in the form, not a Basic header<br>• deleting the client removes its connection and returns the server to `AWAITING_AUTH`<br>• the stored issuer is the metadata `issuer`, not a trailing-slash protected-resource value<br>• redirect URI accepts only HTTPS or loopback HTTP on the exact callback path |

Spring Session JDBC owns the browser session, so MockMvc cannot supply a session-bound actor. The session path is covered at controller level (`McpOAuthCallbackTest`), as MEM-60 does with `GoogleDriveOAuthTest`. The integration test calls the same service methods against real persistence and HTTP.

Not run for 2b: `gradlew clean check`, the full web `pnpm check` and a live authorization server. Whether Google accepts the `resource` parameter remains a live-probe item. Also not run: the full web `pnpm check` and live Google Drive MCP (deferred by the owner). JetBrains MCP was unavailable, so no IDE-inspection claim is made.

## Phase 3 — 2026-09-16

| Check | Command | Result |
| --- | --- | --- |
| User connections end to end | `gradlew :api:test --tests 'io.memoryos.api.chat.ChatSessionApiIntegrationTest.mcp*'` | `mcpUserConnectionsAreGroupScopedProbedAndIsolatedPerUser` passed:<br>• a Group-restricted per-User server is listed for a member as `NOT_CONNECTED` and is absent, and 404, for a non-member<br>• a rejected API key gives 409 `MCP_AUTHORIZATION_REQUIRED` and stores nothing<br>• an accepted key is sealed (`v1:`) and never echoed<br>• tool refresh of a per-User server uses the refreshing administrator's own credential<br>• two Users hold separate credentials; disconnecting one leaves the other<br>• without their own credential the administrator gets 409 `MCP_CREDENTIAL_REQUIRED` and the server status is unchanged |
| End-to-end User OAuth | `ChatSessionApiIntegrationTest.mcpOAuthDiscoversRegistersConnectsRefreshesAndDisconnects` (stub authorization server) | Passed:<br>• a per-User `KNOWN_PROVIDER` server lists for a member with the client label only, never the issuer or endpoints<br>• the pending authorization carries the User's own id and originating chat<br>• the administrator cannot complete a User's authorization (`MCP_CONFLICT`)<br>• the User's own token is stored while the server status stays `CREATED`<br>• the administrator's refresh there fails with `MCP_AUTHORIZATION_REQUIRED` and does not change the status<br>• a refresh that loses a concurrent race returns the winner's token and the credential stays `ACTIVE`<br>• Group membership withdrawn while an authorization is pending makes the completion `MCP_NOT_FOUND` and hides the server<br>• the User's disconnect revokes at the authorization server and removes only their own credential |
| Callback ownership and return | `gradlew :api:test --tests io.memoryos.api.mcp.McpOAuthCallbackTest` | 5 tests passed, including a connecting User returned to `/chat/{session}` with the outcome code; a callback without pending state lands on `/`, which every User may open |
| Return addresses | `McpReturnPathTest` | 2 tests passed: only `/`, `/chat/{id}`, `/projects/{id}` and `/admin/mcp` are accepted; absolute URLs, `//host`, backslashes, queries and fragments are refused |
| Core MCP suites and module boundaries | `gradlew :core:test --tests 'io.memoryos.mcp.*' --tests io.memoryos.ModulithArchitectureTest --tests io.memoryos.CoreDependencyRulesTest` | 35 tests passed; the new `mcp` JDBC access repository keeps the module boundary |
| Full API suite | `gradlew :api:test --no-daemon --max-workers=1` (after `fc202859`) | Passed: 23 suites, 175 tests, 0 failures |
| OpenAPI and web client | `MEMORYOS_OPENAPI_WRITE=true` `OpenApiContractTest`; `pnpm generate:api`; `tsc -b --noEmit` | Passed with four new `/api/mcp/connections...` paths; the diff is 360 added lines and removes no operation; typecheck clean |

Not run for Phase 3: `gradlew clean check`, the full web `pnpm check`, and a live authorization server.

## Phase 4 (backend) — 2026-09-16

| Check | Command | Result |
| --- | --- | --- |
| Model-facing tool surface | `gradlew :core:test --tests io.memoryos.chat.tools.McpToolsTest` | 6 tests passed:<br>• each tool carries its snapshotted JSON Schema and an untrusted-data warning; a non-read-only tool says so first<br>• every failure category returns a category sentence with no upstream body, URL or status code — the reference appends `Original error: {e}` instead<br>• only an authorization failure tells the User to reconnect<br>• the per-turn call limit refuses further calls<br>• one result is capped against the remaining context and unparseable arguments never reach the server<br>• servers that are selected but unusable are named with the reason |
| Module boundary | `gradlew :core:test --tests io.memoryos.ModulithArchitectureTest --tests io.memoryos.CoreDependencyRulesTest` | Passed with `chat` now allowed to depend on `mcp`; `mcp` stays closed and Chat sees only `McpTurnService`/`McpTurnTools` |
| Turn lifecycle | `gradlew :core:test --tests io.memoryos.chat.ChatTurnServiceTest` | 8 tests passed after the command, setup and cleanup changes |
| OpenAPI and web client | `MEMORYOS_OPENAPI_WRITE=true` `OpenApiContractTest`; `pnpm generate:api`; `tsc -b --noEmit` | Passed; `mcpServerIds` added to send, edit and regenerate (21 added lines), no operation removed, typecheck clean |

| End-to-end turn | `gradlew :api:test --tests 'io.memoryos.api.chat.ChatSessionApiIntegrationTest.mcp*'` (fixture MCP server, mocked provider) | 4 tests passed; `mcpToolsRunInATurnAndUnusableServersBecomeAConnectAction` covers:<br>• the model calls `mcp_<slug>_search_files` and the arguments arrive at the server unchanged<br>• the result reaches the next request while the API key and header secret never appear in any prompt<br>• the tool whose model-facing name exceeds the limit is never offered<br>• a Group-restricted server is not offered to a non-member and is never called, and the turn still answers<br>• Stop mid-turn cancels it and no late tool call reaches the server |
| Full API suite | `gradlew :api:test --no-daemon --max-workers=1` (after `b1c6ecf2`) | Passed: 23 suites, 176 tests, 0 failures |
| Full core suite | `gradlew :core:test` | 555 tests passed across 85 suites; the run ended with an `OutOfMemoryError` in `ObjectWriteLifecycleIntegrationTest` (object storage, unrelated to MCP), the known host memory limit rather than a regression |

Not run for Phase 4: `gradlew clean check` and the composer UI (Phase 5). The per-turn defaults (10 calls, 60s per call) are configuration and not yet measured against real Google Drive schemas.

## Phase 5 — 2026-09-16

Mobbin (2026-09-16, web) for this phase: [Replit](https://mobbin.com/screens/718e8393-29d9-474c-8a40-c798cc577ea9) ships an "MCP Servers" table with name, description, connection status and Disconnect next to "+ Add MCP server"; [Relevance AI](https://mobbin.com/screens/428513ea-93de-4ed8-8d4e-ef1189f0cdc6) locks the authentication type after creation and defers OAuth to a Connect step; [Perplexity](https://mobbin.com/screens/5777f7b3-1fbb-4d4d-81fe-00e7c9d2b7e8) requires a risk acknowledgement before adding a custom connector and shows registration failures inline; [Cursor](https://mobbin.com/screens/4b0de194-b789-4520-945d-acfed0e8ea10) edits name, URL, headers and client credentials in one dialog; [Mistral](https://mobbin.com/screens/0e571bd8-628d-46c6-98ea-22e4967be84b) and [Claude](https://mobbin.com/screens/16cc5d91-2f00-4a92-9777-6dd57a63a6a4) both put connectors in a composer submenu. MemoryOS follows the acknowledgement, the submenu and the status-per-row table; it keeps the authentication type editable because changing it deletes the stored credentials rather than stranding them.

| Check | Command | Result |
| --- | --- | --- |
| Composer MCP submenu | `pnpm exec vitest run src/features/chat/chat-mcp-options.test.tsx` | 5 tests passed:<br>• a connected server is selectable for the turn<br>• an unconnected server offers Connect and no switch<br>• several accounts are listed only when the server has more than one, and nothing is started before the choice<br>• a server with no enabled tools cannot be selected<br>• an OAuth server with no client reads as waiting on an administrator |
| Admin OAuth applications | `pnpm exec vitest run src/features/mcp/mcp-oauth-clients.test.tsx` | 7 tests passed:<br>• a server with no application says nobody can connect<br>• discovery is offered only in `AUTO_DISCOVERY` mode<br>• a discovered issuer registers by DCR only once it is labelled, and the label reaches the request<br>• an issuer offering neither DCR nor a metadata document says so and sends the administrator to the manual form<br>• the administrator's own connect appears only on a shared-connection server<br>• connect starts the authorization and disconnect removes the shared connection<br>• a pasted application posts its secret in the token request rather than a Basic header |
| Web checks | `pnpm test:unit`; `pnpm lint`; `pnpm check:i18n`; `pnpm exec tsc -b --noEmit`; `pnpm format:check` | 55 files, 298 tests passed; lint, i18n audit, typecheck and formatting clean |
| Delivered screenshots | `pnpm test:e2e tests/e2e/mcp-administration.spec.ts` | 7 tests passed; 16 screenshots written to `D:/MemoryOS/output/mem112-mcp-{admin,tools,add,composer}-{light,dark}-{1280,390}.png`, following the MEM-108 naming. At 390px the four per-row actions wrap onto two lines and leave the destructive delete alone on its own row; an overflow menu is the fix and is not applied yet. |
| Chromium screenshots and self-review | `pnpm test:e2e tests/e2e/mcp-administration.spec.ts` | 3 tests passed against stubbed responses, and the captured screenshots were reviewed. The review found and fixed: the server description was never rendered although the form collects it; server rows carried no identifying mark; the authentication fieldset stacked three unlabelled option rows; those rows were plain buttons with no selected state for assistive technology; the OAuth callback URL was not shown although each organization must register it; and the tool list had no heading. |
| Route tree | `pnpm build` | `/admin/mcp` generated into `routeTree.gen.ts`; the OAuth callback target now exists |

Not run for Phase 5: `gradlew clean check`. The Chromium scenario stubs the API rather than running a fixture MCP server end to end. The MCP activity timeline still has no dedicated renderer, so an MCP tool step shows under the generic tool row.

## Library reuse review and its findings — 2026-09-16

| Check | Command | Result |
| --- | --- | --- |
| MCP tool-call metrics | `gradlew :core:test --tests io.memoryos.chat.tools.McpToolsTest` | 8 tests passed. `memoryos.chat.mcp.call` records one timer per outcome (`succeeded`, `tool_error`, `auth_required`, `timeout`, `invalid_arguments`, `unknown_tool`, `unavailable`, `call_limit`). A test pins the label set to exactly `{server, tool, outcome}` so no high-cardinality label is added later; `server` is the slug, never the administrator's free-form name. Spring AI does not instrument MCP client tool calls, so nothing is duplicated. |
| Root protected-resource document | `gradlew :core:test --tests io.memoryos.mcp.McpOAuthProtocolTest` | 9 tests passed. A new test pins that the root well-known document may name the origin, while the challenge and path documents must still name the server exactly. This fixed a real defect: the previous strict equality refused a conformant server that publishes its metadata at the root. |

| Failure paths inside a real turn | `gradlew :api:test --tests 'io.memoryos.api.chat.ChatSessionApiIntegrationTest.mcpToolFailures*'` | Passed. With a 2s per-call timeout, one turn each for the three paths that were previously covered only against mocks:<br>• the server answers with its own `isError` — the turn completes and the model receives the tool's text<br>• a call outlives the per-call timeout — the model is told the server did not answer in time, and no credential appears in any prompt<br>• the stored API key is replaced with a rejected one through the real sealing path — the model is told to reconnect, with no upstream status code |

The reuse review of `spring-ai-community/mcp-security` is recorded in [design.md](design.md#library-reuse-review-2026-09-16).

## Repository gate — 2026-09-16

| Module | Command | Result |
| --- | --- | --- |
| `connector`, `worker` | `gradlew :connector:check :worker:check` | Passed |
| `api` | `gradlew :api:check` | Passed: 23 suites, 177 tests, 0 failures |
| `core` (static and verification tasks) | `gradlew :core:check -x test` | Passed |
| `core` (tests, first half) | `gradlew :core:test --tests 'io.memoryos.{mcp,chat,iam}.*' --tests 'io.memoryos.Modulith*' --tests 'io.memoryos.Core*'` | Passed |
| `core` (tests, second half) | `gradlew :core:test --tests 'io.memoryos.{objectstorage,connector,document,ingestion,retrieval}.*'` | Passed: 34 suites, 284 tests, 0 failures |

| frontend | `pnpm check` | Passed in one run: generated-client stability, Playwright image, i18n audit, lint, format, typecheck, 55 files with 298 unit tests, and route-tree stability |

`gradlew clean check` in one process was not run and is not expected to complete on this machine. `:core:test`
runs the whole module in one JVM capped at `maxHeapSize = "1g"` (`core/build.gradle.kts:16`), and on a 14 GB host
it ends with `OutOfMemoryError` inside `ObjectWriteLifecycleIntegrationTest` after 558 of the module's tests have
passed with no failures. That test passes on its own (9 tests), so the cause is heap pressure from running the
module in one JVM, not a defect in it and not related to MCP. The build configuration was deliberately left
unchanged: it is shared, and the constraint is local. CI, which runs the modules on larger workers, remains the
authority for the single-command gate.
