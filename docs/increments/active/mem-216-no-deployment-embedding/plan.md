# Plan: no deployment embedding provider

Design: [design.md](design.md). Linear: [MEM-216](https://linear.app/memory-os/issue/MEM-216).

- [x] `EmbeddingProviderCredentials`: drop the `deployment` reference and the deployment key.
- [x] `SearchProperties` and `memoryos-search.yaml`:
  - drop `api-key`;
  - the endpoint has no default and is validated only when set;
  - the model defaults to Qwen/Qwen3-Embedding-4B with 2560 dimensions.
- [x] `SearchGenerations`:
  - seed `Serving`, Internal, without a key;
  - an unset endpoint is `search.generation.unconfigured` and makes search unavailable, for both the seed and the unsaved generation.
- [x] `OpenAiCompatibleEmbeddings`: drop the blank-key branch only the deployment key reached.
- [x] V144: delete unused `deployment` providers; clear the reference on a used one.
- [x] Compose:
  - drop `MEMORYOS_EMBEDDING_API_KEY_FILE` and the `model_api_key` mount from api and worker, and the top-level secret;
  - pass `MEMORYOS_EMBEDDING_ENDPOINT` through, optional (`:-`, never `:?`, so a server without it still starts). Model and dimensions are not passed: an empty value would replace their defaults;
  - update the secret-source test.
- [x] Tests:
  - credentials;
  - the V144 migration;
  - seeding (Qwen, Internal, no key) and an unset endpoint;
  - existing users of `SearchProperties`.
- [x] Docs: `docs/specs/search.md`, `docs/runbooks/search-runtime.md` (first install), the development runbook, the test matrix, roadmap.
- [ ] CI; merge; staging: V144 applied, the Search settings page lists only `serving-embedding`, a search answers.
