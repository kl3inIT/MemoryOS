# No deployment Chat model

Status: designed 2026-10-02. Linear: [MEM-211](https://linear.app/memory-os/issue/MEM-211).

## Problem

When a Tenant is bootstrapped, `ModelCatalogProvisioner` inserts an "OpenAI" provider and makes its model the Chat default. The provider has `builtin_key = 'deployment'`, and its credential is the marker `deployment`. `ProviderCredentials` resolves that marker to `memoryos.chat.provider.api-key`, which is a server secret (`MEMORYOS_CHAT_API_KEY_FILE`) and never a key an administrator entered.

On staging, that key stopped working on 2026-10-01. Every model of the provider then answered 401: gpt-5.6-luna, gpt-6-sol, gpt-6-luna and gpt-6-astra. Administrators could not replace the key from the Models page, because the key lives in the server's secrets. On 2026-10-02 the meeting minutes failed for hours after the "Meeting minutes" task was pointed at one of those models.

## Reference

Onyx, main at `6b77733c5`, `backend/onyx/setup.py`:

- `setup_postgres` creates an OpenAI provider from `GEN_AI_API_KEY` only when no default model exists.
- `model_configs.py` marks that variable "should only be used for dev", and the shipped `env.template` leaves it commented out.
- A production install therefore starts with no provider, and an administrator adds one in the UI.
- Onyx does keep a default model that the administrator chooses (`update_default_provider`, `fetch_default_llm_model`).

## Decision

**The catalog starts empty.**
- Provisioning keeps only what is not a model: the Tenant's `chat_model_default` row, with no model, and one row per model flow.
- Every provider is added by an administrator, and its key is stored encrypted in the catalog.
- The administrator-chosen Chat default and the per-flow models stay exactly as they are.
- ADR 0002 forbids a dev-only seeding mode, so development and tests add their provider the same way an administrator does.

**The deployment provider configuration goes away.** This removes:
- the `memoryos.chat.provider.*` properties: `api-key`, `base-url`, the two prices, `max-completion-tokens`, `tool-calling`, `vision` and `reasoning`;
- their configuration metadata;
- the `deployment` credential marker in `ProviderCredentials`;
- `ModelCatalogService.Deployment` and the `chatDeploymentModel` bean;
- four OpenAI beans: `chatOpenAiClient`, `chatOpenAiSyncClient`, `chatProviderModel` and `chatLlmService`;
- the API's `MEMORYOS_CHAT_API_KEY_FILE` mapping.

The `model_api_key` secret stays, because embedding still reads it.

**Embabel starts without a model.** Embabel 1.5.2 refuses to start ("No models detected") unless at least one model service bean exists. Today `chatLlmService` is that bean.
- Embabel ships a placeholder for exactly this case: `embabel-agent-byok-autoconfigure` registers `SetupRequiredLlm` (`setup-required`), and the API sets `embabel.models.default-llm: setup-required`.
- Every MemoryOS call already passes its catalog service explicitly (`ModelCalls`, `ChatModelExecutor`). A call that reached the platform default would fail with Embabel's `NoLlmConfiguredException`, instead of quietly spending a deployment key.
- The module also registers per-key client factories that MemoryOS does not call. They are inert beans with no endpoint. Writing our own placeholder would duplicate `SetupRequiredLlm`.

**Existing seeded providers lose the marker, not the provider.**
- Migration V140 sets `credential = NULL` and `builtin_key = NULL` on every `llm_provider` whose credential is `deployment`.
- The provider, its models, the Chat default and the flow rows are kept, following [ADR 0018](../../../decisions/0018-schema-changes-preserve-data.md).
- The Models page then shows the provider without a key. The administrator enters one or deletes the provider.
- Selection already treats a provider without a usable credential as unusable, so a turn fails with the existing codes rather than calling with no key.

## Out of scope

- **A distinct "no model configured" code and screen.** Today an empty catalog fails as `CHAT_PROVIDER_UNAVAILABLE`, and the composer says the provider rejected the request or could not be reached. That copy is wrong for an empty catalog, but it is the same behaviour an unset deployment key has today. A distinct `CHAT_MODEL_NOT_CONFIGURED`, the Chat and minutes screens for it, and the Models page's unset default are step 2 in the plan, in their own pull request.
- **The embedding provider seeded as "Deployment".** It is a separate table with its own properties (`EmbeddingProviderCredentials`, `memoryos.search`).
- **`PersonaProperties.model` and the `persona.model` column.** They are still written at provisioning, but no turn reads them.

## Consequences

- A new environment answers no chat until an administrator adds a provider and picks a default.
- On staging after the deploy, the "OpenAI" provider has no key. Its models are already failing with 401, and the Tenant default and the meeting tasks point at 9Router models.
- `ChatSessionApiIntegrationTest` no longer gets a seeded, keyed default. Its tests add their fixture provider and default model themselves.
