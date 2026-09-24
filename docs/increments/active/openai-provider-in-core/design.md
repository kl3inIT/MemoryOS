# OpenAI provider in core

## Requirement

Owner decision 2026-09-24 ([audit owner decisions](../audit-quality-fixes/owner-decisions.md), item 1.2): the OpenAI Chat provider (~1,600 lines) sat in the `api` composition root, against the rule that `api` and `worker` define no capability-owned behaviour ([conventions](../../../conventions.md)). It moves into `core`. Behaviour, `memoryos.chat.provider.*` property keys, bean names and the published HTTP contract (`openapi.yml`) stay unchanged.

## Design

The provider moves as-is into `io.memoryos.chat.catalog.openai`, next to the `ChatProviderAdapter` extension point it implements. The package is a `@NamedInterface("openai")` of the closed `chat` module. No new Modulith module is added.

| Moved from `api/.../api/chat` | Role |
| --- | --- |
| `OpenAiChatProviderAdapter` | Catalog adapter: option validation, `/models` discovery, client creation, Embabel binding |
| `OpenAiResponsesChatModel`, `ChatCompletionsReasoning`, `OpenAiReasoningFallback`, `OpenAiChatRequestPolicy` | Streaming routes, reasoning publication, effort fallback, native request policy |
| `OpenAiCancellation` | OkHttp transport that cancels the raw call before closing a blocked reader |
| `LocalModelMetadata`, `ChatKnownModels` (+ `chat/known-models.json`), `ChatTokenizerProfiles` | Ollama/LM Studio metadata, the installed model list, the hosted O200K profile |
| `ChatModelValidation` | Validate probe for a configured model |
| `OpenAiChatProviderConfiguration` | Deployment clients and the Embabel default service. It holds endpoint and credential checks, and core already hosts `@Configuration` classes such as `S3ObjectStorageConfiguration` |

`api` keeps `ChatModelCatalogConfiguration` (catalog wiring and the `openAiChatProviderAdapter` bean) and the controllers. The worker scans no `io.memoryos.chat` package, so it gains no beans. Types that `api` code or its tests still use became public; everything else stays package-private. The unit tests moved with their classes. `ChatSessionApiIntegrationTest` and the live research probes stay in `api`.

`core` now declares `okhttp`, which `OpenAiCancellation` uses directly; `api` no longer declares it. Before the move, `OpenAiCancellation` compiled against OkHttp 5.4.0 in `api`. It now compiles against 4.12.0 (from openai-java) in `core`, and it still runs on 5.4.0 in the API because OpenTelemetry pulls that version in. The OkHttp calls it makes behave the same in both versions.

## Out of scope

The other items listed with 1.2 remain follow-ups: `GoogleDriveAccountClient`/`GoogleDriveOAuthProperties`, admission in `ActorSessionLoginSuccessHandler`, and orchestration in `MeetingController.minutes/publish`. Letting the worker run meeting minutes or transcription needs its own design.
