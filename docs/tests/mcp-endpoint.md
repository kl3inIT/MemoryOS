# MemoryOS MCP endpoint verification matrix

Contract: [MemoryOS MCP endpoint](../specs/mcp-endpoint.md). `McpEndpointIntegrationTest` is
`api/src/test/java/io/memoryos/api/mcp/endpoint/McpEndpointIntegrationTest.java`; it runs the API against PostgreSQL
with signed tokens from a stand-in issuer and Search and Keycloak's admin API mocked.

| Requirement | Durable verification |
| --- | --- |
| An endpoint not configured, or switched off, hides `/mcp` and its metadata (404) | `McpEndpointIntegrationTest.anOffSwitchHidesTheEndpointAndItsMetadata` |
| An anonymous call is told where to sign in: 401 with `resource_metadata` and `scope`; the metadata names the resource, issuer and scope | `McpEndpointIntegrationTest.anAnonymousCallIsToldWhereToSignIn` |
| Only a token issued for the endpoint with `knowledge:read` gets in: the browser API's token gets 401, one without the scope 403, and the endpoint's token is refused on `/api/**` | `McpEndpointIntegrationTest.onlyATokenIssuedForTheEndpointWithItsScopeGetsIn` |
| A foreign browser `Origin` is refused | `McpEndpointIntegrationTest.aForeignBrowserOriginIsRefused` |
| The endpoint lists exactly `search`, `search_with_filters` and `fetch`, all read-only; the instructions ask for claims linked to their source | `McpEndpointIntegrationTest.theEndpointPublishesOnlyItsThreeReadOnlyTools`, `.theInstructionsAskForClaimsLinkedToTheirSource` |
| `search` returns citable evidence with a MemoryOS link for an upload | `McpEndpointIntegrationTest.searchReturnsCitableEvidenceWithAMemoryOsLinkForAnUpload` |
| `search_with_filters` offers each filter with its allowed values and narrows the search to what was asked; an unknown set is answered with the usable ones, an unreadable period with the format, an unknown source before Search runs | `McpEndpointIntegrationTest.searchWithFiltersOffersEachFilterWithItsAllowedValues`, `.searchWithFiltersNarrowsTheSearchToWhatThePersonAsked`, `.anUnknownDocumentSetIsAnsweredWithTheSetsThePersonCanUse`, `.aPeriodTheToolCannotReadIsRefusedWithTheFormatToUse`, `.anUnknownSourceIsRefusedWithoutReachingSearch` |
| A refused call says what to change; a failure reaches the app without its cause | `McpEndpointIntegrationTest.aRefusedCallTellsTheClientWhatToChange`, `.aFailureReachesTheClientWithoutItsCause` |
| A client that accepts only JSON is served; an oversized body gets 413; a 2026-07-28 probe gets the 400 that makes it fall back to `initialize` | `McpEndpointIntegrationTest.aClientThatAcceptsOnlyJsonIsServedRatherThanRefused`, `.anOversizedBodyIsRefused`, `.chatGptsModernProbeIsAnsweredSoItFallsBackToInitialize` |
| Tool calls are limited per member and app, listing is free; a refusal reaches the activity log and the counter by scope and app | `McpEndpointIntegrationTest.toolCallsAreLimitedPerCallerAndListingIsFree` |
| An administrator turns the endpoint on and off at a revision, each change audited; a member is given the URL and the apps that are on | `McpEndpointIntegrationTest.anAdministratorTurnsTheEndpointOnAndOffAndEachChangeIsAudited`, `.aMemberIsGivenTheUrlAndTheTrustedAppsWhileTheEndpointAnswers` |
| Trusted apps: adding, switching and removing an app write Keycloak each time and are audited; without the account the list is read-only; without an endpoint Keycloak is left alone | `McpEndpointIntegrationTest.anAdministratorTrustsAnAppOfTheirOwnAndKeycloakFollowsEachChange`; `core/src/test/java/io/memoryos/mcp/McpTrustedAppServiceTest.java` |
| The Keycloak adapter writes only its own hosts and keeps every other setting, writes nothing unchanged, removes only the clients of hosts no longer trusted | `core/src/test/java/io/memoryos/iam/keycloak/KeycloakMcpClientPolicyTest.java` |
| Every tool call reaches the activity log without its query, the insights count it, and the Worker removes calls older than 90 days | `McpEndpointIntegrationTest.everyToolCallReachesTheActivityLogWithoutItsQueryAndTheInsightsCountIt`; `worker/src/test/java/io/memoryos/worker/ControlPlaneIntegrationTest.java` registers the task |
| A member lists their grants and revokes one by its URL client ID; the Keycloak adapter reads and deletes consents of metadata-document clients | `McpEndpointIntegrationTest.aMemberListsTheirGrantsAndRevokesOneByItsUrlClientId`; `core/src/test/java/io/memoryos/iam/keycloak/KeycloakMcpClientGrantsTest.java` |
| The realm script: the endpoint is optional and the browser origin followed by `/mcp`; its scope carries the audience; Claude and ChatGPT are admitted by their documents with PKCE; a rerun keeps the trusted hosts; the admin account holds exactly its two roles; grants lapse after 30 days unused and end after 180; the old ChatGPT client is removed once; the image is built with `cimd` | `infrastructure/keycloak/test_realm_mcp_endpoint.py` |
| nginx forwards the two endpoint paths and the client metadata document to the API | `web/scripts/nginx-config.test.mjs` |
| The administration page: switch at its revision, trusted apps (confirming before distrust, domain validation, read-only without the account), activity filters in the address, insights; the member page guides only through the apps that are on and revokes a grant | `web/src/features/mcp/mcp-endpoint-admin-page.test.tsx`, `mcp-endpoint-settings-page.test.tsx`; `web/tests/e2e/mcp-endpoint.spec.ts` (captures in both themes at 1280 and 390 px, reviewed) |
| HTTP paths and schemas are in the checked-in contract | `api/src/test/java/io/memoryos/api/OpenApiContractTest.java` |

Live evidence: [MEM-114 verification](../increments/active/mem-114-public-mcp-server/verification.md).
