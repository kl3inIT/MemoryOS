# Implementation plan

Design: [design.md](design.md).

## 1. Tool metadata

- [x] V184: `mcp_server_tool.app_resource_uri`, `model_visible`, `app_visible`.
- [x] Sync reads `_meta.ui.resourceUri`, then `ui/resourceUri`, then `openai/outputTemplate` (only `ui://`, at most 1,024
  characters), and `ui.visibility`. `McpToolDescriptor` and `McpTurnTools.Binding` carry them.
- [x] `McpTools` never offers a tool hidden from the model.
- [x] MCP Java SDK 2.1.0; the client declares `io.modelcontextprotocol/ui` with `text/html;profile=mcp-app`.

## 2. Result and step

- [x] `McpToolResult` carries the whole `CallToolResult` as JSON. The model's text is unchanged.
- [x] V184: `chat_message_tool_apps` with 64 KB checks, cascading from `chat_message` and `mcp_server`.
- [x] `ChatToolEvent` finished carries `app` (`ChatToolApp`) for a completed call of a tool with a view when it fits. The
  answer stores it with its tool calls (at most 1 MB per answer). The history returns it as `apps`.

## 3. Host route

- [x] `POST /api/chat/sessions/{sessionId}/messages/{messageId}/apps/{callId}` (`ChatToolAppService`, `McpAppHost`)
  with the owner, row, server-access and credential checks.
- [x] Methods: `mcp-apps/read-resource` (MIME check, 2 MB bound, `_meta.ui` from the content or its listing),
  `tools/call` (app visibility, policy, `approved`), `resources/read`, `resources/list`. Anything else answers `400`.
- [x] Logging `mcp.app.<request>.failed`. Refresh `openapi.yml` and the Hey API client.

## 4. Web

- [x] `@assistant-ui/react` 0.15.29 (enforces the view's CSP), `@assistant-ui/ai-sdk` 0.0.13 with the history patch
  carried over, `@assistant-ui/react-streamdown` 0.3.20.
- [x] `chat-activity.ts` follows a completed step with its view's part (`<callId>:app`, `mcp.app` through
  `callProviderMetadata`), live and from history; the view stands outside the activity group.
- [x] `ChatMcpApps` registers `McpAppRenderer` with `McpAppsRemoteHost` and a `fetch` to the route, `hostInfo`, a
  theme- and locale-driven `hostContext`, inline only, no `sendMessage`, the `ASK` confirmation and the 640 px cap.
- [x] `nginx.conf` `frame-src https://*.scf.auiusercontent.com`, with its test.

## 5. Verification

- [x] Core: `McpClientsIntegrationTest` (views, visibility, metadata, whole result), `McpServerRulesTest`,
  `McpToolsTest`, `McpAppHostTest`, `ChatPersistenceIntegrationTest`; `ModulithArchitectureTest`,
  `CoreDependencyRulesTest`.
- [ ] API: `ChatSessionApiIntegrationTest.mcpAppsShowAViewInTheAnswerAndServeItsRequestsToTheOwnerOnly`,
  `OpenApiContractTest`.
- [ ] Web: `chat-mcp-apps.test.tsx`, `nginx-config.test.mjs`, `pnpm --dir web check`.
- [ ] Staging with Canva:
  - `create-design` shows the candidates and picking one works.
  - `get-design-thumbnails` shows images.
  - A reload renders the view again.
  - Disconnecting Canva leaves no view.
  - Light and dark themes both render.
- [x] Spec `chat`, test matrix `chat`, runbook `mcp-servers`.
- [ ] Roadmap once merged.
