# Provider adapters behind a registry

Status: **proposed 2026-09-30; not started.** No Linear issue yet. The convention change it follows is in
[change design](../../../conventions.md#change-design).

## Problem

Three capabilities let an administrator pick one of several interchangeable external protocols, and each dispatches
on the provider enum with `switch` statements spread over its services:

| Family | Providers | Where the protocol is chosen today |
| --- | --- | --- |
| Web (`chat.web`) | 8: Brave, Tavily, Exa, Serper, Google PSE, SearXNG, 9Router, Firecrawl | `WebProviderClient.searchRequest` and `readRequest` (two `switch`es mixing default URLs, headers, bodies and JSON paths), plus six capability flags on `WebProvider` written as `this == …` |
| Voice (`voice`) | 5: OpenAI, OpenAI-compatible, ElevenLabs, Azure, Soniox | `BatchTranscriptionService`, `VoiceProviderClient.verify`, `VoiceSynthesisService.provider`, `VoiceTranscriptionService.transcribe`, with part of each protocol already in `AzureSpeech`, `ElevenLabsVoice`, `SonioxAsync` |
| Image (`chat.image`) | 2: OpenAI Images, Cloudflare Workers AI | `ImageProviderClient.generateRequest` and `editRequest` |

Adding or changing a provider means editing every `switch` in its family, and a provider's protocol is read across
several files. `WebProviderClient` also parses JSON with a static `new ObjectMapper()` and reports failures as
untyped `IOException`/`IllegalArgumentException`.

## Owner decision (2026-09-30)

A capability with a provider choice is built as one class per provider behind one interface, collected into a
registry keyed by the provider identity, from its first provider. The existing families move to that shape. The
rule is recorded in [conventions](../../../conventions.md#change-design).

## Reference

MateClaw at `bd41a71`, `mateclaw-server/src/main/java/vip/mate/tool/search/`: `SearchProvider` with one class per
provider (`TavilySearchProvider`, `SerperSearchProvider`, `SearXNGSearchProvider`, `DuckDuckGoSearchProvider`) and
`SearchProviderRegistry`, which collects the Spring beans into a map. The repository's own `ai.ProviderAdapters`
(`List<ProviderAdapter>` into a map keyed by `type()`, typed failure on a missing key) already has this shape.

| | |
| --- | --- |
| Requirement | Owner decision above; three families with 2 to 8 providers each and more expected |
| Reference strengths | A provider's whole protocol is one file; adding a provider adds a class and touches no dispatch |
| Proposed difference | Keep the persisted enum as the key (MateClaw uses strings); fail at startup when an enum constant has no adapter or two; no runtime registration |
| Benefit | Provider changes are local; per-provider tests without the other seven; capability flags live with the protocol |
| Costs | More files per family; the compiler's `switch` exhaustiveness is replaced by a startup check and a test |
| Simpler baseline | Keep the `switch` and move request data into the enum. Rejected by the owner decision |
| Not taken from MateClaw | `registerPluginProvider` (runtime plugin registration, its lock and case-insensitive conflict check): MemoryOS has no plugin requirement. `autoDetectOrder` fallback: see candidates below |

## Design

### Shape of every family

- **The enum stays the identity.** `WebProvider`, `VoiceProvider` and `ImageProvider` are persisted and appear in
  the OpenAPI contract, so they keep their constants and names. Strings never select a provider.
- **One interface per function** in the family's package, `providers()` returning the enum constants the class
  serves (one, or two when constants share a protocol, as `OPENAI` and `OPENAI_COMPATIBLE` do).
- **One class per provider** in an `adapter` subpackage (`chat.web.adapter`, `voice.adapter`,
  `chat.image.adapter`), as `connector.adapter.<provider>` already is ([ADR 0016](../../../decisions/0016-integration-bundle-named-sources.md)),
  internal to the module under Spring Modulith, each a Spring bean.
- **One registry class per function** built from `List<…Adapter>` into an `EnumMap`, indexing each adapter under
  every constant it serves. The constructor always throws when a constant is served twice. A **complete** registry
  (every provider must offer the function) also throws when a constant is missing, so a missing adapter fails the
  application at startup, not a request; its lookup is `require(provider)`. A **partial** registry (only some
  providers offer the function) instead declares the constants it expects, fails at startup when one of those is
  missing, and looks up with `find(provider)` returning `Optional`.

  | Registry | Kind |
  | --- | --- |
  | `WebSearchAdapterRegistry` | Partial: every provider except Firecrawl |
  | `WebContentAdapterRegistry` | Partial: Tavily, Exa, Firecrawl |
  | `VoiceConnectionCheckAdapterRegistry`, `SpeechTranscriptionAdapterRegistry` | Complete |
  | `BatchTranscriptionAdapterRegistry` | Partial: OpenAI, OpenAI-compatible, Soniox |
  | `SpeechSynthesisAdapterRegistry` | Partial: every provider except Soniox |
  | `ImageGenerationAdapterRegistry` | Complete |
- **What a provider supports and offers is a record**, `<Family>ProviderCapabilities`, returned by the adapter:
  flags, default endpoint and catalog (known models, sizes, voices), read from a bundled resource when large. The
  enum keeps only its constants.
- **Shared plumbing stays in one place.** The client that owns timing metrics (`measured`), HTTP (`WebHttp`,
  `ImageHttp`, the voice HTTP clients), clipping and result validation keeps them and delegates only the
  provider-specific request and response mapping. Adapters do not repeat the metrics wrapper.
- **One measurement for every provider call** (owner decision 2026-09-30). Web, Image and the three Voice
  services each hand-roll a `meters.timer(...)` with `provider`, `operation` and `outcome` tags. They are replaced by
  one Micrometer `Observation` per provider call, named per family (`memoryos.chat.web.request`,
  `memoryos.chat.image.request`, `memoryos.chat.voice.request`) with the same low-cardinality keys, as the
  [observability guideline](../../../guidelines/observability.md) prescribes for an operation that needs traces and
  metrics together. The timer names stay, so dashboards keep working; the call also becomes a span. The registry
  client opens the observation, so adapters never measure themselves.
- **Typed failures.** Adapter failures become the family's `BusinessException` (`ChatException`,
  `VoiceException`) with stable codes; the injected Jackson `ObjectMapper` replaces the static one.

### Naming

The repository already uses *provider* for the vendor identity (`WebProvider`, `SourceType.SHAREPOINT`) and
*adapter* for the code that speaks its protocol (`ai.ProviderAdapter`, `OpenAiProviderAdapter`,
`connector.adapter.<provider>`). This increment keeps that split, and takes MateClaw's systematic
`<Vendor><Function><Role>` scheme (`OpenAiSttProvider`, `DashScopeTtsProvider`, `TtsProviderRegistry`,
`ImageProviderCapabilities`, `SttRequest`/`SttResult`) with *Adapter* in place of *Provider*:

| Role | Pattern | Examples |
| --- | --- | --- |
| Vendor identity (persisted, in the API) | `<Family>Provider` enum | `WebProvider`, `VoiceProvider`, `ImageProvider` (unchanged) |
| Function contract | `<Function>Adapter` interface | `WebSearchAdapter`, `WebContentAdapter`, `SpeechTranscriptionAdapter`, `BatchTranscriptionAdapter`, `SpeechSynthesisAdapter`, `VoiceConnectionCheckAdapter`, `ImageGenerationAdapter` |
| Implementation of one function | `<Vendor><Function>Adapter` | `BraveWebSearchAdapter`, `FirecrawlWebContentAdapter`, `SonioxBatchTranscriptionAdapter` |
| Implementation of several functions of a family | `<Vendor><Family>Adapter` | `TavilyWebAdapter` (search and read), `ElevenLabsVoiceAdapter`, `OpenAiImageAdapter` |
| Lookup | `<Function>AdapterRegistry` | `WebSearchAdapterRegistry`, `SpeechSynthesisAdapterRegistry`; `ai.ProviderAdapters` becomes `ProviderAdapterRegistry` |
| What a provider supports | `<Family>ProviderCapabilities` record | `WebProviderCapabilities` |
| Call data | `<Function>Request` / `<Function>Result` records | `WebSearchRequest`, `WebSearchResult` (replacing the nested `WebProviderClient.Result`) |

- Vendor names are written as the vendor writes them, in `UpperCamelCase` with acronyms as words: `OpenAi`,
  `ElevenLabs`, `SharePoint`, `Searxng`, `GooglePse`, `NineRouter` (for 9Router), `CloudflareWorkersAi`.
- One name per concept. MateClaw mixes `GoogleImagenProvider` with `OpenAiImageProvider` and puts an auth mode in a
  class name (`ChatGPTOAuthImageProvider`); MemoryOS does neither.
- Records, not MateClaw's mutable Lombok `@Data @Builder` classes, for capabilities, requests and results.
- A class that owns shared plumbing and delegates to adapters keeps its current name (`WebProviderClient`,
  `ImageProviderClient`, the Voice services), since callers and the API already use it.

### Web

- `WebSearchAdapter` (search) and `WebContentAdapter` (read) are separate interfaces: Firecrawl only reads, and five
  providers only search. Tavily and Exa implement both.
- The flags on `WebProvider` (`search`, `content`, `requiresKey`, `requiresEngine`, `requiresEndpoint`,
  `supportsSiteFilter`) move into `WebProviderCapabilities`; search and read support follow from which registries
  hold the provider. `WebConnectionService.save` and the settings descriptor read them from the registries. If a
  descriptor's JSON changes, `openapi.yml` and the Hey API client are regenerated in the same change.
- 9Router's engine listing (`nineRouterEngines`) moves into `NineRouterWebSearchAdapter`.

### Voice

Voice providers do not all offer the same functions (Soniox is transcription only; ElevenLabs and Azure have no
batch transcription). The family therefore has one interface per function, not one interface with unsupported
methods:

- `VoiceConnectionCheckAdapter` (`verify`), `SpeechTranscriptionAdapter` (short dictation),
  `BatchTranscriptionAdapter` (recordings), `SpeechSynthesisAdapter` (read-aloud), each with its own registry keyed
  by `VoiceProvider`.
- A registry for an optional function holds only the providers that offer it; "not offered" is
  `VoiceException.providerUnavailable()`, as the `switch`es throw today.
- `OPENAI` and `OPENAI_COMPATIBLE` share one adapter whose `providers()` returns both constants.
- The existing `AzureSpeech`, `ElevenLabsVoice` and `SonioxAsync` become the adapters' internals.
- Everything `VoiceProvider` carries besides its name moves into `VoiceProviderCapabilities` on the adapters: flags
  (`requiresKey`, `requiresEndpoint`, `speech`), `baseUrl(...)`, default endpoint, suggested STT and TTS models and
  voices. The enum keeps only its constants.
- Live transcription (`LiveTranscriptionService`, the realtime transcribers) is included only if it dispatches on
  `VoiceProvider`; the implementation step records which.

### Image

`ImageGenerationAdapter` with `generate` and `edit`: `OpenAiImageAdapter` and `CloudflareWorkersAiImageAdapter`.
Known models, sizes and `sizeFor` move from `ImageProvider` into `ImageProviderCapabilities` on each adapter; the
enum keeps only its constants.

### Chat models (`ai`)

The model family is the reference, and already puts its catalog on the adapter: `ProviderAdapter` exposes
`credentialRequirement()`, `tokenizerProfiles()`, `knownModels()` (from the bundled `chat/known-models.json`),
`nativeWebSearch()` and `reportedModels(...)`. Two gaps remain:

- **Identity is a string.** `ProviderAdapter.type()` returns `"openai"` and `llm_providers.adapter_type` stores it.
  Step 1 adds a `ModelAdapterType` enum (today only `OPENAI`, persisted and serialized as `"openai"`, so no
  migration and the same JSON value; `openapi.yml` and the web client regenerate if the schema gains the enum) and
  keys `ProviderAdapterRegistry` by it. It is not named `<Family>Provider` because `LlmProvider` already names a
  Tenant's configured model connection.
- **Its capability methods are loose.** They stay as they are; grouping them into a `ModelProviderCapabilities`
  record is not worth the churn while one adapter exists, and is revisited when the Gemini or Anthropic adapter
  from [Chat Web search](../chat-web-search/plan.md) is added, which starts in this shape.

### Families that start in this shape

- Gemini and Anthropic model adapters ([Chat Web search](../chat-web-search/plan.md)).
- Lark Suite and later Sources ([MEM-118](../mem-118-connector-adapters-lark/design.md)).
- The Trạm 1 classifier ([MEM-198](https://linear.app/memory-os/issue/MEM-198)): `GroundingClassificationAdapter`
  keyed by `GroundingClassifierType { LLM, SYSTEM_ONE }`, with one `SystemOneGateway` for every server speaking
  `/v1/systemone` (Laya, Kev, simple-jev, Jev hosted differ only in configuration), reused by MEM-129 and MEM-134.

### Already in place: provider-specific model behavior

Wrapping a `ChatModel` to fix one provider's behavior is already how `ai` works: `ModelGuard`,
`OpenAiReasoningFallback`, `ChatCompletionsReasoning` and `OpenAiCancellation.Native` delegate to the next
`ChatModel`. This increment changes none of them.

### Out of scope

- **Connectors** are standardized in their own increment,
  [connector adapters and Lark](../mem-118-connector-adapters-lark/design.md) (MEM-118), because their dispatch
  reaches operation kinds, provider tables and the sync and lease machinery. It builds on step 1 here.
- **Automatic provider fallback** (MateClaw's `autoDetectOrder`). Today an administrator selects one Web provider;
  choosing another automatically changes product behavior and needs its own owner decision.
- `chat-web-search` stays open for native adapters and paid acceptance; its native work is in `ai.openai`, not in
  `WebProviderClient`. Whichever lands second rebases onto the other.

## Verification

- Each family: a registry test that fails when a constant has no adapter or a duplicate; the existing provider
  tests stay green unchanged, since the refactor preserves behavior.
- `ModulithArchitectureTest` and `CoreDependencyRulesTest` pass with the new `provider` subpackages.
- `OpenApiContractTest` passes; regenerate only if a descriptor changed.
- `clean check` after each family.

## ADR

The rule narrows [ADR 0015's layout inside a module](../../../decisions/0015-capability-module-map.md#layout-inside-a-module),
which keeps an interface only when it is a published API with an internal implementation: a provider family keeps
an internal interface even with one provider. It does not conflict with
[ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md), since a registry with real adapters and a
real selection is not speculative. ADRs are append-only, so a new ADR narrowing ADR 0015 is recorded when the Web
step starts.
