# MEM-114 UI references

Mobbin screens chosen on 2026-10-01. They show intent, not a specification: layout and flow are adapted to MemoryOS
components, and deviations are recorded in the design.

## Where the pages live

Decided on 2026-10-01 from a second Mobbin pass: each MCP direction gets its own page.

- [Notion, Settings › Connections](https://mobbin.com/screens/cf5e63d0-c13a-417f-aa0d-29c77c0fa8b0) keeps
  "Connections" (Notion using other apps) apart from "Notion MCP" (AI tools using Notion), with a card linking across.
- [Descript, Settings](https://mobbin.com/screens/0ce6b1ce-f761-43b2-bfe1-82865899cdfc) has "Connected apps" and
  "Descript MCP" as separate items.
- MemoryOS: *Máy chủ MCP* and *Connections* stay Chat's use of outside tools. *MemoryOS MCP* is a new item in both
  Administration (`/admin/mcp-endpoint`) and Settings (`/settings/mcp`).

## Administration › MemoryOS MCP

- [Retool, Retool AI](https://mobbin.com/screens/06c937f6-383e-4ae6-b96b-9ae38c23f7f3) and
  [Ditto, AI](https://mobbin.com/screens/75402f8b-2239-4cde-bfea-e752f7aa4354): one bordered group of switch rows, a
  title and one line each.
- [Mintlify, Admin › MCP](https://mobbin.com/screens/7b23e95a-7215-4db8-8d84-58ac039ab68d): label on the left,
  control on the right; client credentials as their own row.
  - MemoryOS keeps the switch, the URL and ChatGPT's client ID and masked secret. Redirects and other clients live in
    Keycloak.

## Settings › MemoryOS MCP: connecting a client

- [Magnific, Settings › MCP](https://mobbin.com/screens/6c752779-eef2-4420-a780-18d568dce6db): a tab per client,
  three numbered steps, the URL with a copy button. This is the shape MemoryOS follows, with Claude and ChatGPT tabs.
- [Descript, Descript MCP](https://mobbin.com/screens/0ce6b1ce-f761-43b2-bfe1-82865899cdfc): a row per client and
  the "MCP URL" field with copy beneath.
- [Customer.io, Personal settings › MCP](https://mobbin.com/screens/b2434437-426e-4845-9c4a-132ca940cf07): a
  per-client connection guide. It is too long for MemoryOS; keep two or three steps.

## Settings › MemoryOS MCP: authorized apps

- [Railway, Account › Apps](https://mobbin.com/screens/a1d35780-61e0-4535-a16f-33bc280570de): one row per app, "7
  permissions · Authorized 6 days ago" under the name, one action. MemoryOS rows read "Đọc tri thức · Cấp ngày …".
- [Discord, Authorized Apps](https://mobbin.com/screens/b19ff1c2-1060-491d-bca0-e6f2102916fb): the revoke action is
  destructive in colour. MemoryOS confirms it in a dialog instead of expanding the row.
- [GitLab, User settings › Applications](https://mobbin.com/screens/85906da2-07a4-42d8-841d-93a3d2f26657): an
  authorized applications table with name, authorized at, scope and Revoke.
- [Dropbox Dash, connected apps](https://mobbin.com/screens/669b28e8-79b8-4417-ba73-59c35b7da51c): an expandable row
  with connected date and permissions, plus Disconnect.

## Keycloak consent page

- The starting point: [Keycloak 26.8's stock consent page](spike-consent.png), captured in the spike for Claude Code's
  CIMD client. It shows the scope's Vietnamese consent text and the client's hostname. It also lists the realm default
  scopes (roles, profile, email).
- Delivered in pull request 1, from the end-to-end run on Keycloak 26.8. No template is overridden: only theme
  messages, CSS, the realm's locales, the scopes' `gui.order`, and ChatGPT's `logoUri`.
  - [Claude, Vietnamese](consent-claude-vi.png), [ChatGPT, Vietnamese](consent-chatgpt-vi.png),
    [ChatGPT at 390 px](consent-chatgpt-vi-mobile.png), [sign-in, English](login-en.png).
  - **Icon.** As Slack, Zoom and Google do (Mobbin: Cursor in Slack, Descript in Zoom, Perplexity in Google), the
    requesting app's icon sits above the title when the client has one. ChatGPT's comes from the web app's
    `/provider-logos/openai.svg`. Claude's metadata documents publish no `logo_uri`, so Claude shows its name only.
  - **Order.** The main permission comes first and staying connected second. The client's own line is last, as a
    muted note: Keycloak's fixed English "The client's hostname is claude.ai", or "Ứng dụng từ chatgpt.com".
  - **Language switcher.** It sits small under the card, as Google places it. The page follows the browser's
    language, Vietnamese first.
  - **Not done, because it needs a template override.** The two-icon "app ⇄ MemoryOS" header (GitHub) and a
    "signed in as" line (Google, X).
- [Craft, OAuth consent](https://mobbin.com/screens/66cc3ac5-605a-4f26-a14e-a07291d9f6e9). This is the target shape for
  a read-only grant:
  - the client name;
  - two plain permission lines;
  - one primary action;
  - "signed in as" with switch account.

## Client side, for the acceptance runbook

- [Claude, Settings › Connectors](https://mobbin.com/screens/e6ade293-ed67-4901-8d3c-4196c31b842e): "Add custom
  connector".
- [Claude, connector detail with tool permissions](https://mobbin.com/screens/2eb6c9a1-64f2-46a8-9cf8-ba9b1897ba86):
  read-only tools are grouped by their annotations.
