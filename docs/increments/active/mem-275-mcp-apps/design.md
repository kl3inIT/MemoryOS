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

A new migration (the next version after `main`'s newest, checked before merge) adds two columns to `mcp_server_tool`:

- `app_resource_uri VARCHAR(1024) NULL`, accepting only `ui://` URIs. Anything else is dropped at sync.
- `app_only BOOLEAN NOT NULL DEFAULT false`, true when the visibility lists `app` but not `model`.

`McpToolDescriptor` gains both fields. A tool with `app_only` is never offered to the model (`McpTurnTools`), because
SEP-1865 requires the host to hide it. It stays in the admin tool list, where the policy still governs it.

### 2. Carry the view on the tool step

When an MCP call finishes, `McpSession.callTool` returns the result's `structuredContent` and `_meta` beside the text.
The model sees exactly what it sees today.

If the called tool has an `app_resource_uri`, the finished `ChatToolEvent` carries an `app` object on the step:

- `serverId` and `resourceUri`.
- `input`: the call's arguments.
- `output`: `{content, structuredContent, _meta}`, at most 64 KB. Over that, the step carries no `app` and shows as
  today.

The web maps it to `ToolCallMessagePart.mcp.app` with `input` and `result`, which is what `McpAppRenderer` reads.

**Persistence.** The same object goes into a new table, `chat_message_tool_apps (message_id, call_id, server_id,
resource_uri, input, output)`. It has the 64 KB check per field and cascades from `chat_message`. A reloaded answer
renders the view again, as Claude and ChatGPT do.

This is not the MEM-287 transcript. That table replays results to the model and deliberately drops MCP output. This
table is only read back to the session owner's own screen, and every render passes the access check in section 3. A
disconnected server, a revoked credential or a removed tool therefore shows the plain step, not the stored view.

### 3. One host route, owner-scoped

The route is `POST /api/chat/sessions/{sessionId}/messages/{messageId}/apps/{callId}`. It takes assistant-ui's
`McpAppsRemoteHost` body `{method, params}`.

Each request first checks four things:

1. The actor owns the session.
2. The message holds that call's app row.
3. The actor can still use the row's server (tenant, groups, server enabled).
4. The actor has a usable credential under the server's auth performer.

A failure answers `404` or the existing `MCP_*` problem, and the frame shows its error state.

| Method | Behaviour |
| --- | --- |
| `mcp-apps/read-resource` | Reads the row's `resourceUri`, or another `ui://` URI of the same server. It accepts only `text/html;profile=mcp-app` (or `text/html+skybridge` for an Apps SDK template). It returns `{uri, mimeType, html, meta}`, with the CSP from section 4 injected into `html`. The resource is at most 2 MB. |
| `tools/call` | Runs a tool of the row's server only. The tool must be visible to the app (`visibility` contains `app`). Policy `ALWAYS` runs it. `ASK` runs it only with `approved: true` from the web's confirmation (section 5). `OFF`, unknown and model-only tools are refused. The call is logged and counted like a model call. |
| `resources/read` | Reads a resource of the row's server. |
| `resources/list` | Lists the resources of the row's server. |

Every other method answers `-32601`. `serverId` from the frame is ignored: the row decides the server. A view therefore
cannot reach another server even when it lies about `serverId`.

### 4. Sandbox and CSP

The view runs in assistant-ui's SafeContentFrame. That gives each view its own origin, derived from a hash of the
content, under `scf.auiusercontent.com`. The content is posted in the browser and nothing is uploaded to assistant-ui.
The domain is on the Public Suffix List, so views cannot share cookies or storage with each other or with MemoryOS. This
is the same isolation Claude gets from `claudemcpcontent.com` and ChatGPT from `oaiusercontent.com`.

Self-hosting the shim (`unsafeShimDomain`) needs a domain MemoryOS registers on the Public Suffix List. That is out of
this increment. An air-gapped deployment simply sees the plain step, because the frame fails to load.

**Web CSP.** `web/nginx.conf` adds `frame-src https://*.scf.auiusercontent.com`. `frame-ancestors 'none'` stays.

**View CSP.** assistant-ui does not apply `_meta.ui.csp`, and SEP-1865 makes the host responsible for it. The route
therefore prepends a `<meta http-equiv="Content-Security-Policy">` to the HTML:

- `default-src 'none'`
- `script-src 'self' 'unsafe-inline'` plus `resourceDomains`
- `style-src 'self' 'unsafe-inline'` plus `resourceDomains`
- `img-src`, `font-src` and `media-src`: `'self' data: blob:` plus `resourceDomains`
- `connect-src` plus `connectDomains`
- `frame-src` plus `frameDomains`, or `'none'`
- `base-uri` plus `baseUriDomains`, or `'none'`

A domain that is not `https://` is dropped. This matches what `basic-host` applies as the frame's CSP header.

### 5. What the view can ask of the host

| Bridge request | v1 |
| --- | --- |
| `ui/initialize` | `hostInfo` `{name: "MemoryOS", version}`. `hostContext` carries the theme (light or dark from the app theme), `locale`, `displayMode: "inline"` and `availableDisplayModes: ["inline"]`. Context changes are sent when the theme changes. |
| Tool input and result | Sent from the stored `input` and `output`. |
| `tools/call` | Goes through the route. For an `ASK` tool, the web first shows the same `AlertDialog` as tool approval ("Canva muốn chạy create-design", buttons Cho phép and Từ chối). A refusal answers the view with an error. This follows Claude's prompts for view tool calls. |
| `resources/read`, `resources/list` | Go through the route. |
| `openLink` | Only `https:` links, opened with `window.open(url, "_blank", "noopener,noreferrer")`. Any other scheme is refused. |
| `requestDisplayMode` | Always answers `inline`. |
| `sendMessage`, `updateModelContext` | Not handled. The library answers `-32601`, as in Goose today. |
| Size | Auto height, capped at 640 px. |

The view replaces the step's raw JSON result. The step header (server mark, tool title, status) stays above it.

### 6. Other surfaces

Lark, Zalo and other channels keep text results. They have no frame to render into, and the model's text is unchanged.

## Not in this increment

- Fullscreen and picture-in-picture display modes.
- `sendMessage` and `updateModelContext` from the view.
- A self-hosted shim domain.
- Views for built-in MemoryOS tools.
- MCP-UI's older `ui://` raw-HTML and remote-DOM results that do not follow SEP-1865.
