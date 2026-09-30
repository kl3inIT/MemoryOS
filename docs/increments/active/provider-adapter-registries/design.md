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
- **One class per provider**, each a Spring bean, returning its enum constant from `provider()`. Where two constants
  share a wire protocol but not behavior (Voice `OPENAI` and `OPENAI_COMPATIBLE`), each has its own thin class over
  a shared package-private protocol helper, so no adapter branches on its own provider.
- **Where the classes live.** In an `adapter` subpackage (`chat.web.adapter`, `chat.image.adapter`), internal to the
  module under Spring Modulith. Two deliberate exceptions keep the classes next to code that is not public: Voice
  adapters sit in `voice` itself, because its provider clients (`AzureSpeech`, `ElevenLabsVoice`, `SonioxAsync`, the
  realtime transcribers) are package-private by module design and tested there; connector adapters sit in their
  provider feature package (see [MEM-118](../mem-118-connector-adapters-lark/design.md)).
- **One registry per family, complete.** Every constant has exactly one class; the constructor fails at startup on a
  missing or duplicate adapter. A function only some providers offer is its own interface extending the family's
  base, and the registry answers it by the interfaces the class implements (`Optional` lookups internally, boolean
  questions such as `speaks` or `reads` publicly). "Not offered" keeps the exception the old `switch` threw.
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
| Function contract | `<Function>Adapter` interface | `WebSearchAdapter`, `WebContentAdapter`, `RealtimeTranscriptionAdapter`, `BatchTranscriptionAdapter`, `SpeechSynthesisAdapter`, `ImageGenerationAdapter` |
| Implementation of one function | `<Vendor><Function>Adapter` | `BraveWebSearchAdapter`, `FirecrawlWebContentAdapter`, `SonioxBatchTranscriptionAdapter` |
| Implementation of several functions of a family | `<Vendor><Family>Adapter` | `TavilyWebAdapter` (search and read), `ElevenLabsVoiceAdapter`, `OpenAiImageAdapter` |
| Lookup | `<Family>AdapterRegistry`, one per family | `WebAdapterRegistry`, `VoiceAdapterRegistry`, `ImageAdapterRegistry`; `ai.ProviderAdapters` becomes `ProviderAdapterRegistry` |
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

Implemented as follows (step 2):

