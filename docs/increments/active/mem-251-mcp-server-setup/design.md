# MCP server setup and connections

Linear: [MEM-251](https://linear.app/memory-os/issue/MEM-251). Plan: [plan.md](plan.md). Follow-ups:
[MEM-252](https://linear.app/memory-os/issue/MEM-252) (verified catalog) and
[MEM-253](https://linear.app/memory-os/issue/MEM-253) (composer Tools button, write-tool approval, then Gmail and
Calendar).

## Problem

The owner found MCP setup hard to use (2026-10-14). An audit of `/admin/mcp`, Settings › Connections and the
composer, against Onyx (`.tmp/onyx`), Dust (`.tmp/dust/front/lib`) and MateClaw (`.tmp/mateclaw`), found:

- **Settings the API accepts cannot be entered.** The server form copies `oauthScopes` and
  `oauthAdditionalParameters` from the saved server and always sends `headers: KEEP`. The
  [MCP server runbook](../../../runbooks/mcp-servers.md#one-oauth-application-per-organization) requires
  `access_type=offline` and `prompt=consent` for Google, without which a connection ends after about an hour; an
  API-key server cannot say which header carries its key.
- **The form asks everything at once and some of it is jargon.** Name, short code, URL, description, sign-in
  method, who signs in, how to obtain the OAuth settings, callback address, who may use it and a required
  acknowledgement, in one dialog. The short code is a model-facing tool prefix; "Tự nhập điểm cuối" shows no
  field because the endpoints are entered later in a separate dialog.
- **The chosen option is invisible.** The choices are toggle buttons whose "on" state is `bg-muted`, which matches
  the dialog background in the dark theme (the owner's screenshot shows no option selected).
- **The next step after saving is hidden.** An OAuth server is unusable until it has an OAuth application, and the
  applications are under "Xem công cụ". On a per-person server the administrator must also connect their own
  account before "Lấy công cụ" can list the tools; nothing says so, and the badge reads "Cần kết nối".
- **Destructive actions without confirmation.** Deleting an OAuth application (which removes the connections made
  with it) and ending the shared connection act on the first click.
- **Settings › Connections** uses a generic icon for every server, labels a reconnection "Kết nối", shows English
  source strings beside Vietnamese ones, and expands one button per OAuth application label.

The list itself was text-heavy (owner, 2026-10-14): a monogram, a status badge, the URL and a line of settings
on every card, with three buttons. ChatGPT's plugin directory, Mistral's connectors and Mintlify's integrations show
a brand mark, a name, one line and a single action, in two columns, with everything else on a details page.

Onyx adds a server in two steps (name, description, URL and access, then authentication with progressive fields and
a collapsed Advanced section), shows one action per card state ("Authenticate", "Reconnect") and has no
acknowledgement checkbox, only a warning line. Dust reads a server's identity and tools from its URL and models
shared versus personal connections explicitly. MateClaw shows the last error and a Test result on the card.

## Decision

The API, persistence and OAuth flows are unchanged; every MCP capability this increment needs already exists. The
change is in `web/` and in the copy.

### Adding and editing a server

One dialog, ordered by what an administrator decides:

1. **Address and name.** The URL comes first, then the name. The short code is derived from the name (letters and
   digits of its ASCII form, at most 16, `mcp` when nothing is left) and is shown, editable on creation, under
   Advanced with the tool-name prefix it produces.
2. **Sign-in:** OAuth, API key or none, as radio choices whose selected state is visible in both themes.
3. **Whose account:** "Mỗi người dùng tài khoản của mình" or "Dùng chung một tài khoản", with no explanatory
   paragraph; a shared API key is entered here.
4. **Who may use it:** the whole organization or chosen Groups.
5. **Advanced**, collapsed unless the server already has a value in it:
   - short code;
   - OAuth: discovery or a manually entered application ("Nhập ứng dụng OAuth thủ công", which says what it does),
     scopes (space-separated), additional authorization parameters as name/value rows, and the callback address;
     when the URL is a Google API host and the two Google parameters are missing, one button adds them;
   - headers as name/value rows. An API-key server places `{api_key}`; with no rows the server sends
     `Authorization: Bearer {api_key}`. Header values are write-only, so an existing server shows the saved header
     names and offers to replace or remove them, never to edit a value in place.

The description field moves under Advanced: the model never reads it and it is optional.

**The acknowledgement checkbox is removed.** MEM-112 followed Perplexity's required acknowledgement; Onyx and Dust
have none, and the page header already states that MCP servers are third parties. The warning stays as that line.

**The callback address stays derived from the browser origin.** The runbook requires `MEMORYOS_MCP_REDIRECT_URI`
to be the browser origin plus `/login/oauth2/code/mcp`, so the derived value is the configured one in a valid
deployment, and a missing configuration already fails the connection with a 503. Exposing the configured value
would add an endpoint for no case that works today.

### The list: a mark, a name, one line, one action

Servers are listed in two columns as ChatGPT lists its plugins. Each row shows the service's mark on its brand
colour (a bundled simple-icons mark matched by a word of the address or name, a monogram otherwise; a favicon would
tell the host the Tenant's server list), the name, and one line: what is missing when something is, in the warning
colour, otherwise the description or the enabled tool count. The row's only action is the next step, or a menu
(edit, fetch tools again, delete) when nothing is missing. Selecting the row opens a side sheet with the URL, the
status, whose account, who may use it, the OAuth applications, the tools and the delete action.

The next step is computed from the server, its OAuth applications and the administrator's own connection (the list
Settings › Connections already reads):

| State | Action |
| --- | --- |
| OAuth server without an application | **Thêm ứng dụng OAuth**: the sheet opens and discovery runs; if the authorization server accepts the client metadata document or registration, one button creates the application named after the server; otherwise the manual form opens with the discovered issuer and endpoints filled in, so only the client ID and secret are typed |
| Shared connection not made | **Kết nối tài khoản dùng chung** |
| Per-person server whose administrator has not connected | **Kết nối tài khoản của bạn**, returning to the page, because listing tools uses that credential |
| No tools listed yet, or the last listing failed | **Lấy công cụ** |
| Connected with tools | none; the menu |

Discovery's two registration buttons ("Tự đăng ký (DCR)", "Dùng tài liệu ứng dụng") become one: the metadata
document when available, otherwise registration, as Onyx's default discovery prefers CIMD and falls back to DCR.

Deleting an OAuth application and ending the shared connection ask for confirmation, stating what stops working.

### Settings › Connections

The page keeps its route and becomes "Ứng dụng đã kết nối" in both languages, in the same two-column list: the
mark, the name, the state or description, and one action: "Kết nối", "Kết nối lại" for a rejected credential (a
menu of the organizations' application labels when there are several), or a menu with "Ngắt kết nối" once
connected. All copy goes through the Vietnamese-source catalog with English translations.

## Not in this increment

- Reading a server's name and tools from its URL before saving (Dust), and auto-detecting the sign-in method:
  both need a new unauthenticated probe in `core`; the catalog (MEM-252) removes most of the typing first.
- Showing the connected account's email: the token response does not carry it for every provider; MEM-252 decides
  per catalog entry.
- Choosing the OAuth application from the person's organization instead of a label: there is no mapping from a
  member to an organization's application yet.
- The composer Tools button and tool approval (MEM-253).

## Verification

Component tests for the editor's payload (scopes, parameters, headers, slug derivation), the next-step rule and the
confirmations; the existing MCP administration browser suite updated to the new flow; screenshots of the dialog and
cards in both themes reviewed before asking the owner. On staging: add Google Drive MCP with the two Google
parameters from the form and confirm the connection survives past an hour.
