# MEM-114 UI references

Mobbin screens chosen on 2026-10-01. They show intent, not a specification: layout and flow are adapted to MemoryOS
components, and deviations are recorded in the design.

## Administration: the endpoint switch

- [Mintlify, Admin › MCP](https://mobbin.com/screens/7b23e95a-7215-4db8-8d84-58ac039ab68d).
  - A "Connect your site with AI apps" row with Claude, Cursor and Others tabs.
  - Allowed redirect domains and client credentials as separate rows.
  - MemoryOS keeps only the switch and the URL. Redirects and clients live in Keycloak.

## Settings › Kết nối: connecting a client

- [Magnific, Settings › MCP](https://mobbin.com/screens/6c752779-eef2-4420-a780-18d568dce6db): a tab per client,
  three numbered steps, the URL with a copy button.
- [Customer.io, Personal settings › MCP](https://mobbin.com/screens/b2434437-426e-4845-9c4a-132ca940cf07): a
  per-client connection guide. It is too long for MemoryOS; keep two or three steps.

## Settings › Kết nối: grants

- [GitLab, User settings › Applications](https://mobbin.com/screens/85906da2-07a4-42d8-841d-93a3d2f26657): an
  authorized applications table with name, authorized at, scope and Revoke.
- [Dropbox Dash, connected apps](https://mobbin.com/screens/669b28e8-79b8-4417-ba73-59c35b7da51c): an expandable row
  with connected date and permissions, plus Disconnect.

## Keycloak consent page

- The starting point: [Keycloak 26.8's stock consent page](spike-consent.png), captured in the spike for Claude Code's
  CIMD client. It shows the scope's Vietnamese consent text and the client's hostname. It also lists the realm default
  scopes (roles, profile, email).
- After pull request 1: [the `memoryos` theme's consent page](e2e-consent.png) from the end-to-end run. The default
  scopes are gone, leaving Offline Access, the client's hostname and the scope text.
  - Still to fix in pull request 3: the item order puts Keycloak's hostname line between the two permissions.
  - Also in pull request 3: "Offline Access" has no Vietnamese text.
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
