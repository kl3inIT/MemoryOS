# Spring AI 2.0 best practices for this project

Research date: 2026-07-25. Target stack: Spring Boot 4.1.0, Spring AI 2.0.0,
Java 25, OpenAI, and a synchronous Streamable HTTP MCP server.

## Decisions applied

1. Use the current WebMVC starter
   `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` with
   `spring.ai.mcp.server.protocol=STREAMABLE`. The MCP endpoint is explicitly
   fixed to `/mcp`; the old standalone SSE transport is not implemented.
2. Keep Spring AI outside Visual Paradigm. The VP plugin is compiled with
   `--release 11`; only the external MCP server runs Spring Boot and Java 25.
3. Expose only the tool capability. Resources, prompts, completions, and change
   notifications are disabled because this server does not implement them.
4. Register the bridge catalog through a `ToolCallbackProvider`. Each tool has a
   unique name, a purpose-oriented description, a strict JSON schema, parameter
   descriptions, and `additionalProperties=false`.
5. Treat tool execution as application code, not model code. The model proposes
   a call; the server validates it, invokes the loopback bridge, applies a
   timeout, and returns a controlled error.
6. Bind both processes to loopback. Never expose the raw VP bridge to the LAN.
7. For an OpenAI diagram agent, disable parallel tool calls. Diagram mutations
   are order-dependent: nodes must exist before connectors, and all elements
   must exist before layout and verification.
8. Use `ChatClient` rather than calling `ChatModel` directly when the Java
   application should execute tool calls automatically. In Spring AI 2.0,
   `ChatClient` supplies the `ToolCallingAdvisor`; direct `ChatModel` calls
   require the application to manage the tool loop.
9. Parse the problem statement into a typed plan before mutation. Prefer Java
   records plus structured-output validation over free-form text parsing.
10. Keep OpenAI credentials only in `OPENAI_API_KEY`. Do not commit `.env`,
    keys, prompts containing student/private data, or raw model/tool payloads.

## Prompt strategy

The recommended baseline is a low-variance, structured workflow:

- System/role prompt: senior UML analyst with explicit diagram constraints.
- Contextual prompt: include the exact problem statement and requested diagram
  types; do not silently add domain facts.
- Step-back pass: extract verified entities, events, decisions, states, and
  invariants before planning tool calls.
- Few-shot examples: use one small valid example per diagram type when a stable
  output schema is required.
- Structured plan: map the answer into records and validate it before any
  mutating tool is enabled.
- Reflection: verify the created elements against the plan using read tools;
  repair only concrete omissions.
- Self-consistency: reserve multiple model calls for ambiguous/high-risk
  analysis. Do not execute several competing mutation plans.

The agent should not request or print hidden chain-of-thought. It should return
the extracted facts, a concise plan, tool outcomes, and a verification
checklist.

## OpenAI configuration baseline

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        model: ${OPENAI_MODEL}
        store: false
        parallel-tool-calls: false
```

Choose either `temperature` or `top-p`, not both. Model families differ in
whether they use `max-tokens` or `max-completion-tokens`, so that setting should
be model-specific rather than copied blindly.

For production, set finite request timeouts/retries, make mutation operations
idempotent where possible, and attach a stable hashed safety identifier rather
than an email or student ID.

## Observability and privacy

- Keep Actuator health enabled for the sidecar and expose no sensitive
  environment/config endpoints.
- Record tool name, outcome, latency, and correlation ID.
- Do not record API keys or full problem statements by default.
- Spring AI tool observations already use low-cardinality tool-definition
  names; avoid putting element names or prompt text into metric tags.
- Decide explicitly whether tool errors should be thrown to the caller or
  returned to the model. This MCP server returns controlled errors so the
  client can repair a plan, while bridge connectivity failures fail startup by
  default.

## Sources

- [Spring AI Prompt Engineering Patterns](https://docs.spring.io/spring-ai/reference/api/chat/prompt-engineering-patterns.html)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Spring AI OpenAI Chat](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html)
- [Spring AI Streamable HTTP MCP Server](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)
- [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/index.html)
