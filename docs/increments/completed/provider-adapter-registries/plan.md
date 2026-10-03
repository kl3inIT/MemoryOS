# Implementation plan

Each step is behavior-preserving. Delivered 2026-09-30 in PRs #401 and #402.

## 0. Convention and scope

- [x] Record the provider-family and naming rules in [conventions](../../../conventions.md#change-design) and point
  to them from the [backend guide](../../../guidelines/backend.md).
- [x] Owner go-ahead 2026-09-30; Linear [MEM-201](https://linear.app/memory-os/issue/MEM-201).
- [x] [ADR 0019](../../../decisions/0019-provider-families-use-adapters-behind-a-registry.md) narrows ADR 0015 for provider families.
- [x] Connectors planned in [connector adapters and Lark](../../active/mem-118-connector-adapters-lark/plan.md), after step 1.

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
- [x] `clean check` in CI (one PR for Web, Voice and Image, #402), after MEM-199's shared token counters (#405).

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

- [x] `ARCHITECTURE.md`, the Chat and chat-models specs and the Chat test matrix describe the adapter registries.
- [x] ADR 0019.
- [x] Increment moved to `completed/`; roadmap reconciled.
