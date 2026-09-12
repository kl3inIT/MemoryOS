# Visual Paradigm MCP architecture review brief

You are an independent architecture reviewer. Work read-only:

- Do not edit, create, move, or delete project files.
- Do not run destructive commands.
- Inspect the repository and actual installed dependency JARs before making claims.
- Clearly separate observed facts, recommendations, and unresolved uncertainties.
- Do not implement anything.
- State the active Claude model at the top of the response.

## Repository

`D:\Workspaces\SupportPE\SWD392\swd392-vp-mcp`

## Goal

We are about to add native Visual Paradigm support for a System Context Diagram,
defined by the course and official Visual Paradigm documentation as a level-0
Data Flow Diagram:

- exactly one process representing the entire system;
- external entities around it;
- named, directed data flows;
- no internal process decomposition in the context diagram.

The immediate goal is not implementation. First determine the architecture and
reliability improvements required to make diagram creation correct, smooth, and
fast as the tool catalog grows.

## Current architecture to verify

The repository appears to use two runtimes:

1. A Java 11 plugin running inside Visual Paradigm 18.1.
   - Binds to `127.0.0.1:2026`.
   - Exposes private JSON/HTTP endpoints `/health`, `/tools`, and `/execute`.
   - Owns all calls to Visual Paradigm `openapi.jar`.
   - Moves Visual Paradigm operations onto the Swing EDT.
2. A Java 25 Spring Boot 4.1 / Spring AI 2.0 sidecar.
   - Binds to `127.0.0.1:2027/mcp`.
   - Exposes MCP over Streamable HTTP.
   - Loads bridge tool definitions once during Spring startup.
   - Forwards tool executions to the Java 11 bridge.

There are currently 66 atomic tools. The current prompt is a single long system
prompt containing rules for several diagram types.

Do not assume this summary is correct. Verify it from the code, resolved Gradle
dependencies, and installed JARs.

## Required review

### 1. Before Context Diagram

Identify the minimum changes that must be completed before native Context
Diagram tools are added. Distinguish hard blockers from optional improvements.

### 2. Tool catalog and discovery

Recommend the best design for 70+ tools while preserving:

- MCP interoperability;
- typed JSON schemas;
- clear user approvals/tool visibility;
- low prompt/token overhead;
- reliable tool selection;
- compatibility with clients that do and do not support deferred tool loading.

Compare at least:

- advertising every atomic tool;
- a server-side semantic `searchTools` plus generic `executeTool`;
- diagram-specific MCP endpoints/profiles;
- coarse workflow tools backed by internal atomic operations;
- MCP prompts/resources for diagram-specific guidance;
- full and compact catalog modes.

Explain whether MCP's standard `tools/list` and tool-list-changed notification
solve the same problem as semantic tool search.

### 3. Spring AI 2.0 and MCP SDK

Inspect the resolved Spring AI 2.0 community JARs and relevant MCP SDK JARs
directly. Verify, with exact class names or configuration properties where
possible:

- dynamic tool registration or replacement after server startup;
- tool list change notification support;
- `ToolCallbackProvider` lifecycle;
- MCP prompts and resources;
- Streamable HTTP behavior;
- tool annotations and schema generation;
- progress/logging support;
- structured tool results and metadata;
- limitations of the current boot auto-configuration approach.

Flag dependency/classpath issues, including the coexistence of Jackson 2 and
Jackson 3 if relevant.

### 4. Visual Paradigm OpenAPI

Inspect the installed Visual Paradigm 18.1 `openapi.jar` directly. Determine the
native classes/constants/methods for:

- Data Flow Diagram creation;
- process;
- external entity;
- data store, even if a level-0 context diagram should not use one;
- data flow and bidirectional data flow;
- diagram elements, connectors, captions, routing, and bounds.

Prefer native DFD model elements. Do not recommend drawing generic circles and
rectangles unless the OpenAPI genuinely lacks native DFD support.

Identify any licensing, edition, API, rendering, or notation limitations.

### 5. Mutation safety

Review and recommend concrete handling for:

- Swing EDT serialization and request ordering;
- multiple simultaneous MCP calls;
- duplicate mutations caused by retries;
- idempotency keys;
- tool preconditions and postconditions;
- partial failure during multi-step construction;
- transaction/undo/rollback capabilities;
- timeouts and cancellation;
- saving only after validation;
- structured error codes and recovery hints.

### 6. Diagram correctness and layout

Recommend a design that creates diagrams quickly while keeping correctness
deterministic. Cover:

- an intermediate structured `DiagramSpec`;
- evidence/requirement traceability;
- semantic validators implemented in Java;
- geometry and connector-crossing checks;
- explicit layout versus Visual Paradigm auto-layout;
- export and visual inspection;
- repair loops;
- native diagram-specific constraints.

For a level-0 Context Diagram, include the invariants that should be enforced in
code rather than left only in a prompt.

### 7. Prompt and instruction architecture

Review:

- `prompts/diagram-agent-system.md`;
- `skills/swd392-vp/SKILL.md`;
- MCP server `instructions`;
- current prompt/resource capabilities.

Propose a modular instruction hierarchy that avoids repeating every diagram's
rules in every request. Explain what belongs in:

- short server instructions;
- per-diagram MCP prompts;
- read-only MCP resources;
- tool descriptions and schemas;
- Java validators;
- client-side task prompts.

### 8. Target architecture and phased plan

Return:

1. A severity-ranked findings table.
2. The smallest safe improvement set required before Context Diagram.
3. Improvements that can wait until after the first working Context Diagram.
4. A phased target architecture.
5. A proposed tool surface for Context Diagram.
6. A proposed compact/full discovery strategy.
7. Explicit disagreements or caveats about the current design.

Avoid generic MCP advice. Every important conclusion should point to observed
repository/JAR evidence or be clearly marked as a recommendation.
