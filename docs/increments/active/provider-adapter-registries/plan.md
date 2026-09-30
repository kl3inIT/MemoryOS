# Implementation plan

Each step is behavior-preserving and ends green on its own. Nothing is implemented yet.

## 0. Convention and scope

- [x] Record the provider-family and naming rules in [conventions](../../../conventions.md#change-design) and point
  to them from the [backend guide](../../../guidelines/backend.md).
- [ ] Owner review of [design](design.md); open a Linear issue and link it here and in the roadmap.
- [ ] Record the ADR narrowing ADR 0015's single-implementation rule for provider families when step 2 starts.
- [x] Connectors planned in [connector adapters and Lark](../mem-118-connector-adapters-lark/plan.md), after step 1.

## 1. Rename the existing registry

- [x] `ai.ProviderAdapters` becomes `ProviderAdapterRegistry`, keyed by the adapter's string type (open family; no
  enum), with the same startup failure on a duplicate. PR #401.
- [x] `TokenizerProfiles` moves to the `ai` root; `estimator(profile)` resolves a model's profile; `ChatTurnSetup`
  shares the one O200K estimator. Measuring other models' tokenizers and a vLLM `/tokenize` profile are not planned
  (owner decision 2026-09-30). PR #401.

## 2. Web

- [x] `WebAdapter` with `WebSearchAdapter`, `WebContentAdapter`, `WebEngineListAdapter`; `WebProviderCapabilities`;
  `WebCall` for shared transport and bounds.
- [x] Eight adapters under `chat.web.adapter`: Brave, Serper, GooglePse, Searxng (search); Firecrawl (read); Tavily,
  Exa (search and read); NineRouter (search and engines).
- [x] One `WebAdapterRegistry` (`EnumMap`, fails at startup on a missing or duplicate provider).
- [x] Flags leave `WebProvider`; `WebConnectionService`, `ChatModelExecutor` and `WebConnectionController` read the
  registry. No API change.
- [x] One `Observation` per provider call replaces the hand-written timer; same timer name and tags.
- [x] Tests: `WebAdapterRegistryTest` (completeness, duplicates, functions and capabilities per provider); existing
  Web client, PDF, tool and API tests pass with only their construction changed.
- [ ] `clean check` in CI.

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
