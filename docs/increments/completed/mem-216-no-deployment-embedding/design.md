# No deployment embedding provider (MEM-216)

Status: in progress 2026-10-03. Linear: [MEM-216](https://linear.app/memory-os/issue/MEM-216). Follows [no deployment Chat model](../../completed/no-deployment-chat-model/design.md) (MEM-211).

## Problem

The first start of a deployment seeds the PRESENT search generation from deployment configuration. The seeded provider:
- is named `Deployment` and defaults to OpenAI `text-embedding-3-large` (3072, External);
- keeps no key of its own, only the `deployment` reference, so every call reads `MEMORYOS_EMBEDDING_API_KEY` from the `model_api_key` secret the api and the worker still mount.

MEM-211 removed exactly this shape from Chat: a provider the deployment owns, with a key no administrator entered.

On 2026-10-03, staging and production both serve PRESENT from `serving-embedding`: Qwen/Qwen3-Embedding-4B, 2560 dimensions, Internal, with a sealed key. In both, the `Deployment` provider is used by no generation.

An administrator can already replace any provider's key on the Search settings page, so a dead deployment key is recoverable. This change is cleanup and a better default, not a fix for an outage.

## Reference

- Onyx defaults to a self-hosted embedding model (`nomic-ai/nomic-embed-text-v1` on its model server) and keeps cloud keys in the database; `GEN_AI_API_KEY` is a development convenience.
- MemoryOS's self-hosted equivalent is the serving node: `compose.serving.yaml` runs Text Embeddings Inference with Qwen3-Embedding-4B behind a key generated on that node.

## Decision

**Every embedding key is a catalog entry.**
- `EmbeddingProviderCredentials` loses the `deployment` reference.
- `memoryos.search.api-key` (`MEMORYOS_EMBEDDING_API_KEY`) is gone, together with its `SPRING_AI_OPENAI_API_KEY` fallback.
- The api and the worker no longer mount `model_api_key`. Nothing else reads it since MEM-211.

**A new deployment seeds Qwen on its serving node.**
- The seed defaults to Qwen/Qwen3-Embedding-4B with 2560 dimensions.
- The query and document prefixes default to empty, as staging and production run. Whether the instruct prefix of the preset serves Vietnamese questions better is a rebuild decision, outside this change.
- `MEMORYOS_EMBEDDING_ENDPOINT` has no default: only the deployment knows where its serving node is.
- The seeded provider is named `Serving` and marked Internal, because the seed describes the deployment's own serving node. An operator who seeds from a cloud endpoint retags it on the page.
- The provider is seeded without a key, so it sends the no-key bearer, as a provider saved without a key does. The administrator enters the serving node's key on the Search settings page, which already offers Replace key.

**A missing endpoint fails closed.**
- An empty endpoint is valid configuration.
- When a generation must be seeded or served unsaved, an empty endpoint makes search unavailable (`SearchUnavailableException`) and logs `search.generation.unconfigured` once at ERROR, naming `MEMORYOS_EMBEDDING_ENDPOINT`.
- The process keeps running: the startup index check already treats an unavailable search as a warning. Every later call tries again, so setting the endpoint and restarting recovers.
- Seeded deployments never read it.

**V144 removes what is left.**
- It deletes every provider holding the `deployment` reference that no generation uses. That removes the `Deployment` row on staging and production.
- A provider still used by a generation, such as a development database seeded against OpenAI, keeps its row but loses the reference. The page then shows it without a key, and the developer enters one. Failing the migration instead would stop local startups for no production benefit.

## Limits

- The legacy index identity hashes the configured endpoint, model and dimensions. Only seeding and the unsaved generation read it, so seeded deployments are unaffected. A deployment from before V125 that never started since would compute a different identity and seed a new index; none exists.

## Out of scope

- The instruct prefix for Qwen on staging and production: a FUTURE rebuild, chosen on the page.
- Bundling an embedding server into the application stack.
