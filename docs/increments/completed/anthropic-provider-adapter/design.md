# Anthropic provider adapter (MEM-242)

Owner decision 2026-10-11: a connection to Anthropic's own endpoint uses a native adapter, not the `openai` adapter on Anthropic's OpenAI-compatible endpoint. No saved connection used that route, so nothing is migrated.

## Requirement

A second chat `ProviderAdapter`, type `anthropic`, speaks the Messages API, and the *Claude* preset creates a connection of that type. A Claude model then streams its thinking as turn reasoning, keeps it across tool cycles and reports Anthropic's own usage and failures.

A Claude model behind a router (9Router, OpenRouter) stays on the `openai` adapter: the adapter follows the endpoint's protocol, never the model name.

The registry is open for this ([ADR 0019](../../../decisions/0019-provider-families-use-adapters-behind-a-registry.md), [provider extension](../../../specs/chat-models.md#credentials-and-provider-extension)), so no ADR is added.

## Design

### Libraries

- `org.springframework.ai:spring-ai-anthropic` (Spring AI BOM, 2.0.1), on the official `anthropic-java` SDK: `AnthropicChatModel` builds the request and reads the stream.
- `com.embabel.agent:embabel-agent-anthropic` (1.5.2, the Embabel version already in use): `AnthropicOptionsConverter` turns Embabel's options into `AnthropicChatOptions`, as `embabel-agent-openai` does for the OpenAI adapter.

No request or stream is hand-written.

### Package `io.memoryos.ai.anthropic`

| Class | Role |
| --- | --- |
| `AnthropicProviderConfiguration` | Registers the adapter bean |
| `AnthropicProviderAdapter` | Type `anthropic`, credential required; option validation; the Models API listing; `create` and the binding |
| `AnthropicRequestPolicy` | Output clamp, capability checks, tools off on the last cycle, a required tool, the thinking a level asks for |
| `AnthropicTurnModel` | `ModelTurns`: thinking to the turn's listener, `checkActive` on every event, stop reasons and usage normalized |
| `AnthropicCancellation` | The SDK clients on an OkHttp transport whose call is cancelled with the subscription |
| `AnthropicFailures` | A 401 or 403 from the SDK is a rejected credential |

### Decisions

- **Transport.** The adapter owns its OkHttp transport, as the OpenAI adapter does, because a stream must stop from another thread before and after its headers, and only `Call.cancel()` does that safely. Authentication and the API version come from the SDK's `AnthropicBackend`. No redirect is followed and nothing is retried. A cancel closes a received response before the subscription upstream is cancelled, since a blocked read holds the SDK reader's lock; a read that fails because of that close ends the stream, and a request without a response is cancelled only after the subscription is, so a stop never reaches Spring AI, which logs every stream failure, as one.
- **Thinking text.** Spring AI 2.0 keeps streamed thinking text until the response ends and publishes it per delta from 2.1. Until then `AnthropicCancellation` reads the SDK's typed stream events where the SDK hands the stream to Spring AI, through an interface proxy; the class comment says what removes it.
- **Thinking across tools.** Spring AI puts the thinking blocks and signatures of a response in the assistant message's metadata and reads them back from there. Embabel carries that metadata across its tool loop, so the replay needs no code here; the wire test rebuilds the message from its metadata alone to prove it.
- **Levels.** `LOW`, `MEDIUM` and `HIGH` ask for adaptive thinking with that effort. A model that only takes a budget is configured with `thinkingBudget`, never inferred from its name, and gets 2,048, 8,192 or 16,384 tokens, kept below the output bound. `OFF`, helper calls and an inference that must call a tool send thinking disabled.
- **Usage.** Input is reported as input plus cache-read plus cache-write tokens, so the cached share is the subset `ModelAccounting` assumes.
- **Outside the package.** `KnownModels` moves to the `ai` root since two adapters read it; `AiException.providerIncompatible` no longer says "OpenAI-compatible".
- **Web.** A preset may name its adapter type; *Claude* names `anthropic` at `https://api.anthropic.com`. The model editor sends an `anthropic` model only `temperature`, `topP` and `reasoningEffort`, offers no `minimal` level and no completion-token family. It reads the adapter type it already has; the adapter descriptor is unchanged, so the API contract is too.

## Left out

- Prompt-cache control and a cache-write price. Cached tokens a response reports are already counted.
- A Claude tokenizer profile: the adapter estimates with O200K and the 5% input margin, which undercounts Claude's tokenizer on some text.
- `thinkingBudget` in the model editor; it is settable through the API.
- The `openai` adapter refusing `api.anthropic.com`, and removing its reading of Anthropic's listing fields.
- Anthropic's hosted Web search (the open item of [Chat web search](../../active/chat-web-search/plan.md)), an `xhigh` level, the 1M-token context beta, Bedrock and Vertex, a Gemini adapter.

## Risks

- **Run against Anthropic** on staging on 2026-10-11 ([verification](verification.md)).
- **Forced tools and earlier thinking.** A forced tool call sends thinking disabled while earlier assistant messages still carry thinking blocks. The API documents that as accepted; the staging run did not reach a forced tool call.
- **Spring AI 2.1.** The upgrade publishes thinking per delta and removes the need for the event proxy.
