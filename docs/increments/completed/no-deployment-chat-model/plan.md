# Plan: no deployment Chat model

Design: [design.md](design.md). Linear: [MEM-211](https://linear.app/memory-os/issue/MEM-211).

## Step 1: the catalog starts empty (this pull request)

- [x] `api`:
  - add `embabel-agent-byok-autoconfigure` (version from the `embabel` catalog entry);
  - set `embabel.models.default-llm: setup-required`;
  - remove `chatDeploymentModel` and the `memoryos.chat.provider.*` entries from `application.yaml` and the configuration metadata.
- [x] `core/ai`:
  - `ModelCatalogProvisioner` initializes the default row and the flow rows only;
  - remove `ModelCatalogService.Deployment`, the `deployment` marker in `ProviderCredentials` and its key property, and the four beans in `OpenAiProviderConfiguration`.
- [x] V140: delete the providers whose credential is `deployment`, with their models, after clearing the Chat default and the Agents that named one. `DeploymentChatProviderMigrationTest` proves that what an administrator added stays.
- [x] Tests:
  - `ChatSessionApiIntegrationTest` adds its fixture provider and default model itself, and its live checks build their model through the adapter;
  - remove `ChatModelCatalogConfigurationTest`;
  - update `ChatTenantProvisioningTest`, `ProviderCredentialsTest` and `ModelCatalogConstraintsTest`, and remove `OpenAiProviderConfigurationTest`;
  - every API `@SpringBootTest` now starts with no provider key.
- [x] Deployment: remove the API's `MEMORYOS_CHAT_API_KEY_FILE` mapping. The launcher exports any `*_FILE` it is given, so it needs no change. Keep `model_api_key` for embedding.
- [x] Docs: `docs/specs/chat-models.md`, `docs/specs/chat.md`, `docs/tests/chat.md` and ADR 0020, which supersedes ADR 0015's mention of the keys.
- [x] Gates:
  - `:core:test` and `:api:test` for the touched classes;
  - `ModulithArchitectureTest`;
  - the backend CI gate;
  - start the API locally with no provider key and confirm it serves `/api/chat/providers`.
- [x] Staging after deploy: the "OpenAI" provider and its models are gone, Chat answers on the 9Router default, and the minutes are written.

## Step 2: say "no model configured" (next pull request)

- [x] A distinct `CHAT_MODEL_NOT_CONFIGURED` (validation) when the Tenant has no Chat default and the turn or flow has no usable model of its own. A default whose provider cannot be used still answers `CHAT_PROVIDER_UNAVAILABLE`.
- [x] The Chat composer and the failed-minutes state name it. A model manager gets the link to the Models page in the minutes state.
- [x] The Models page shows an unset Tenant default, and an unset task row without a Chat default.
- [x] Owner request (2026-10-03): choosing a model in a default or task row saves it at once, with no Save button. The page header icon matches the navigation's (`Sparkles`).
- [x] UI screenshots approved by the owner (2026-10-03).

## Verification log

- **Step 1, PR #451** (merge `7b78ad58`):
  - Local: `DeploymentChatProviderMigrationTest`, `ChatTenantProvisioningTest`, `ProviderCredentialsTest`, `ModelCatalogConstraintsTest`, `ModulithArchitectureTest`, `CoreDependencyRulesTest`, and `OpenApiContractTest`, whose API context starts with no provider key.
  - CI: green, including `ChatSessionApiIntegrationTest` with its own catalog fixture.
  - CodeRabbit was rate-limited; the fallback is recorded on the PR.
- **Step 2, PR #462** (merge `55d7306d`):
  - Local: `ChatTenantProvisioningTest.anEmptyCatalogSaysNoModelIsConfiguredRatherThanAProviderFault`, and 129 web tests across models, meetings, chat runtime and i18n.
  - Lint, format, i18n audit, `tsc -b` and knip pass. CI is green.
  - The owner approved the UI. CodeRabbit was rate-limited; the fallback is recorded on the PR.
- **Staging, 2026-10-02/03:**
  - V140 applied at 17:19 UTC. Only the administrator-added 9Router provider remains (7 models), and the Tenant default and all four task models are `cx/gpt-6-luna`.
  - The API starts with no provider key, and its log has no model errors.
  - With the owner's admin login on `55d7306d`:
    - a Chat turn completed on the default model (`202`, then `COMPLETED`);
    - on the Models page, choosing a naming model saved at once (`PUT` `200`, "Đã lưu mô hình tác vụ.", no Save button), and choosing the previous model restored it.
  - A rerun of a meeting's minutes went `READY` on its first attempt in about 20 seconds.
- **Production:** `55d7306d` was deployed by a manual dispatch on 2026-10-02 18:09 UTC, which also ran V140 there.
