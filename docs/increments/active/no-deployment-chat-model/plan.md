# Plan: no deployment Chat model

Design: [design.md](design.md). Linear: [MEM-211](https://linear.app/memory-os/issue/MEM-211).

## Step 1: the catalog starts empty (this pull request)

- [ ] `api`:
  - add `embabel-agent-byok-autoconfigure` (version from the `embabel` catalog entry);
  - set `embabel.models.default-llm: setup-required`;
  - remove `chatDeploymentModel` and the `memoryos.chat.provider.*` entries from `application.yaml` and the configuration metadata.
- [ ] `core/ai`:
  - `ModelCatalogProvisioner` initializes the default row and the flow rows only;
  - remove `ModelCatalogService.Deployment`, the `deployment` marker in `ProviderCredentials` and its key property, and the four beans in `OpenAiProviderConfiguration`.
- [ ] V140: clear `credential` and `builtin_key` where the credential is `deployment`. Add a migration test over a seeded row that proves the provider, its models, the default and the flows survive.
- [ ] Tests:
  - `ChatSessionApiIntegrationTest` adds its fixture provider and default model itself, and its model becomes a plain mock;
  - remove `ChatModelCatalogConfigurationTest`;
  - update `ChatTenantProvisioningTest`, `ProviderCredentialsTest`, `ModelCatalogConstraintsTest` and `OpenAiProviderConfigurationTest`;
  - add a test that the API context starts with no provider key.
- [ ] Deployment: remove the API's `MEMORYOS_CHAT_API_KEY_FILE` mapping and the launcher export. Keep `model_api_key` for embedding.
- [ ] Docs: `docs/specs/chat-models.md` (the deployment import), `docs/specs/chat.md`, `docs/tests/chat.md`, ADR 0015's mention, and a new ADR once implementation is under way.
- [ ] Gates:
  - `:core:test` and `:api:test` for the touched classes;
  - `ModulithArchitectureTest`;
  - the backend CI gate;
  - start the API locally with no provider key and confirm it serves `/api/chat/providers`.
- [ ] Staging after deploy: the "OpenAI" provider shows no key, Chat answers on the 9Router default, and the minutes are written.

## Step 2: say "no model configured" (next pull request)

- [ ] A distinct `CHAT_MODEL_NOT_CONFIGURED` (validation) when the Tenant has no usable default and the turn or flow has no model of its own.
- [ ] The Chat composer and the failed-minutes state name it. A model manager gets the link to the Models page.
- [ ] The Models page shows an unset Tenant default.
- [ ] UI screenshots for owner approval.

## Verification log

(Filled in as the steps land.)
