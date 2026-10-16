# Implementation plan

Design: [design.md](design.md).

## 1. Tool metadata

- [ ] Migration (next version after `main`): `mcp_server_tool.app_resource_uri`, `app_only`.
- [ ] Sync reads `_meta.ui.resourceUri`, then `ui/resourceUri`, then `openai/outputTemplate` (only `ui://`), and
  `ui.visibility`. `McpToolDescriptor` carries both.
- [ ] `McpTurnTools` never offers an `app_only` tool to the model.

## 2. Result and step

- [ ] `McpToolResult` carries `structuredContent` and `_meta`. The model's text is unchanged.
- [ ] Migration: `chat_message_tool_apps` with 64 KB checks, cascading from `chat_message`.
- [ ] `ChatToolEvent` finished carries `app` for a tool with a view when the payload fits. The answer stores it. The
  message read returns it.

## 3. Host route

- [ ] `POST /api/chat/sessions/{sessionId}/messages/{messageId}/apps/{callId}` with the owner, row, server-access and
  credential checks.
- [ ] Methods: `mcp-apps/read-resource` (MIME check, 2 MB bound, CSP injection), `tools/call` (app visibility, policy,
  `approved`), `resources/read`, `resources/list`. Anything else answers `-32601`.
- [ ] Logging `mcp.app.<method>.<outcome>`. Refresh `openapi.yml` and the Hey API client.

## 4. Web

- [ ] `chat-activity.ts` maps `app` to `mcp.app` with input and result.
- [ ] Register `McpAppRenderer` with `McpAppsRemoteHost` on the route, plus `hostInfo`, a theme-driven `hostContext`,
  the `openLink` https check, the `ASK` confirmation dialog and the 640 px cap.
- [ ] `nginx.conf` `frame-src https://*.scf.auiusercontent.com`. Deployment test for the header.
- [ ] i18n en and vi for the confirmation dialog.

## 5. Verification

- [ ] Core tests:
  - Sync keeps the URI and visibility; a non-`ui://` URI is dropped.
  - An `app_only` tool is not offered to the model.
  - An oversized payload carries no `app`.
- [ ] API tests for the route:
  - Another person's session gives 404.
  - Another server's tool is refused.
  - `ASK` without `approved` is refused.
  - `OFF` is refused.
  - A non-HTML resource is refused.
  - The CSP is built from `_meta.ui.csp`.
- [ ] Web tests:
  - The step renders the frame when it has `mcp.app`.
  - `ASK` shows the dialog.
  - `openLink` refuses `javascript:`.
- [ ] Staging with Canva:
  - `create-design` shows the candidates and picking one works.
  - `get-design-thumbnails` shows images.
  - A reload renders the view again.
  - Disconnecting Canva leaves the plain step.
  - Light and dark themes both render.
- [ ] Spec `chat` and `mcp`, test matrices, ARCHITECTURE (CSP), runbook `mcp-servers`, roadmap.