- `WebAdapter` (`provider()`, `capabilities()`) is extended by one interface per function: `WebSearchAdapter`,
  `WebContentAdapter` and `WebEngineListAdapter` (9Router's engine list). A provider class implements the functions
  it offers; Tavily and Exa implement search and read, 9Router search and engine listing.
- One `WebAdapterRegistry` rather than one per function: every `WebProvider` has exactly one class, so the registry
  holds one adapter per constant, fails at startup on a missing or duplicate one, and answers `searches`, `reads`
  and the per-function lookups by the interfaces the class implements.
- `WebProvider` keeps only its constants. `WebProviderCapabilities` (`requiresKey`, `requiresEndpoint`,
  `requiresEngine`, `siteFilter`) replaces the enum's flags in `WebConnectionService`, `ChatModelExecutor` and
  `WebConnectionController`. The Web settings API has no provider descriptor (the web client knows the providers),
  so `openapi.yml` does not change.
- `WebCall` holds what every adapter shares: the HTTP call and JSON parsing with the injected `ObjectMapper`, the
  "Web provider request failed" rule, result URL validation, the twenty-result and clipping bounds.
- `WebProviderClient` keeps the credential lookup, the built-in reader and one `Observation`
  (`memoryos.chat.web.request`, keys `provider`, `operation`, `outcome`) per provider call; Boot's meter handler
  turns it into the same timer the Chat & AI dashboard reads, now with a span and an `error` tag.
- Provider failures stay `IOException` inside the client: `WebTools` keeps partial results per failed request and
  the controller maps them to `ChatException.providerUnavailable()`, which is already the typed boundary. The
  nested `WebProviderClient.Result` keeps its name, as `WebTools` and `WebPdfReader` use it.

### Voice

Implemented as follows (same PR as Web and Image):

- `VoiceAdapter` (package-private) carries what every provider does: `verify` and short `transcribe`. Function
  interfaces extend it: `RealtimeTranscriptionAdapter` (OpenAI on its own endpoint, Soniox), `LiveTranscriptionAdapter`
  (Soniox), `BatchTranscriptionAdapter` (OpenAI, OpenAI-compatible, Soniox; with `diarizes` and `maxBytes`) and
  `SpeechSynthesisAdapter` (all but Soniox).
- Five classes in `voice`: `OpenAiVoiceAdapter`, `OpenAiCompatibleVoiceAdapter`, `ElevenLabsVoiceAdapter`,
  `AzureVoiceAdapter`, `SonioxVoiceAdapter`. `OpenAiAudio` holds the OpenAI protocol both OpenAI classes share
  (SDK transcription, verbose-JSON recordings, SDK speech, `NO_CREDENTIAL`); `VoiceChecks` the listing checks;
  `ProviderSpeech` the read-aloud stream both REST and SDK speech return.
- `VoiceAdapterRegistry` is public for the API and `meeting`: `capabilities`, `speaks`, `transcribesRecordings`,
  `diarizesRecordings`, `maxRecordingBytes`. `BatchTranscriptionService.supports/diarizes/maxBytes` became instance
  methods; `MeetingRecordingService` calls them on its injected service.
- `VoiceProvider` keeps only its constants; `VoiceProviderCapabilities` holds the flags, `baseUrl(...)`, default
  endpoint and suggested models and voices. `VoiceProviderResponse` reads them, so the API JSON is unchanged.
- Verification and dictation transcription are one `Observation` each (`memoryos.chat.voice.request`). The
  read-aloud timer is still recorded directly: it measures a stream's lifetime, closed from another thread, and
  moving it to an observation is left to a separate change.
- Azure stays on REST (MEM-91 Q1). Moving it to the Speech SDK is [MEM-137](https://linear.app/memory-os/issue/MEM-137);
  with the adapter in place it replaces `AzureVoiceAdapter`'s internals and adds realtime and streaming speech.

### Image

Implemented as follows: `ImageGenerationAdapter` (`generate`, `edit`, `resolvedModel`, `usageModel`,
`normalizeEndpoint`) with `OpenAiImageAdapter` and `CloudflareWorkersAiImageAdapter` in `chat.image.adapter`;
`ImageAdapterRegistry` complete; `ImageCall` for the shared HTTP, JSON and image decoding. Known models, sizes,
`sizeFor`, the edit model and Cloudflare's account-ID expansion move from `ImageProvider` into
`ImageProviderCapabilities` and the adapters; `ImageConnectionService.providers` returns each provider with its
capabilities for the API. One `Observation` per call replaces the image timer (same name and keys).

### Chat models (`ai`)

The model family is the reference, and already puts its catalog on the adapter: `ProviderAdapter` exposes
`credentialRequirement()`, `tokenizerProfiles()`, `knownModels()` (from the bundled `chat/known-models.json`),
`nativeWebSearch()` and `reportedModels(...)`. Two gaps remain:

- **Identity stays a string, by design.** `ProviderAdapter.type()` (`"openai"`) is persisted in
  `llm_providers.adapter_type`, and the family is open: `secondRegisteredAdapterNeedsNoExecutorChangesOrDummyCredentials`
  registers a `fixture-local` adapter as a bean and nothing else changes. An enum would turn every new protocol into
  an edit of `ai` and break that contract, so step 1 renames `ProviderAdapters` to `ProviderAdapterRegistry`, keeps
  the string key and its startup failure on a duplicate type, and adds no enum (decided 2026-09-30 while
  implementing; the convention now distinguishes closed and open families).
- **Tokenizer profiles** resolve through `ai.TokenizerProfiles.estimator(profile)`, moved from `ai.openai` to the
  module root so `chat` uses the same cached O200K estimator instead of loading a second vocabulary.
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
