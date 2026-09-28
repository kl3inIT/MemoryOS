# 0011 — MCP client in Chat

Status: Accepted, implementation started 2026-09-15. Supersedes only the sentence "MCP remains deferred." in [ADR 0001](0001-controlled-modular-monolith.md); the rest of ADR 0001 stands.

## Context

Chat tools are written one by one (`web_search`, `open_url`, image generation, knowledge search, `run_python`). Ingestion copies content ahead of time and cannot answer live state, structured queries or sources that must be read with the asking User's own permissions. The owner chose to port Onyx `40eb240df`'s MCP client ("MCP actions") without reducing its behavior, accepted first against the official Google Drive MCP server. The increment is [MEM-112](../increments/completed/mem-112-chat-mcp-client/design.md).

## Decision

- Add a closed `core` capability `mcp` that owns MCP servers, their OAuth clients, tool snapshots and credentials, and the client that calls remote servers. Chat depends on it to register per-turn tools; `mcp` depends only on IAM.
- MemoryOS is an MCP client over Streamable HTTP using the official MCP Java SDK. stdio is excluded; HTTP+SSE is excluded unless a required server offers nothing else, because the `2026-07-28` specification deprecates it.
- Authentication types are `NONE`, `API_TOKEN` and `OAUTH`, performed by an administrator for everyone or by each User. A server may have several OAuth clients so each Google Workspace organization keeps an `Internal` app. Credentials are keyed by the Actor, never by email, and by the issuing authorization server.
- OAuth discovery and Dynamic Client Registration run only during `MCP_MANAGE` setup; the discovered endpoints are shown, persisted and become configured endpoints. Runtime calls only persisted endpoints, consistent with the accepted provider endpoint policy that allows internal hosts under trusted management authority.
- Secrets are sealed with Spring Security `AesGcmBytesEncryptor` and bound to Tenant, record and purpose; the key is deployment configuration.
- Pass-through of the User's login token is deferred to a gated phase that exchanges tokens rather than forwarding them and requires its own decision changing the identity contract that discards provider tokens.
- MemoryOS as an MCP server ([MEM-114](https://linear.app/memory-os/issue/MEM-114)) is not decided here.

## Consequences

A new module, migration and capability (`MCP_MANAGE`) enter the system. Chat's tool loop gains tools whose schemas come from administrator snapshots, bounded by the existing turn guards. The MCP Java SDK version currently declares protocol versions up to `2025-11-25`; upgrading it for `2026-07-28` stays inside the `mcp` module. Live Google acceptance depends on Workspace Developer Preview access and is recorded separately from automated verification.
