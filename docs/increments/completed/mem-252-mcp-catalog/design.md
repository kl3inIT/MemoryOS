# MCP catalog of popular apps

Linear: [MEM-252](https://linear.app/memory-os/issue/MEM-252). Plan: [plan.md](plan.md). Builds on
[MEM-251](../mem-251-mcp-server-setup/design.md); write-tool approval is
[MEM-253](https://linear.app/memory-os/issue/MEM-253).

## Problem

Adding an MCP server means knowing its address, sign-in, scopes and, for Google, two authorization parameters.
ChatGPT, Onyx (Craft › Apps: Slack, Google Calendar, Drive, Gmail, Linear, GitHub, HubSpot, Notion), Dust
(`remote_servers.ts`, about twenty) and MateClaw (a recommended grid) all offer known servers ready to add. The
owner asked for a fixed catalog of the common apps, with Gmail included and every scope requested, the choice of
what the model may do being made per tool.

## Decision

### The catalog

A fixed list in `web/src/features/mcp/mcp-catalog.ts`, chosen with the owner on 2026-10-14. Each entry is the
values the add dialog starts from; a server added from it is an ordinary server, so the API, persistence and
OAuth flows are unchanged. Every address was probed that day with an unauthenticated `initialize` (an OAuth
challenge from each), and the authorization server's metadata decides how its OAuth application comes about:

| App | Address | OAuth application |
| --- | --- | --- |
| Google Drive | `https://drivemcp.googleapis.com/mcp/v1` | By hand, one Google `Internal` app per organization |
| Gmail | `https://gmailmcp.googleapis.com/mcp/v1` | By hand, as Drive |
| Linear | `https://mcp.linear.app/mcp` | Automatic (registration and metadata document) |
| Notion | `https://mcp.notion.com/mcp` | Automatic (both) |
| Atlassian | `https://mcp.atlassian.com/v1/mcp` | Automatic (registration) |
| GitHub | `https://api.githubcopilot.com/mcp/` | By hand (GitHub OAuth app) |
| Slack | `https://mcp.slack.com/mcp` | By hand (Slack app) |
| Figma | `https://mcp.figma.com/mcp` | Automatic (registration) |
| Canva | `https://mcp.canva.com/mcp` | Automatic (both) |
| Miro | `https://mcp.miro.com/` | Automatic (registration) |
| Asana | `https://mcp.asana.com/v2/mcp` | By hand |
| HubSpot | `https://mcp.hubspot.com/` | By hand |

Every entry is per person and asks for the scopes its provider offers, so turning on a tool later needs no new
sign-in; Google's entries carry `access_type=offline` and `prompt=consent`. Gmail is limited to Workspace accounts
of the organization's own `Internal` app: personal Gmail needs Google's verification and a CASA assessment.

Left out for now: Google Calendar (no official remote server found), Sentry, Intercom, Stripe, Zapier and Box
(reachable, little asked for), Supabase (no OAuth metadata), Lark and Microsoft 365 (no official remote server).

Cloudflare in front of Notion, Atlassian, Sentry, Supabase and Intercom refuses Python's user agent with 403; from
the staging host the JDK client's `Java-http-client` agent gets the normal 401 challenge, so MemoryOS is not
affected.

### On the page

`/admin/mcp` shows **Đã thêm** and **Ứng dụng phổ biến**, both in the two-column list of MEM-251. A catalog row is
the brand mark, the name, one line of what it is for and a `+`; selecting it opens the add dialog titled "Thêm
Gmail" with everything filled in, so the administrator chooses only who may use it. An entry already added (by
host) leaves the catalog. Saving goes to the new server's page, where its next step continues: an automatic
OAuth application is one button, and a manual one opens the form with the discovered endpoints, a link to the
provider's console and the callback address to register. "Thêm máy chủ" stays for any other address.

### Tools that may change data start off

Listing a server's tools used to leave every new tool off. A newly listed tool now starts on when the server
declares it read-only (`readOnlyHint`) and its model-facing name fits, and off otherwise; a tool already known
keeps its switch. Reading works as soon as a server is connected, and a tool that sends, edits or deletes reaches
the model only once an administrator turns it on, until MEM-253 asks the person before each such call.

## Verification

Component tests for the catalog (hosts, Google parameters, short codes) and the dialog's preset; the MCP
administration browser suite; the API integration test for the new default. Live: Linear on staging connected by
a person (pending), then each automatic entry as accounts allow; each verified entry is recorded in the
[MCP server runbook](../../../runbooks/mcp-servers.md).
