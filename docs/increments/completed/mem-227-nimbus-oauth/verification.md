# MEM-227 verification

Staging ran `3681de33` (#778, #779 and #780) from 2026-10-19 05:46Z.

## Automated

- Every pull request passed the repository CI (`clean check` of each module, and the web gate).
- On zm in Docker (JDK 25):
  - #778: `io.memoryos.mcp.*`, `io.memoryos.api.mcp.*`, the MCP cases of `ChatSessionApiIntegrationTest`,
    `ModulithArchitectureTest` and `CoreDependencyRulesTest`, 94 tests. `McpOAuthProtocolTest` reads the recorded
    metadata of the eleven catalog providers that publish it.
  - #779: the same set with `McpScopeStepUpTest`, 106 tests.
  - #780: `:memoryos-integration-google:check` and `GoogleDriveOAuthTest`, 64 tests.
- `RestGoogleDriveAccountClientTest.acceptsOnlyAnIdentityTokenThatMeetsEveryRule` was written before the change and
  passed on the old checks. On the new code it found the two `azp` rules that Nimbus does not enforce; MemoryOS
  keeps them.

## Staging

**Step-up, against a test server.** The test server ran on zm in the `memoryos-internal` network. It was one process
acting as MCP server and authorization server; its `export_file` needs `files:write`. The steps ran through the web
session of an administrator:

1. A shared-connection server asking for `files:read` was created, discovered and registered (DCR,
   `client_secret_basic`).
2. The authorization asked for `files:read` with `resource` and S256, and the callback carried `iss`. Then the code
   exchange ran, and so did two refreshes; their answers omit `scope`.
3. In Chat, `export_file` was answered `403 insufficient_scope`. The step showed "Step-up fixture từ chối kết nối dùng
   chung. Quản trị viên cần kết nối lại."
4. Connecting again asked for `files:read files:write`. After that, `export_file` returned `report.pdf exported`.
5. After the new grant, the next authorization still asks for both, because they are now granted.

The API logged only the expected `chat.mcp_tool.failed` (`AUTHORIZATION_REQUIRED`), and no
`mcp.oauth.scope.not_recorded`. The server and the test process were removed afterwards.

**Real providers.**

- Canva:
  - its access token had expired the day before, and the next Chat call refreshed it through Nimbus;
  - the owner then disconnected and connected again, and the new credential was `ACTIVE` at 05:57:09Z;
  - a Chat call on it listed the three latest designs.
- Linear: a Chat call listed three issues assigned to the owner.
- Discovery of Canva and Linear returned their endpoints and scopes.

**Not exercised on staging** (owner, 2026-10-19: "canva ok là đc rồi"):

- Connecting Google Drive. Its ID-token checks are covered by the tests above.
- Signing in through an upstream provider: `OidcDiscoveryClient` did not change.
