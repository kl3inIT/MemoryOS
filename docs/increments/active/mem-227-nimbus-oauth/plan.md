# Implementation plan

Design: [design.md](design.md).

## 1. Sender and strict reader

- [ ] `core` declares `com.nimbusds:oauth2-oidc-sdk` 11.38.2 in `gradle/libs.versions.toml`.
- [ ] `shared.OAuthHttp`: Nimbus `HTTPRequestSender` over `OutboundHttp` with `exchange`; strict JSON check before
  Nimbus parses; 64 KiB bound and the request deadline.
- [ ] `OAuthHttpTest` on the wire: no redirect followed, deadline, bound, an error body read, duplicate members and
  trailing content refused.

## 2. MCP client on Nimbus

- [ ] Record today's provider responses as fixtures in `McpOAuthProtocolTest` (Linear, Canva, Notion, Google) and
  run them against the current code first.
- [ ] `McpOAuthProtocol`: registration, deregistration, authorization URL, code and refresh grants, revocation and
  authorization server metadata through Nimbus; field limits checked on the kept values. Discovery of the protected
  resource, the challenge, CIMD and the provider deviations stay.
- [ ] The existing `McpOAuthProtocolTest`, `McpOAuthCallbackTest` and `McpOAuthPropertiesTest` pass unchanged.

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
