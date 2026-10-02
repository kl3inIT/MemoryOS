# MemoryOS MCP endpoint governance: trusted apps, grant lifetime, rate limits, activity (MEM-207, MEM-209)

Status: in progress, started 2026-10-02 at the owner's request, in one pull request. Linear:
[MEM-209](https://linear.app/memory-os/issue/MEM-209) and the first part of
[MEM-207](https://linear.app/memory-os/issue/MEM-207). Builds on the endpoint of
[MEM-114](../mem-114-public-mcp-server/design.md).

The endpoint shipped with Claude and ChatGPT trusted by the realm script, 30-day idle grants without a maximum, a
loose rate limit and no record of its calls beyond logs. Glean's MCP administration is the reference, as the owner
asked: an administrator chooses the hosts whose apps may connect, grants end after 180 days, each tool call is logged
without its query or documents, and the same log gives the insights. Onyx's MCP server has none of these.

## 1. Trusted apps (MEM-207, first part)

**What an administrator sees.** *Quản trị › MemoryOS MCP › Cài đặt* lists the trusted apps: Claude and ChatGPT built
in, each with a switch, then the Tenant's own apps with a switch and a remove button. *Thêm ứng dụng* asks for a name,
the domains of the app's client ID (the URL of its metadata document, 1 to 10, `*.` for subdomains) and any other
domain its document lists (callback, logo; loopback allowed). Switching an app off or removing it asks first, because
it revokes every connection made through it. A member's *Cài đặt › MemoryOS MCP* gives the steps only for the apps
that are on, with one generic guide for any app of the Tenant's own.

**Where the list lives.** MemoryOS owns it (`mcp_trusted_apps`, V140); Keycloak's client policy `memoryos-mcp-cimd`
is its projection ([ADR 0020](../../../decisions/0020-trusted-mcp-apps-kept-in-memoryos.md)).

- Built-in apps keep their hosts in code (`McpTrustedApp.Preset`); a built-in row holds only its switch and revision.
  Claude: client ID hosts `claude.ai`, `claude.com`; document hosts those plus `localhost`, `127.0.0.1`. ChatGPT:
  `chatgpt.com`; document hosts `chatgpt.com`, `persistent.oaistatic.com`.
- Each change writes Keycloak inside the database transaction, so a Keycloak failure rolls the change back
  (`MCP_CLIENT_POLICY_UNAVAILABLE`). A change Keycloak took whose transaction then failed is repaired by the
  reconciliation, which runs when the API starts and every 10 minutes.
- Keycloak's policy is realm-wide, so only the operating Tenant's list reaches it.
- Switching an app off or removing it deletes the Keycloak clients whose client ID URL is on a host no longer
  trusted; their offline sessions, and so the grants, go with them.
- Revisions fence every change (`MCP_TRUSTED_APP_CHANGED`); at most 50 apps of the Tenant's own.
- Each change is audited as `mcp_trusted_app.change` with the change (`ADD`, `ENABLE`, `DISABLE`, `REMOVE`) and the
  app's client ID domains.

**The account that writes Keycloak.** `memoryos-mcp-admin`, a confidential client with only a service account and
the realm-management roles `manage-realm` and `manage-clients`, which client policies and client removal need. The
user provisioner already holds `manage-users`; keeping the two apart means neither key can do both. Its secret is the
file `mcp_admin_client_secret`; without it the list is read-only and the page says so.

**The static ChatGPT client is gone.** `memoryos-chatgpt`, its secret file and the administration page's ChatGPT
fields existed for a ChatGPT that is given a client ID by hand. ChatGPT connects through its metadata document since
2026-10-02, so the realm script deletes the client once and the deployment no longer needs the file.

**Not in this part.** Dynamic Client Registration limited to named redirect hosts, for Gemini, is the second part of
MEM-207 and waits for an account that can accept it. An administrator revoking one member's connections is not in
scope until the owner decides it.

## 2. Grant lifetime (MEM-209)

Offline sessions keep the 30-day idle limit and gain a 180-day maximum (`offlineSessionMaxLifespanEnabled`,
`offlineSessionMaxLifespan` 15552000), as Glean bounds its MCP grants. Within 180 days the app refreshes as before;
after it, the member connects again.

## 3. Rate limits (MEM-209)

Each `tools/call` spends one token from the caller's bucket (person and app, 300 a minute) and one from the
endpoint's (3,000 a minute); discovery and listing are free. Spring Framework 7 has only `@ConcurrencyLimit`, a bound
on concurrent calls, not a rate over time, so the token buckets stay on bucket4j. A refusal answers 429 with
`Retry-After`, counts in `memoryos.mcp.endpoint.rate_limited` by scope and app, and reaches the activity log.

## 4. Activity log and insights (MEM-209)

**What is recorded.** One row per tool call in `mcp_endpoint_calls`: when, who, the client ID and its kind (Claude,
ChatGPT, other), the tool and how it ended (`SUCCESS`, `REFUSED`, `FAILED`, `RATE_LIMITED`). Never the query, a
filter or a document id, as Glean logs its MCP servers. The row is written in its own transaction and a failure to
write it is logged and swallowed, so the log never fails a call.

**Who reads it.** Holders of `MCP_MANAGE`, the people who switch the endpoint and trust its apps. `AUDIT_READ` does
not reach it: the audit trail records administrators' changes, and a member reading what they may read is not one.

**Pages.** *Hoạt động*: the calls newest first, filtered by period (24 hours to 90 days), app, tool and outcome, with
cursor pages; the filters live in the address. *Thống kê*: for the last 7 or 30 UTC days, calls, people, failures and
refusals for rate, the calls of each day, and the calls and people of each app and tool.

**Retention.** The worker removes rows older than 90 days every hour, in batches of 5,000.

**Metrics.** `memoryos.mcp.endpoint.call` gains the `client` tag (`claude`, `chatgpt`, `other`, never the client ID)
and a histogram; the Chat & AI dashboard has a *MemoryOS MCP endpoint* row: calls by tool and outcome, calls by app,
p95 by tool, and refusals for rate.

## Excluded

- Gemini and Dynamic Client Registration (MEM-207, second part).
- An administrator revoking one member's connections.
- Web and Keycloak session lifetimes, awaiting the owner's numbers.
