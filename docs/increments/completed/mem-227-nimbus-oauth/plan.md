# Implementation plan

Design: [design.md](design.md).

## 1. Sending and the strict reader

- [x] `core` declares `com.nimbusds:oauth2-oidc-sdk` 11.38.2 in `gradle/libs.versions.toml`.
- [x] `McpOAuthProtocol` sends Nimbus requests over `OutboundHttp` with `exchange`, and reads every JSON answer
  strictly before Nimbus parses it.
- [x] Tests on the wire: no redirect followed, an error body read, duplicate members, trailing content, an answer
  over 64 KiB and a string `expires_in` refused.

## 2. MCP client on Nimbus

- [x] The authorization-server metadata of the eleven catalog servers that answer unauthenticated discovery (all
  but Atlassian), recorded on 2026-10-19 under `src/test/resources/mcp/oauth-metadata/`, is read by
  `McpOAuthProtocolTest`. Token and registration answers need an account; their shapes, Canva's relative
  `registration_client_uri` and Linear's empty `scope` included, stay in the existing tests.
- [x] `McpOAuthProtocol`: registration, deregistration, authorization URL, code and refresh grants, revocation and
  authorization-server metadata through Nimbus; field limits checked on the kept values. Discovery of the protected
  resource, the challenge, CIMD and the provider deviations stay.
- [x] The existing tests pass. Two changed with the code: the constructor takes only the request timeout, and the
  PKCE verifier in the test is 43 characters, as RFC 7636 asks and Nimbus enforces. `io.memoryos.mcp.*` (core) and
  `io.memoryos.api.mcp.*`: 94 tests, 0 failures.

## 3. Step-up

- [x] `McpSession.classify`: a 403 `insufficient_scope` carries the challenged scope (`McpException.requiredScope`),
  empty when it names none or an unusable one.
- [x] `McpOAuthService.requireScope` stores `requested_scope` in the sealed payload of an active credential;
  `start` requests the configured, granted and requested scopes; issuing a token clears it, and a refresh keeps it.
- [x] `McpTurnTools` (tool calls and view requests) and the tool refresh hand the scope to their connection.
- [x] Tests: `McpScopeStepUpTest`; the union and the clearing in
  `ChatSessionApiIntegrationTest.mcpOAuthDiscoversRegistersConnectsRefreshesAndDisconnects`.

## 4. Google Drive and upstream discovery

- [x] `RestGoogleDriveAccountClientTest.acceptsOnlyAnIdentityTokenThatMeetsEveryRule`, written first and passing on
  the old checks: a valid token, an absent `at_hash` and several audiences with `azp` are accepted; a wrong issuer,
  audience, `azp`, nonce or `at_hash`, several audiences without `azp`, an unverified or absent email, an issue time
  in the future and an expired token are refused.
- [x] `RestGoogleDriveAccountClient`: `IDTokenValidator` and `AccessTokenValidator` replace the Spring decoder and the
  claim checks; `spring-security-oauth2-jose` leaves the module for `oauth2-oidc-sdk`.
- [x] `OidcDiscoveryClient` stays as it is (see the design).

## 5. Verification

- [x] `clean check`: repository CI on each pull request; the MCP and Google sets on zm.
- [x] Staging: Canva refreshed, disconnected and connected again; Linear called. Google Drive and upstream sign-in
  were not exercised (owner, 2026-10-19); see [verification](verification.md).
- [x] Staging: a step-up against a test MCP server that answers `403 insufficient_scope`.
- [x] Spec `chat` (MCP authorization), spec `connector`, test matrices `chat` and `connector`, runbook `mcp-servers`.
- [x] Roadmap row.
