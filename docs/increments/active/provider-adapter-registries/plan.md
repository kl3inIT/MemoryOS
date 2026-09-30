# Implementation plan

Each step is behavior-preserving and ends green on its own. Nothing is implemented yet.

## 0. Convention and scope

- [x] Record the provider-family and naming rules in [conventions](../../../conventions.md#change-design) and point
  to them from the [backend guide](../../../guidelines/backend.md).
- [ ] Owner review of [design](design.md); open a Linear issue and link it here and in the roadmap.
- [ ] Record the ADR narrowing ADR 0015's single-implementation rule for provider families when step 2 starts.
- [x] Connectors planned in [connector adapters and Lark](../mem-118-connector-adapters-lark/plan.md), after step 1.

## 1. Rename the existing registry

- [ ] `ai.ProviderAdapters` becomes `ProviderAdapterRegistry`, keyed by the adapter's string type (open family; no
  enum), with the same startup failure on a duplicate; update callers and tests. No migration, no API change.
- [ ] `TokenizerProfiles` moves to the `ai` root: `estimator(profile)` resolves a model's profile to the one cached
  O200K estimator, replacing the string check in `OpenAiProviderAdapter`; `ChatTurnSetup` uses it instead of a
  second `JTokkitTokenCountEstimator`, including the no-selection path from `ChatTurnPersistence`. Behavior
  unchanged. Measuring other models' tokenizers and a vLLM `/tokenize` profile are not planned (owner decision
  2026-09-30).

## 2. Web

- [ ] One `Observation` per provider call replaces `WebProviderClient.measured`; the same for Image and Voice in
  their steps. Check the timer names and tags the dashboards read before and after.

- [ ] `WebSearchAdapter`, `WebContentAdapter`, `WebProviderCapabilities`, `WebSearchRequest`, `WebSearchResult`.
- [ ] Eight adapters under `chat.web.adapter`: Brave, Serper, GooglePse, Searxng, NineRouter (search); Firecrawl
  (read); Tavily, Exa (both).
- [ ] `WebSearchAdapterRegistry` and `WebContentAdapterRegistry` (`EnumMap`, fail at startup on a missing or
  duplicate provider).
- [ ] `WebProviderClient` keeps `measured`, `WebHttp`, clipping and result validation and delegates the request and
  response mapping; the injected `ObjectMapper` replaces the static one; failures become typed `ChatException`s.
- [ ] Flags leave `WebProvider`; `WebConnectionService` and the settings descriptor read `WebProviderCapabilities`.
- [ ] Tests: registry completeness; one test per adapter's request and mapping; existing Web tool and connection
  tests unchanged.
- [ ] Regenerate `openapi.yml` and the Hey API client only if the descriptor changed; `pnpm --dir web check` then.
- [ ] `clean check`.

## 3. Voice

- [ ] Confirm whether live transcription dispatches on `VoiceProvider`; record the answer in the design.
- [ ] `VoiceConnectionCheckAdapter`, `SpeechTranscriptionAdapter`, `BatchTranscriptionAdapter`,
  `SpeechSynthesisAdapter` and their registries.
- [ ] Adapters under `voice.adapter`: `OpenAiVoiceAdapter` (both OpenAI constants), `ElevenLabsVoiceAdapter`,
  `AzureVoiceAdapter`, `SonioxVoiceAdapter`, absorbing `AzureSpeech`, `ElevenLabsVoice`, `SonioxAsync`.
- [ ] Replace the four `switch`es in `BatchTranscriptionService`, `VoiceProviderClient`, `VoiceSynthesisService` and
  `VoiceTranscriptionService`.
- [ ] Tests: registry completeness and "not offered" per function; existing Voice tests unchanged; `clean check`.

## 4. Image

- [ ] `ImageGenerationAdapter`, `OpenAiImageAdapter`, `CloudflareWorkersAiImageAdapter`,
  `ImageGenerationAdapterRegistry`; `ImageProviderClient` delegates.
- [ ] Existing image tool and connection tests unchanged; `clean check`.

## 5. Consolidate

- [ ] `ARCHITECTURE.md` and the affected specs describe the adapter registries; `docs/tests/` matrices updated.
- [ ] Decide whether the provider-family rule needs its own ADR.
- [ ] After merge, move this increment to `completed/` and reconcile the roadmap.
