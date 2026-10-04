# Provider-enforced structured output for single model calls

Linear: [MEM-225](https://linear.app/memory-os/issue/MEM-225). Follows [ADR 0026](../../../decisions/0026-system-one-through-spring-ai-typesafe.md)
and [MEM-198](../../completed/mem-198-system-one/design.md).

## Problem

A single model call that wants a typed answer goes through Embabel's `createObject`. MemoryOS declares nothing about
structured output, so Embabel writes format instructions into the prompt and parses the text the model returns. A
model that answers in another shape fails the call: on 2026-10-01 staging lost 12 question checks to
`InvalidLlmReturnFormatException` on `ocg/deepseek-v4-flash`. MEM-198 worked around it for the question check by
asking for text and reading a flat JSON object leniently.

Spring AI 2.0 and Embabel 1.5.2 both carry the maintained answer: send the JSON schema to the provider, which then
constrains the model to it. Embabel uses it when three things hold: the model declares
`NativeSupport.structuredOutput.supported`, the caller's `LlmOptions` do not disable it, and the model service has a
`SpringAiNativeStructuredOutputConfigurer` for its provider. MemoryOS supplies none of the three.

## Decisions

1. **A fifth declared capability, `structuredOutput`.** It sits on `ModelSettings.Capabilities` beside tool calling,
   vision and reasoning, is saved in the same settings document, and is edited on the Models page with the same
   checkbox. It is a declared fact about a deployment, not inferred from a name: a gateway may drop
   `response_format`, and only the model manager knows.
   - `V147` adds the field as `false` to every saved model, so nothing changes until a manager switches it on.
     The reader stays strict: Jackson 3 refuses a missing primitive, as it does for the other capabilities.
   - The known-model catalog takes it from LiteLLM's `supports_response_schema`, the field it already publishes
     beside the three it is synchronised from. A model the provider reports and the catalog does not know is `false`:
     unlike tools, a wrong `true` is not caught by the saved-connection check.
2. **One configurer for both OpenAI routes.** Both routes read `OpenAiChatOptions`. `OpenAiStructuredOutput` sets
   Spring AI's `ResponseFormat` (`json_schema`, the schema, its name, `strict`) on them. Chat Completions sends it as
   `response_format` through Spring AI. `ResponsesRequestBuilder` maps the same option to `text.format`, since the
   Responses route is MemoryOS's own request builder.
3. **`ModelCalls` asks for it explicitly.** For a model that declares the capability the call uses
   `NativeStructuredOutputMode.ENABLED`; for one that does not, `DISABLED`. Embabel's `DEFAULT` mode adds a
   conservative schema check that silently falls back to prompt instructions; a single call must not differ between
   two answer types without anyone seeing it.
4. **The question check answers a fixed type.** This departs from the issue, which asked for a flat schema
   generated per request, one key per question id. A schema with one key per topic cannot come from a Java type, and a
   `Map` is not accepted under `strict`. On a model with the capability the LLM branch asks for
   `Answers(List<Answer(id, score)>)` and reads it without the lenient reader. On a model without it the branch
   keeps the text answer and the lenient reader of MEM-198, because prompt-instructed typed output is exactly what
   failed on staging. Both produce the same `Map<String, Double>` for `JevGuardrail`.
5. **Search helpers are out of scope.** `SearchTool` calls `createObject` on the turn's runner in Embabel's
   `DEFAULT` mode. Declaring the capability on the model lets Embabel use the schema there when its own check
   accepts the type and fall back when it does not; this increment neither forces nor tests it.

## What does not change

- A model without the capability: the request, the prompt and the parsing are as today.
- The System One branch of the question check.
- Retries, deadlines, cost accounting and the reasoning-effort fallback of `ModelGuard` and `OpenAiReasoningFallback`.

## Risks

- **A gateway that ignores the schema.** Embabel appears to build its format instructions for the prompt before,
  and apart from, the native request (`ToolLoopLlmOperations`, read from the 1.5.2 bytecode, not measured), in
  which case the call behaves as it does today. The question check is the exception: on a declared model it asks for the typed answer, which is
  what small models got wrong without enforcement. The capability is the manager's statement that the
  deployment honours the schema; the verification measures the gateway staging uses.
- **`strict` and the schema Embabel generates.** OpenAI's strict mode requires every property listed as required and
  `additionalProperties: false`. The wire tests assert the schema sent for each answer type.

## Verification

- Wire tests on a loopback server for both routes: the schema, name and `strict` in `response_format` and in
  `text.format`; nothing sent for a model without the capability.
- The three answer types (`TranscriptSummarizer`, `TranscriptCorrector`, the question check) parsed from a
  schema-shaped answer.
- Through 9Router on the free tier with self-written prompts: a schema the model follows only when constrained.
  Recorded in `verification.md`, including what could not be measured.
