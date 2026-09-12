# MEM-77 — Backend provider/model foundation

Accepted scope: deliver the backend catalog and native adapter extension now. Đức Anh adds and verifies the local provider adapter on this foundation. No model selector or provider administration UI is included; regenerate the OpenAPI client only.

The [provider/model architecture](../mem-11-production-chat/provider-model-architecture.md) remains the reference contract. Onyx separates provider connections, model configurations and tool configuration. OrgMemory contributes the protocol factory boundary; its workload routing and cache implementation are not copied. Spring AI owns provider calls and Embabel owns conversion, streaming, tool continuation and accounting.

## Implementation

- `core.chat.catalog`: concrete catalog records, authorized application service and the public `ChatProviderAdapter` extension. SQL and mappings stay in `core.chat.persistence.JdbcModelCatalogRepository`.
- `api.chat`: native OpenAI adapter, credential/client composition and HTTP endpoints. The executor consumes an acquired native binding; it does not choose a provider or interpret provider options.
- JDBC stores Tenant-owned providers/models, access associations, the Chat default and an optional Persona model selection. Stable configuration IDs distinguish the same API model name at different endpoints. Transcript retains requested/selected IDs and the actual model name, never credentials or a configuration snapshot.
- A send resolves explicit selection → Persona selection → Tenant Chat default, after conversation ownership and catalog authorization. Unavailable selections may fall back only to an authorized default; the response identifies the actual selection and a generic fallback reason.
- Credentials are deployment references or encrypted Tenant BYOK. Admin writes explicitly keep, replace or remove credentials. Reads expose only configured state. HTTP endpoints on internal networks remain supported; URL credentials/query/fragment are rejected.
- Acquire a client binding per turn. Reuse unchanged bindings; a changed configuration produces a new binding. Retire superseded clients and close only after their last lease. Bound the cache and close on shutdown. Authorization is always read afresh and never cached with the client.
- Capabilities describe chat model input/output and options. Web search and image generation remain separately configured tools; this change does not implement them or advertise them as chat capabilities.

## Compatibility and limits

The deployment provider initializes the Tenant catalog once, preserving existing send callers without a model ID. Subsequent admin edits are authoritative. Existing in-memory setup, local Stop, RAM replay and HTTP/SSE contracts remain in place. No DB run snapshot, Redis, distributed cancellation or worker dispatch is introduced.

Only the existing OpenAI protocol adapter is shipped. Explicit adapter validation, native framework options and contract tests establish its supported configuration; registering another adapter does not certify every local model. Unknown pricing remains unknown, and a finite cost budget requires known pricing. Production behavior includes authorization, DB constraints, bounded resources, credential redaction, stale-write rejection and preserving partial failures.

## Review decisions

PR #88 preserves internal HTTP and private endpoints as an explicit trusted model-manager deployment policy; see the canonical [credential and endpoint contract](../../../specs/chat-models.md#credentials-and-provider-extension). Client initialization uses a per-entry future with reserved capacity, while provider construction and cleanup execute outside the shared cache monitor. Generated APIs distinguish validation results from Search results and describe all browser mutation headers.
