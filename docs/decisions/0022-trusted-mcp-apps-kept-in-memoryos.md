# 22. Trusted MCP apps are kept in MemoryOS and Keycloak follows

Date: 2026-10-02

## Status

Accepted; implementation started 2026-10-02 in
[MemoryOS MCP endpoint governance](../increments/active/mcp-endpoint-governance/design.md) (MEM-207).

## Context

Claude, ChatGPT and other MCP clients identify themselves to Keycloak by a client ID metadata document. Keycloak
admits a document only from the hosts listed in the realm's client policy `memoryos-mcp-cimd`, and the realm script
wrote that list. An administrator who wants to stop ChatGPT, or to trust an agent of their own, had no way to do it
from MemoryOS, and a change made in the Keycloak console would be undone by the next script run.

Keycloak cannot hold the list alone: it has no notion of a Tenant, of who changed what, or of a revision, and its
client policy has no audit trail of its own. MemoryOS cannot hold it alone either, since Keycloak decides at sign-in.

## Decision

- **MemoryOS owns the list.** The trusted apps are rows of the Tenant (`mcp_trusted_apps`) changed through the
  administration page under `MCP_MANAGE`, with revisions and an audit entry per change. Claude and ChatGPT are built
  in, with their hosts in code, and can be switched off but not removed.
- **Keycloak's policy is a projection.** Every change writes the policy's host lists inside the database transaction,
  so a change Keycloak refuses is not saved. The API reconciles the policy with the list when it starts and every
  10 minutes, which repairs a change Keycloak took whose transaction then failed. Only the operating Tenant's list
  reaches Keycloak, because the policy is realm-wide.
- **Distrust revokes.** Switching an app off or removing it deletes the Keycloak clients built from documents on hosts
  no longer trusted, and with them their grants.
- **A dedicated account writes it.** `memoryos-mcp-admin` holds only `manage-realm` and `manage-clients`. The user
  provisioner keeps `manage-users` alone, so no single key both manages people and changes what may sign in.
- **The realm script seeds, then defers.** It writes the built-in hosts on the first run and keeps the live lists on
  every later run.

## Consequences

- A deployment without the account's secret shows the list read-only; the endpoint still works with the hosts
  Keycloak has.
- `manage-realm` is broader than client policies need, because Keycloak has no narrower role for them. The account's
  secret is a server secret file like the others.
- The static `memoryos-chatgpt` client is no longer needed and is removed with this decision.
- A change in the Keycloak console to `memoryos-mcp-cimd`'s host lists lasts until the next reconciliation.
