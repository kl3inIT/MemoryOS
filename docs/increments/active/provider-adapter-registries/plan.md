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
- [ ] `clean check` in CI (one PR for Web, Voice and Image, #402).

## 3. Voice

- [x] Live transcription dispatches on `VoiceProvider` (Soniox's live protocol); it has `LiveTranscriptionAdapter`.
- [x] `VoiceAdapter` with `RealtimeTranscriptionAdapter`, `LiveTranscriptionAdapter`, `BatchTranscriptionAdapter`,
  `SpeechSynthesisAdapter`; one complete `VoiceAdapterRegistry`.
- [x] Five adapters in `voice` (package-private), `OpenAiAudio`, `VoiceChecks`, `ProviderSpeech`.
- [x] The `switch`es in `BatchTranscriptionService`, `VoiceProviderClient`, `VoiceSynthesisService`,
  `VoiceTranscriptionService` and `LiveTranscriptionService` replaced; flags leave `VoiceProvider`.
- [x] Verify and transcribe as observations; the read-aloud timer stays for a separate change.
- [x] Tests: `VoiceAdapterRegistryTest`; existing Voice, meeting and API tests pass with only construction changed.

## 4. Image

- [x] `ImageGenerationAdapter`, `OpenAiImageAdapter`, `CloudflareWorkersAiImageAdapter`, `ImageAdapterRegistry`,
  `ImageCall`, `ImageProviderCapabilities`; `ImageProviderClient` delegates and observes.
- [x] Existing image client, tool and connection tests pass; `ImageProviderTest` covers the registry and catalog.

## 5. Consolidate

- [ ] `ARCHITECTURE.md` and the affected specs describe the adapter registries; `docs/tests/` matrices updated.
- [ ] Decide whether the provider-family rule needs its own ADR.
- [ ] After merge, move this increment to `completed/` and reconcile the roadmap.
