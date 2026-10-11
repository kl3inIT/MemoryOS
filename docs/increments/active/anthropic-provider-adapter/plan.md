# Plan — Anthropic provider adapter (MEM-242)

## Pull request 1 — the adapter

- [x] `spring-ai-anthropic` and `embabel-agent-anthropic` in the version catalog and `core`
- [x] `io.memoryos.ai.anthropic`: configuration, adapter, request policy, turn model, cancellation transport, failures
- [x] `KnownModels` to the `ai` root; `AiException.providerIncompatible` message
- [x] Web: the *Claude* preset on `anthropic`; the model editor's options by adapter type
- [x] `AnthropicProviderAdapterTest` on the wire; `model-catalog.test.ts`
- [x] `ModulithArchitectureTest`, `CoreDependencyRulesTest`, `OpenAiReportedModelsTest`, `OpenAiProviderAdapterTest`, `ModelClientsTest`
- [x] `docs/specs/chat-models.md`, `docs/tests/chat.md`, `ARCHITECTURE.md`, roadmap
- [ ] `clean check` and `pnpm --dir web check` in CI

## After the merge

- [ ] Staging, with the owner's approval of the key and the spend: a connection from the *Claude* preset, the model listing, a turn with tools, a turn with a reasoning level, a stopped turn
- [ ] Record the result in `verification.md`; move the increment to `completed/` and reconcile the roadmap

## Candidates, each its own change

- Prompt-cache control with a cache-write price
- A Claude tokenizer profile measured against the token counting endpoint
- `thinkingBudget` in the model editor
- The `openai` adapter refuses `api.anthropic.com`
