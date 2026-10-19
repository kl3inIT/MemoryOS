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

- [ ] `McpSession.classify`: a 403 `insufficient_scope` carries the challenged scope.
- [ ] `McpOAuthService.requireScope` stores `requested_scope` in the sealed payload; `start` requests the union;
  issuing a token clears it.
- [ ] Chat turn, view host and tool refresh pass the scope on.
- [ ] Tests: classification, the union, clearing, a shared connection.

## 4. Google Drive and upstream discovery

- [ ] `RestGoogleDriveAccountClient`: `IDTokenValidator` and `AccessTokenHash` replace the claim checks.
- [ ] `OidcDiscoveryClient`: `OIDCProviderMetadata.parse` after the strict reader.
- [ ] `RestGoogleDriveAccountClientTest` and `OidcDiscoveryClientTest` pass unchanged.

## 5. Verification

- [ ] `clean check` on zm.
- [ ] Staging: reconnect Linear and Canva, connect Google Drive, sign in through an upstream provider.
- [ ] Staging: a step-up, against a test MCP server that answers `403 insufficient_scope`, or a provider that does.
- [ ] Spec `chat` (MCP authorization), test matrix `chat`, runbook `mcp-servers`.
- [ ] Roadmap row once merged.
