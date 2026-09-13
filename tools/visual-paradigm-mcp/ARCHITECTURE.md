# Architecture

## Boundary

The Visual Paradigm bridge owns all calls to `openapi.jar` and all Swing EDT
coordination. It contains no Spring classes and is compiled with
`--release 11`.

The MCP server owns the MCP protocol and transport. It contains no Visual
Paradigm classes, runs on Java 25, and uses Spring Boot 4.1 plus Spring AI 2.0.

```mermaid
flowchart LR
    Client[MCP client] -->|Streamable HTTP :2027/mcp| Sidecar[Spring AI MCP server\nJava 25]
    Sidecar -->|HTTP JSON :2026| Bridge[VP bridge plugin\nJava 11]
    Bridge --> OpenAPI[Visual Paradigm OpenAPI]
```

The bridge binds to loopback only. It is not an MCP server and does not expose
the deprecated SSE transport.
