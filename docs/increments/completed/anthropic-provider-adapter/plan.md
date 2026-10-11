# Plan — Anthropic provider adapter (MEM-242)

## Pull request 1 — the adapter

- [x] `spring-ai-anthropic` and `embabel-agent-anthropic` in the version catalog and `core`
- [x] `io.memoryos.ai.anthropic`: configuration, adapter, request policy, turn model, cancellation transport, failures
- [x] `KnownModels` to the `ai` root; `AiException.providerIncompatible` message
- [x] Web: the *Claude* preset on `anthropic`; the model editor's options by adapter type
- [x] `AnthropicProviderAdapterTest` on the wire; `model-catalog.test.ts`
- [x] `ModulithArchitectureTest`, `CoreDependencyRulesTest`, `OpenAiReportedModelsTest`, `OpenAiProviderAdapterTest`, `ModelClientsTest`
- [x] `docs/specs/chat-models.md`, `docs/tests/chat.md`, `ARCHITECTURE.md`, roadmap
- [x] `clean check` and `pnpm --dir web check` in CI (PR #572)

## After the merge

- [x] Staging, with the owner's key: a connection from the *Claude* preset, the model listing, a turn with tools, a turn with a reasoning level, a stopped turn ([verification](verification.md))
- [x] Record the result in `verification.md`; move the increment to `completed/` and reconcile the roadmap

## Pull request 2 — what the staging run found

- [x] A stop is not a stream failure: Spring AI logged two errors with a stack trace for every stopped Claude turn
- [x] `known-models.json` from the LiteLLM commit that lists `claude-haiku-5-5` and `claude-sonnet-5-5`, so their price is prefilled and their usage priced

## Candidates, each its own change

- Prompt-cache control with a cache-write price
- A Claude tokenizer profile measured against the token counting endpoint
- `thinkingBudget` in the model editor
- The `openai` adapter refuses `api.anthropic.com`
