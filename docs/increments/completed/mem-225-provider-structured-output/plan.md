# Implementation plan

Design: [design.md](design.md). One pull request.

## Core

- [x] `ModelSettings.Capabilities.structuredOutput`; `V147` backfills `false` into saved settings documents.
- [x] `known-models.json` and `scripts/sync-chat-known-models.mjs`: `structuredOutput` from LiteLLM's
  `supports_response_schema`, at the pinned commit.
- [x] `ModelResolver`: the capability from the catalog; `false` for a model only the provider reports.
- [x] `OutputSchemas.strict`: the schema of an answer type, every property required, an optional one nullable.
- [x] `ModelCalls` carries the schema on `LlmOptions` for a typed call on a declared model; the OpenAI options
  converter sets it as Spring AI's `outputSchema`.
- [x] `ResponsesRequestBuilder`: the output schema to `text.format`.
- [x] `ModelBinding.structuredOutput`.
- [x] The first version went through Embabel's native path and sent nothing (see the design); removed.
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

## Staging

- [x] The capability declared on the task model (`cx/gpt-6-luna`); one correction and one set of minutes on a
  self-written transcript (2026-10-04, see the verification).

## Not done, by the owner's decision

- The question check on a language model: staging runs it on a System One connection.
- The `ocg/*` models through 9Router, whose usage limit was exhausted. The owner accepted the `cx` route as
  the acceptance on 2026-10-04.
