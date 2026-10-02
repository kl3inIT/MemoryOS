# 24. MemoryOS serves MCP in process, with the member's own access

Date: 2026-10-03

## Status

Accepted by the owner on 2026-10-01; implemented in [MEM-114](../increments/active/mem-114-public-mcp-server/design.md)
(PRs #419, #421, #425, #430, #431, #433, #436, #438, #440 and #442) and extended by
[ADR 0022](0022-trusted-mcp-apps-kept-in-memoryos.md). Recorded after the fact, when the endpoint ran on staging with
Claude and ChatGPT.

## Context

Members wanted to ask Claude and ChatGPT about the organization's Documents without copying them out. Both reach an
organization's knowledge through a remote MCP server with OAuth. OrgMemory, the earlier prototype, ran a separate
gateway that exchanged the inbound token (RFC 8693) to call its API; Onyx, the main reference, authenticates its MCP
server with personal access tokens. A second deployable would add a token exchange, a second network boundary and a
second release to operate; personal access tokens are not designed yet (MEM-124).

## Decision

- **One deployable.** `/mcp` lives in `api` and calls the `retrieval` services the Search page uses, in process,
  behind a security chain of its own that accepts only tokens whose audience is the endpoint URL and whose scope is
  `knowledge:read`. No token goes downstream, so there is no exchange.
- **The member's own access, re-read on every call.** The token's `(iss, sub)` resolves the member; nothing is
  provisioned, and Search applies the member's current authority, so revoking access in MemoryOS takes effect on the
  next call.
- **OAuth through Keycloak with client ID metadata documents.** Keycloak 26.8 with `cimd` admits an app by the URL of
  its metadata document; dynamic client registration stays closed. Grants are offline sessions the member can revoke.
- **Read-only tools only:** `search`, `search_with_filters`, `fetch`. No resources, prompts or writes.
- **Off by default.** One switch per Tenant, under `MCP_MANAGE`, audited; a deployment without an endpoint URL cannot
  turn it on. A tool call is a member reading what they may read, so it is logged in the endpoint's activity log and
  counted, not audited.

## Consequences

- The internet-facing process holds database credentials and all of `core`; the chain's audience check, request size
  limit, rate limits and bounded reads stand in for the isolation a gateway would give. Moving the endpoint out later
  is the MEM-133 token-exchange shape.
- The endpoint scales with the API; its rate-limit buckets are per node.
- Every passage returned leaves for an external model. Until MEM-134 keeps confidential Sources out, an enabled
  endpoint exposes every Source the member can read.
