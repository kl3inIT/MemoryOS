# MEM-227: OAuth messages in core through Nimbus, and scope step-up for MCP

Linear: [MEM-227](https://linear.app/memory-os/issue/MEM-227). Plan: [plan.md](plan.md).

## Problem

`core` still builds and parses OAuth messages by hand in three places:

- **MCP OAuth client.** `mcp.McpOAuthProtocol` (519 lines) on its own JDK `HttpClient`, driven by `McpOAuthService`.
  It covers RFC 9728, RFC 8414 and OIDC discovery, RFC 7591/7592 registration, PKCE, RFC 8707 `resource`, the code
  and refresh grants, RFC 7009 revocation and the token endpoint's client authentication.
- **Google Drive consent.** `integration.google.RestGoogleDriveAccountClient` exchanges the code with Google's
  library and checks the ID token's signature with Nimbus. It then checks audience, nonce, `azp`, issue time and
  `at_hash` itself.
- **Upstream sign-in discovery.** `iam.keycloak.OidcDiscoveryClient` reads OIDC metadata over `OutboundHttp` and
  checks its fields itself.

[ADR 0025](../../../decisions/0025-outbound-http-through-the-highest-level-client.md) puts a maintained library
before protocol code written here. MEM-223 left the MCP client "unchanged; a library only after a spike"
([MEM-223 design](../../completed/mem-223-shared-restclient/design.md#mcp-oauth-and-a-library)). This increment is
that spike's outcome.

It also fills one gap in the MCP client itself:

- **No step-up today.** A server answering `403` with `WWW-Authenticate: Bearer error="insufficient_scope"` fails the
  call like any other authorization error. Reconnecting then asks for the same scopes again, and the call fails again.
- **What the spec asks.** MCP authorization 2025-11-25 asks the client to step up (SHOULD). The 2026-07-28 revision
  requires the new request to carry the union of the scopes already requested and the challenged ones.

## References

| Product | MCP OAuth client | Step-up on `insufficient_scope` |
| --- | --- | --- |
| Onyx | `OAuthClientProvider` of the MCP Python SDK (`backend/onyx/server/features/mcp/oauth.py`); Onyx keeps its own token storage, SSRF check and client metadata document | The SDK's: on `403 insufficient_scope` it runs the authorization flow again with the granted scope kept (`src/mcp/client/auth/oauth2.py`, the `step_up` branch) |
| MCP TypeScript SDK | `client/auth.ts` (`discoverAuthorizationServerMetadata`, `registerClient`, `startAuthorization`, `exchangeAuthorization`) | Handled in `client/streamableHttp.ts` |
| MCP Java SDK 2.1 | None: hooks only (`McpSyncHttpClientRequestCustomizer`, `McpHttpClientTransportAuthorizationErrorHandler`). The maintainers point to Spring's `mcp-security` | `McpHttpClientTransportAuthorizationException` carries the status and headers of a 401 or 403 |

Java has no MCP SDK that does what the Python and TypeScript SDKs do. The candidates for the protocol layer,
compared on 2026-10-18 (MEM-227 on Linear holds the full table):

- **spring-ai-community `mcp-security` 0.1.14.** Ruled out. It ties tokens to the servlet request and keeps client
  registrations in memory with no tenant. It has no revocation, deregistration or RFC 9207. It drops PKCE for a
  client that has a secret (issue #107). Its discovery goes through Spring Security's static `RestTemplate`, which
  cannot be bounded.
- **Spring Security 7.1 OAuth2 Client.** Ruled out. It has no RFC 9728, no client-side registration and no
  revocation, and its discovery uses the same static `RestTemplate`.
- **Nimbus `oauth2-oidc-sdk` 11.38.2.** Chosen. It has RFC 8414 and OIDC metadata, RFC 7591/7592, PKCE, RFC 8707,
  the code and refresh grants, RFC 7009, the client authentication methods and ID token validation. It has no
  RFC 9728 and no client metadata documents. Every request can be sent through a sender we supply
  (`HTTPRequestSender`), which is what lets it keep ADR 0025. Connect2id releases one to three versions a month,
  and Spring Security already brings it to `api`.

## Decisions

### Nimbus builds and parses; MemoryOS sends

`core` declares `com.nimbusds:oauth2-oidc-sdk` 11.38.2 in the version catalog: the latest release (2026-07-21), and the version `api` already resolves through `spring-security-oauth2-client` 7.1.1. Spring Boot does not manage it, so the catalog pins it.

**Sending.** Nimbus builds each request (`toHTTPRequest()`), and `McpOAuthProtocol` sends it over an
`OutboundHttp` client with `exchange`. Nimbus's own sender, which follows redirects and reads without a bound, is
never used.

- `exchange` reads an OAuth error body (`invalid_grant`) within the bound.
- The four transport rules hold. `McpOAuthProtocol` stops using its own `HttpClient`, the last client MEM-223 left
  outside `OutboundHttp`.
- The bound stays 64 KiB, and the deadline is the existing request timeout. The connect timeout is `OutboundHttp`'s,
  5 seconds, which was also the old default.
- A redirect is an answer like any other, and discovery treats it as a failure, as
  `doesNotFollowRedirectsDuringDiscovery` asserts.
- The sending stays inside `McpOAuthProtocol` rather than a shared class: no second caller needs it
  ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)).

**The strict reader.** Nimbus parses JSON with json-smart, which accepts duplicate members (the last one wins) and
ignores trailing content. MemoryOS refuses both today. `McpOAuthProtocol` therefore reads every JSON answer once with
the existing strict mapper (`STRICT_DUPLICATE_DETECTION`, `FAIL_ON_TRAILING_TOKENS`) before Nimbus parses it.

- A body that fails is the same failure as now: a discovery, registration or token failure, depending on the call.
- Nimbus also accepts `expires_in` as a string. MemoryOS keeps a lifetime only when it is a number.

**Field limits.** The per-field limits (client ID 2,048, tokens 16,384, scope 4,096, `expires_in` at most 366 days)
move from the parser to the values MemoryOS keeps. They are checked after Nimbus parses and before anything is
stored, so the database bounds still hold.

### What stays MemoryOS code, and why

- **Protected-resource metadata (RFC 9728).** Nimbus has no class for it, so discovery keeps:
  - the `resource_metadata` and `scope` parameters of `WWW-Authenticate` (Nimbus's `BearerTokenError.parse` drops
    `resource_metadata`);
  - the MCP order of well-known URLs;
  - the check that the document names this resource.
- **The authorization server list and issuer.** The PKCE S256 requirement and the exact `issuer` match are
  MemoryOS's fail-closed rules on top of the parsed `AuthorizationServerMetadata`.
- **Client ID Metadata Documents.** The document is served by `McpOAuthService` today, and Nimbus has no CIMD
  client concept to replace it.
- **Provider deviations**, each covered by a test on the wire:
  - Canva answers registration with a relative `registration_client_uri`. It is resolved against the registration
    endpoint.
  - Linear answers `"scope": ""`. That means no scope, not a failure.
- **Endpoint policy, `canonicalResource`, and logging that never carries an upstream body.**

`McpOAuthProtocol` keeps its public methods and records, so `McpOAuthService`, the API and the persisted shape do
not change. Only the bodies of `register`, `authorizationUrl`, `exchangeCode`, `refresh`, `revoke`, `deregister` and
the authorization-server half of `discover` move to Nimbus.

### Step-up on `insufficient_scope`

- **Recognising it.** `McpSession.classify` already maps `McpHttpClientTransportAuthorizationException` to
  `MCP_AUTHORIZATION_REQUIRED`. For a `403` whose `WWW-Authenticate` carries `error="insufficient_scope"`, it keeps
  that code and records the challenged `scope` on the exception.
- **Remembering the scope.** The caller that knows the connection (the chat turn, the view host, the tool refresh)
  hands the scope to `McpOAuthService.requireScope(tenant, server, owner, scope)`. That adds it to the credential's
  sealed payload as `requested_scope`, as the payload already holds the granted `scope`. A connection shared by the
  administrator records it on the shared credential.
- **Asking again.** The next authorization for that connection requests the union of:
  - the server's configured scopes;
  - the scope granted last time;
  - `requested_scope`.

  This is the 2026-07-28 rule, and what the Python SDK does (`granted_scope` is read before the new flow).
  `requested_scope` is cleared when a token is issued.
- **The person's step.** The step fails with `AUTHORIZATION_REQUIRED`, and the chat already offers "Kết nối lại" for
  it (`chat-mcp-step.tsx`). No new screen and no new failure category.
- **No loop.** The flow starts only when the person presses the button. A server that challenges again after the new
  grant shows the same button again; nothing retries on its own.
- **No migration.** The scope lives inside the encrypted payload.

### Google Drive and upstream discovery

- **Google Drive.** `RestGoogleDriveAccountClient` keeps Google's library for the exchange, and its own bounded fetch
  of the key set. Nimbus `IDTokenValidator` takes over the OpenID Connect checks from the Spring decoder and the
  checks written here: signature, issuer, audience, `azp` when there are several audiences, expiry and nonce. Nimbus
  `AccessTokenValidator` checks `at_hash`.
  - MemoryOS keeps its own rules on top:
    - a verified email;
    - an issue time no more than 60 seconds ahead;
    - an authorized party when there are several audiences, and no `azp` naming another client even with one
      audience: Nimbus checks `azp` only with several audiences and does not require it then (the test caught both);
    - the email matching the Drive account.
  - `spring-security-oauth2-jose` leaves the module.
  - Before the change, a new test signed an ID token breaking one rule at a time. The code it replaced passed that
    test, and so does the new code.
- **Upstream sign-in discovery: left as it is (decided 2026-10-19).** `OidcDiscoveryClient` already sends over
  `OutboundHttp` and reads six string fields; there is no protocol code to hand over. Nimbus
  `OIDCProviderMetadata.parse` also requires fields that sign-in does not use, such as `subject_types_supported` and
  `id_token_signing_alg_values_supported`. An upstream provider that omits one would then be refused where it signs
  in today. The risk buys nothing.

## Outcome of the MCP part (2026-10-19)

`McpOAuthProtocol` did not get shorter: 558 lines against 519.

The research estimated 150 to 200 lines, counting only the protocol code Nimbus replaces. What MemoryOS keeps on top
accounts for the rest:
- the strict reader and the field limits;
- protected-resource discovery and the challenge;
- the provider deviations;
- about 35 import lines.

What changed is who writes the messages. Form and JSON encoding, the client authentication headers, PKCE, the
registration and revocation requests and the token response parsing now come from Nimbus. The transport moved onto
`OutboundHttp`, which MEM-223 had left aside. This matches the owner's rule of 2026-10-04 (ADR 0025 amendment): a
maintained library is chosen for quality and maintenance, not for size.

## Out of scope

- The MemoryOS MCP endpoint's authorization server (Keycloak, [ADR 0024](../../../decisions/0024-mcp-endpoint-in-process-with-the-members-access.md)).
- Encrypting secrets at rest: MEM-294.
- Changing which registration method is tried first. The 2026-07-28 revision deprecates DCR; today the
  administrator chooses between DCR and the metadata document, and that stays.

## Risks

- **Nimbus is stricter or looser than today on a field a provider gets wrong.** Each provider seen in production
  (Linear, Canva, Notion, Google) gets a recorded response in `McpOAuthProtocolTest`. A response today's code accepts
  must still be accepted, and one it refuses must still be refused.
- **A Nimbus upgrade changes parsing.** The same tests guard the upgrade. Dependabot raises the catalog version; it is kept equal to the one Spring Security brings to `api`.
