# Provider-enforced structured output for single model calls

Linear: [MEM-225](https://linear.app/memory-os/issue/MEM-225). Follows [ADR 0026](../../../decisions/0026-system-one-through-spring-ai-typesafe.md)
and [MEM-198](../mem-198-system-one/design.md).

## Problem

A single model call that wants a typed answer goes through Embabel's `createObject`. Embabel writes format
instructions into the prompt and parses the text the model returns. A model that answers in another shape fails the
call: on 2026-10-01 staging lost 12 question checks to `InvalidLlmReturnFormatException` on
`ocg/deepseek-v4-flash`. MEM-198 worked around it for the question check by asking for text and reading a flat JSON
object leniently.

The maintained answer is to send the JSON schema to the provider, which then constrains the model to it. Spring AI
2.0 carries it as an option on the request (`StructuredOutputChatOptions.outputSchema`, what
`useProviderStructuredOutput()` sets), with the schema from `BeanOutputConverter`.

## Why not Embabel's native structured output

Embabel 1.5.2, the newest release, has a native path (`NativeSupport`, `SpringAiNativeStructuredOutputConfigurer`,
`NativeStructuredOutputMode`). The first version of this increment used it and sent no schema for any real call.
Measured against `shouldUseNativeStructuredOutput` and read from its bytecode:

- `ENABLED` does not force the path. Like `DEFAULT` it runs a conservative schema check; only `DISABLED` differs.
- The check refuses a list of objects, and a schema in which some property is not required. Embabel's own generator
  marks only primitives required, so a record with a `String` field is refused.
- `TranscriptSummary`, `TranscriptCorrections` and the question check's answer are all refused. A refused call goes
  back to prompt instructions without a word.

Staying inside the library's gate would ship a checkbox and no enforcement. So the schema is sent with Spring AI's
option, and Embabel keeps what it does well: the prompt, the retries and parsing the reply into the type.

## Decisions

1. **A fifth declared capability, `structuredOutput`.** It sits on `ModelSettings.Capabilities` beside tool calling,
   vision and reasoning, is saved in the same settings document, and is edited on the Models page with the same
   checkbox. It is a declared fact about a deployment, not inferred from a name: a gateway may drop the schema, and
   only the model manager knows.
   - `V147` adds the field as `false` to every saved model, so nothing changes until a manager switches it on.
     The reader stays strict: Jackson 3 refuses a missing primitive, as it does for the other capabilities.
   - A provider listing that publishes it prefills it: OpenRouter lists `structured_outputs` in a model's
     `supported_parameters`. Otherwise the known-model catalog does, from LiteLLM's `supports_response_schema`.
     A model neither knows is `false`: unlike tools, a wrong `true` is not caught by the saved-connection check.
2. **`ModelCalls` sends the schema.** For a typed call on a declared model it derives the schema of the answer type
   (`OutputSchemas.strict`, on Spring AI's `BeanOutputConverter`) and carries it on Embabel's `LlmOptions` under
   `ModelBinding.OUTPUT_SCHEMA`. The OpenAI adapter's options converter sets it as `outputSchema`. Chat Completions
   sends `response_format` (`json_schema`, strict) through Spring AI; `ResponsesRequestBuilder` maps the same option
   to `text.format`, since the Responses route is MemoryOS's own request builder.
3. **The schema is strict.** OpenAI's strict mode takes a schema only when every object lists all its properties as
   required and is closed. Spring AI leaves a nullable field out of `required`; `OutputSchemas` adds it back and
   types it as nullable, which is how strict mode expresses an optional field. A model that has no owner for a task
   answers `null`, as it could before.
4. **The question check answers a fixed type.** This departs from the issue, which asked for a flat schema
   generated per request, one key per question id. `ModelCalls` derives a schema from a type, and a `Map` is not
   accepted under `strict`. On a declared model the LLM branch asks for `Answers(List<Scored(id, probability)>)`
   and reads it without the lenient reader. On an undeclared model it keeps the text answer and the lenient reader
   of MEM-198, because prompt-instructed typed output is exactly what failed on staging. Both produce the same
   answers for `JevGuardrail`.
5. **Search helpers are out of scope.** `SearchTool` calls `createObject` on the turn's runner, not through
   `ModelCalls`, and sends no schema.

## What does not change

- A model without the capability: the request, the prompt and the parsing are as today.
- A declared model's prompt: Embabel still writes its format instructions, so a deployment that ignores the schema
  answers as an undeclared model does.
- The System One branch of the question check.
- Retries, deadlines, cost accounting and the reasoning-effort fallback of `ModelGuard` and `OpenAiReasoningFallback`.

## Risks

- **A gateway that ignores the schema.** The question check on a declared model then asks for the typed answer with
  prompt instructions alone, which is what small models got wrong. The capability is the manager's statement that
  the deployment honours the schema; the verification measures the gateway staging uses.
- **A provider that refuses a strict schema.** The call fails with the provider's error instead of answering
  loosely. The schemas of the three answer types are asserted against the strict rules.

## Verification

- Through Embabel end to end: the minutes integration test asserts that the request reaching the provider carries
  the schema.
- Wire tests on a loopback server for both routes: the schema and `strict` in `response_format` and in
  `text.format`, through the rewrites `ModelGuard` applies; nothing sent without a schema.
- The three answer types give a schema strict mode accepts.
- Through 9Router on the free tier with self-written prompts: a schema the model follows only when constrained.
  Recorded in `verification.md`, including what could not be measured.
