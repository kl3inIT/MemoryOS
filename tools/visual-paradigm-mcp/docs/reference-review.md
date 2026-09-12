# Review of the two reference repositories

Research date: 2026-07-25.

## `thanhtrnnn/cnpm`

[`cnpm`](https://github.com/thanhtrnnn/cnpm) is primarily a documentation and
agent-workflow repository. Its `cnpm-vp` skill prescribes a useful creation
order:

1. create diagram;
2. add nodes;
3. add relationships/messages;
4. auto-layout;
5. inspect/report the result.

The Visual Paradigm implementation is not independent code in this repository;
it is linked as the `visual-paradigm-mcp-plugin` submodule pointing to
`thanhtrnnn/vp-mcp`.

Useful ideas retained:

- stable per-diagram workflows;
- BCE stereotypes for analysis/design diagrams;
- verification after layout;
- explicit rules for sequence lifelines and class relationships.

Gaps for the current SWD392 papers:

- no Activity diagram tools;
- no State diagram tools;
- no direct image-export tool;
- its setup scripts target the legacy `/sse` transport;
- a combined fragment workflow documents only one guard/operand at a time.

## `thanhtrnnn/vp-mcp`

[`vp-mcp`](https://github.com/thanhtrnnn/vp-mcp) is a Java 11 Visual Paradigm
plugin derived from `orgatex/visual-paradigm-mcp-plugin`. It demonstrates the
important Visual Paradigm OpenAPI mechanics:

- create model elements through `IModelElementFactory`;
- create shapes through `DiagramManager.createDiagramElement`;
- create relationships through `DiagramManager.createConnector`;
- execute OpenAPI mutations on Swing's EDT;
- package the plugin with its runtime libraries.

Useful ideas retained:

- reflection-based tool catalog at the Java 11 boundary;
- diagram lookup and post-creation inspection;
- explicit relationship creation and layout.

Parts intentionally not retained:

- Maven build;
- embedded/hand-written MCP JSON-RPC and legacy SSE server;
- Docker proxy mode;
- Use Case and ERD scope not present in the supplied papers;
- global name lookup that can select an element from the wrong diagram.

## Clean-room result

This repository uses the references as behavioral/API research only. It has a
new package namespace, new Gradle build, new loopback bridge protocol, and new
implementations for the four paper-required diagram families. Spring AI owns
the MCP protocol in the Java 25 process; the Java 11 plugin owns only Visual
Paradigm OpenAPI operations.
