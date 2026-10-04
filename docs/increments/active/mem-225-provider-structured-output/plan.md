# Implementation plan

Design: [design.md](design.md). One pull request.

## Core

- [x] `ModelSettings.Capabilities.structuredOutput`; `V147` backfills `false` into saved settings documents.
- [x] `known-models.json` and `scripts/sync-chat-known-models.mjs`: `structuredOutput` from LiteLLM's
  `supports_response_schema`, at the pinned commit.
- [x] `ModelResolver`: the capability from the catalog; `false` for a model only the provider reports.
- [x] `OpenAiStructuredOutput` (the configurer) and `NativeSupport` on the `SpringAiLlmService` of
  `OpenAiProviderAdapter`.
- [x] `ResponsesRequestBuilder`: `ResponseFormat` to `text.format`.
- [x] `ModelBinding.structuredOutput()`; `ModelCalls` chooses `ENABLED` or `DISABLED`.
- [x] `GroundingClassifier`: the fixed answer type on a model with the capability; the lenient reader otherwise.

## API and web

- [x] `ChatModelCapabilitiesRequest`/`Response` and `ChatReportedModelsResponse.Capabilities`; `openapi.yml` and the
  Hey API client regenerated.
- [x] Models page: the checkbox and the summary label; `vi` copy keeps the English term.

## Tests

- [x] Wire tests for both routes; the settings document without the field; the question check on both branches.
- [x] `:core:test` for the changed classes, `OpenApiContractTest`, `pnpm --dir web check`; `clean check` is left to CI.

## Documentation

- [x] `docs/specs/chat-models.md`, `docs/specs/chat.md` (question check), `docs/tests/` matrices, roadmap row.
- [x] `verification.md` with the 9Router measurement.

## Carried over

- [ ] Staging: declare the capability on the task model and run one question check, one set of minutes and one
  correction. 9Router honours the schema for `cx/gpt-6-luna` only on the Responses route, so the model also needs
  `reasoningSummary` set to `auto`, or 9Router has to forward `response_format`.
- [ ] The `ocg/*` models through 9Router once their usage limit resets.
