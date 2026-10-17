# MEM-275: MCP Apps in chat answers

Linear: [MEM-275](https://linear.app/memoryos/issue/MEM-275). Plan: [plan.md](plan.md).

## Problem

Some MCP servers return an interactive view with a tool result, not only text. The
[MCP Apps extension](https://github.com/modelcontextprotocol/ext-apps) (SEP-1865, `io.modelcontextprotocol/ui`, stable
since 2026-01-26) defines how this works:

- A tool names an HTML resource in `_meta.ui.resourceUri` (`ui://…`, MIME `text/html;profile=mcp-app`).
- The host renders that resource in a sandboxed frame and feeds it the call's input and result.
- The frame talks back over JSON-RPC `postMessage`. It can call tools, read resources and open links.

Canva is connected on staging (MEM-275 step 1). Six of its 48 tools carry a view: `generate-image`, `create-design`,
`get-design-candidates`, `get-design-thumbnails`, `separate-image-layers` and `track-event-lifecycle`. The views let
the person pick a design candidate or browse thumbnails. MemoryOS drops all of it today:

- `McpSession.callTool` keeps only text. When a result has no text, it serialises `structuredContent`.
- `McpToolDescriptor` and `mcp_server_tool` keep no `_meta`.
- The answer shows a tool step whose result is a JSON string the person cannot use.

## References

| Product | MCP Apps | What it does |
| --- | --- | --- |
| Claude (claude.ai, Desktop) | Full | Renders the view inline in the answer, each server's views on its own sandbox domain. The view can call tools, but the same permission prompts apply as for the model. It also supports sending a message and updating model context. |
| ChatGPT | Partial | Renders Apps SDK views (`openai/outputTemplate`) and MCP Apps on `*.oaiusercontent.com`. Per Alpic's host matrix, it does not support calling tools from the view. |
| VS Code (Copilot Chat) | Yes | Renders inline in the chat. |
| Goose 1.19+ | Experimental | Renders inline. The view calls tools over MCP but cannot message the chat. |
| `ext-apps` `examples/basic-host` | Reference host | A sandbox proxy origin. The resource's `_meta.ui.csp` is applied as the frame's CSP. |
| assistant-ui `McpAppRenderer` | Library | A host bridge (`ui/initialize`, tool input and result notifications, `tools/call`, `resources/read`, `openLink`, …). Each view runs on a hashed origin under `scf.auiusercontent.com`, an assistant-ui domain on the Public Suffix List. |
| Onyx, Glean, LibreChat, Open WebUI, LobeChat | None found | Text results only. |

MemoryOS follows Claude for the experience and the security model, and Goose for staging the scope. View tool calls go
through the same policy as the model's. Messages from the view to the chat come later.

## Decision

### 1. Keep each tool's view metadata at sync

Tool sync already reads `tools/list`. It now also keeps three fields from each tool's `_meta`:

- `ui.resourceUri`. If that is absent, it falls back to the legacy `ui/resourceUri` or the Apps SDK's
  `openai/outputTemplate`. Canva declares both forms.
- `ui.visibility`. The default is `["model", "app"]`.

Migration V186 adds three columns to `mcp_server_tool`:

- `app_resource_uri VARCHAR(1024) NULL`, accepting only `ui://` URIs. Anything else is dropped at sync.
- `model_visible` and `app_visible`, both `BOOLEAN NOT NULL DEFAULT true`, from the visibility. A visibility that names
  neither side is read as the default, both.

`McpToolDescriptor` and `McpTurnTools.Binding` carry the three fields. A tool not `model_visible` is never offered to
the model (`McpTools`), because SEP-1865 requires the host to hide it. A tool not `app_visible` cannot be called from a
view. Both stay in the admin tool list, where the policy still governs them.

The client also says it renders views: SEP-1865 asks it to declare `capabilities.extensions["io.modelcontextprotocol/ui"]`
with the MIME types it accepts, and a server may offer views only to a client that does. The MCP Java SDK takes client
extensions from 2.1.0, so the SDK moves from 2.0.1 to 2.1.0.

### 2. Carry the view on the tool step

When an MCP call finishes, `McpSession.call` returns the whole `CallToolResult` as JSON beside the text. The model
sees exactly what it sees today.

If the called tool has an `app_resource_uri` and the call did not fail, the finished `ChatToolEvent` carries an `app`
object (`ChatToolApp`):

- `serverId` and `resourceUri`.
- `input`: the call's arguments.
- `output`: the whole result, at most 64 KB. Over that, the step carries no `app` and shows as today.

The web keeps the step where it is, in the collapsed activity, and adds a second tool part right after it, with the
call ID plus `:app`. That part carries the view's input and output as its arguments and result, and
`callProviderMetadata.mcp.app`, which the AI SDK converter turns into `ToolCallMessagePart.mcp.app` for
`McpAppRenderer`. It is left out of the activity group, so the view stands in the answer as Claude shows it. The step's
own input stays the progress the step line reads.

**Persistence.** The same object goes into a new table, `chat_message_tool_apps (message_id, call_id, server_id,
resource_uri, input, output)`. It has the 64 KB check per field and cascades from `chat_message`. A reloaded answer
renders the view again, as Claude and ChatGPT do.

This is not the MEM-287 transcript. That table replays results to the model and deliberately drops MCP output. This
table is only read back to the session owner's own screen, and every render passes the access check in section 3. A
disconnected server, a revoked credential or a removed tool therefore shows an empty frame in place of the view. A
deleted server deletes its rows (`server_id` cascades from `mcp_server`). One answer keeps at most 1 MB of views.

### 3. One host route, owner-scoped

The route is `POST /api/chat/sessions/{sessionId}/messages/{messageId}/apps/{callId}`. It takes assistant-ui's
`McpAppsRemoteHost` body `{method, params}`, plus `approved` (section 5). assistant-ui gives the host only the part's
`serverId`, so the web sets it to the session, message, call and step tool, and a custom `fetch` posts to the route they
name.

Each request first checks four things:

1. The actor owns the session.
2. The message holds that call's app row.
3. The actor can still use the row's server (tenant, groups, server enabled).
4. The actor has a usable credential under the server's auth performer.

A failure answers `404` or the existing `MCP_*` problem, and the frame shows its error state.

| Method | Behaviour |
| --- | --- |
| `mcp-apps/read-resource` | Reads a `ui://` view of the row's server. It accepts only `text/html;profile=mcp-app` (or `text/html+skybridge` for an Apps SDK template). It returns `{uri, mimeType, html, meta}`, where `meta` is the content's `_meta.ui`, else that of the resource's `resources/list` entry. The resource is at most 2 MB. |
| `tools/call` | Runs a tool of the row's server only. The tool must be visible to the app. Policy `ALWAYS` runs it. `ASK` answers `409 MCP_APP_APPROVAL_REQUIRED` unless `approved` is true (section 5). `OFF`, unknown and model-only tools answer `403 MCP_APP_TOOL_NOT_ALLOWED`. A failure is logged as `mcp.app.<request>.failed`. |
| `resources/read` | Reads a resource of the row's server. |
| `resources/list` | Lists the resources of the row's server. |

Every other method answers `400`. The row decides the server: the route never takes a server from the browser, so a
view cannot reach another server.

### 4. Sandbox and CSP

The view runs in assistant-ui's SafeContentFrame. That gives each view its own origin, derived from a hash of the
content, under `scf.auiusercontent.com`. The content is posted in the browser and nothing is uploaded to assistant-ui.
The domain is on the Public Suffix List, so views cannot share cookies or storage with each other or with MemoryOS. This
is the same isolation Claude gets from `claudemcpcontent.com` and ChatGPT from `oaiusercontent.com`.

Self-hosting the shim (`unsafeShimDomain`) needs a domain MemoryOS registers on the Public Suffix List. That is out of
this increment. An air-gapped deployment simply sees the plain step, because the frame fails to load.

**Web CSP.** `web/nginx.conf` adds `frame-src https://*.scf.auiusercontent.com`. `frame-ancestors 'none'` stays.

**View CSP.** SEP-1865 makes the host enforce `_meta.ui.csp`. `@assistant-ui/react` 0.15.29 does it: before any view
code runs, it writes a `Content-Security-Policy` meta built from `connectDomains`, `resourceDomains`, `frameDomains` and
`baseUriDomains`, with `connect-src 'none'` and `frame-src 'none'` where a declaration is missing. The route therefore
passes `meta` through unchanged. The design first had the route inject this policy, because 0.15.22 did not; the
upgrade made that unnecessary.

**Dependencies.** `@assistant-ui/react` moves from 0.15.22 to 0.15.29, with `@assistant-ui/ai-sdk` 0.0.13 and
`@assistant-ui/react-streamdown` 0.3.20 that go with it. 0.0.13 still rebuilds the history-restore callback each render,
so the existing patch is carried to 0.0.13.

### 5. What the view can ask of the host

| Bridge request | v1 |
| --- | --- |
| `ui/initialize` | `hostInfo` `{name: "MemoryOS", version}`. `hostContext` carries the theme (light or dark from the app theme), `locale`, `displayMode: "inline"` and `availableDisplayModes: ["inline"]`. Context changes are sent when the theme changes. |
| Tool input and result | Sent from the stored `input` and `output`. |
| `tools/call` | Goes through the route. When it answers `MCP_APP_APPROVAL_REQUIRED`, the web shows a confirmation with the approval card's title ("Cho phép {{tool}} trên {{server}}?") and the call's arguments, then sends the call again with `approved`. Cancelling answers the view with an error. This follows Claude's prompts for view tool calls. |
| `resources/read`, `resources/list` | Go through the route. |
| `openLink` | assistant-ui's default: `http(s)` links only, opened with `window.open(url, "_blank", "noopener,noreferrer")`. |
| `requestDisplayMode` | Always answers `inline`. |
| `sendMessage`, `updateModelContext` | Not offered, as in Goose today. The library would append a view's message to the thread by default, so `sendMessage` answers an error; `updateModelContext` has no handler and answers `-32601`. |
| Size | Auto height, capped at 640 px. |

The step line (server mark, tool title, status) stays in the activity; the view follows it in the answer.

### 6. Other surfaces

Lark, Zalo and other channels keep text results. They have no frame to render into, and the model's text is unchanged.

## Not in this increment

- Fullscreen and picture-in-picture display modes.
- `sendMessage` and `updateModelContext` from the view.
- A self-hosted shim domain.
- Views for built-in MemoryOS tools.
- MCP-UI's older `ui://` raw-HTML and remote-DOM results that do not follow SEP-1865.
