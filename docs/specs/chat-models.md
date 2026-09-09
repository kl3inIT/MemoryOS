# Chat provider/model catalog

The backend lives in `core.chat.catalog` and `chat.persistence.JdbcModelCatalogRepository`, with native provider composition and HTTP controllers in `api.chat`. The browser model selector and administration screens are not implemented. This contract extends [Chat](chat.md); the [reference design](../increments/active/mem-11-production-chat/provider-model-architecture.md) also describes later UI work.

## Configuration and access

Flyway V21 creates `llm_provider`, `model_configuration`, `chat_model_default`, provider Group/Persona associations and optional Persona model selections. Composite foreign keys enforce Tenant ownership. The deployment still has one Tenant. Model API names are unique within a provider; stable model configuration UUIDs distinguish identical names at different endpoints.

`MODELS_MANAGE` is a global IAM capability, grantable through ordinary Groups and implied by `IAM_ADMIN`. It does not follow from Source management or scoped Group management. Model administration revalidates authority under the IAM Tenant lock; writes take the exclusive lock. Expected revisions reject stale updates/deletes. Limits are 64 providers and 256 model configurations per Tenant.

Public providers bypass Group restrictions. Restricted providers require Group membership or model management. A nonempty Persona allowlist applies to everyone, including model managers. A restricted provider without matching Groups is manager-only. Disabled providers are unavailable; hidden models leave selection lists but retain their access policy. List and send use the same access predicate; selection lists additionally filter hidden models.

The Tenant Chat default must be visible, enabled, credential-ready, public and unrestricted by Persona. Hiding/deleting the default or disabling/restricting its provider requires choosing another default first. Deleting another model/provider clears affected Persona defaults; message history is preserved.

## Selection and lifetime

Send accepts optional `modelConfigurationId`: explicit selection → Persona default → Tenant Chat default. No model-name lookup or invented model tier participates. A missing or unauthorized selection falls back only to an authorized Tenant default; otherwise send fails before reserving a message. `202` returns actual `modelConfigurationId` and optional `SELECTION_UNAVAILABLE` without exposing inaccessible configuration details.

Request equality includes parent, text and the requested model ID. Repeating an accepted request returns its original message IDs and selection even after a catalog edit/deletion. The request and selected IDs are historical metadata without cascading foreign keys; configuration and credentials are not snapshotted in the transcript. The final outcome retains the actual model name and native accounting.

`ChatModelResolver` resolves authorized configuration before constructing clients, outside the catalog transaction. Each turn acquires a `ChatModelClients.Lease`; endpoint/key/model-option edits advance a revision and subsequent turns acquire a new client binding. Retired clients close after the last active lease; idle clients can be evicted for capacity. The cache counts both current and retired clients, defaults to 32, and rejects admission when all slots are occupied. Shutdown retires clients without closing resources still in use. A disconnected SSE reader does not release or cancel execution.

The binding supplies a native `SpringAiLlmService`, final-request policy, tokenizer and model context/output limits. Input is capped by both the deployment limit and model context window minus reserved output; output is capped by both deployment and model settings. Authorization is never cached with a binding.

## Credentials and provider extension

On first catalog access, the existing deployment OpenAI model initializes the catalog once. Its credential refers to `MEMORYOS_CHAT_API_KEY` or `SPRING_AI_OPENAI_API_KEY`. Admin catalog changes become authoritative; later reads do not overwrite them from deployment settings. The old Persona model string is retained for compatibility, not used to resolve a catalog selection by name.

Tenant BYOK uses explicit `KEEP`, `REPLACE`, `REMOVE`. Reads return `credentialConfigured`, never a key or ciphertext. Spring Security `AesGcmBytesEncryptor` encrypts credentials with a random IV and verifies integrity; encrypted content is bound to the Tenant/provider IDs. `MEMORYOS_CHAT_CATALOG_ENCRYPTION_KEY` is a base64-encoded random 32-byte key kept in Infisical. Missing encryption configuration disables BYOK writes/decryption, not deployment-reference credentials. Preserve this key with database backups; changing it without re-encrypting stored credentials makes them unreadable. No personal BYOK is implemented.

`ChatProviderAdapter` is the extension point. A bean declares its stable type and credential requirement, validates local configuration, then creates a native binding plus owned-client cleanup. OpenAI uses official synchronous/asynchronous SDK clients, explicit deadline, zero automatic retries, native Embabel options conversion and native observations/meters. The conservative completion-token option family omits sampling parameters; the standard family supports validated temperature/top-p/penalty settings. Reasoning effort is explicit. Unknown pricing stays unknown; a finite cost budget requires pricing on both the configuration and native service.

HTTP(S) endpoints are supported, including internal HTTP. URL credentials, query strings and fragments are rejected. Web search and image generation remain separately configured tools. Vision input capability does not mean image generation support. Neither tool is implemented by this catalog change.

## Backend API

| Endpoint | Contract |
| --- | --- |
| `GET /api/chat/models?sessionId=...` | Actor-authorized visible model list for the session or builtin Persona |
| `GET /api/chat/provider-adapters` | Installed adapter types/credential requirements; model manager |
| `GET/POST /api/chat/providers` | Redacted list / create provider |
| `PUT/DELETE /api/chat/providers/{id}?revision=...` | Full replacement / deletion with stale-write protection |
| `GET/POST /api/chat/providers/{id}/models` | Configured models / create model |
| `PUT/DELETE /api/chat/models/{id}?revision=...` | Replace / delete model configuration |
| `GET/PUT /api/chat/model-default` | Read / set Tenant Chat default using expected revision |
| `GET/PUT /api/chat/personas/{id}/model` | Read / set Persona default; omit model ID to inherit |
| `POST /api/chat/models/{id}/validate` | Explicit connectivity check, at most two concurrent checks, 15-second stream deadline and at most 32 output tokens; generic result without provider error payload |

All configuration/default/validation endpoints require `MODELS_MANAGE`. Validation does not certify streaming cancellation, tool behavior, capabilities or model quality. OpenAPI and the generated client are the exact HTTP schemas. [Adapter handoff](../increments/active/mem-77-provider-backend/adapter-handoff.md) lists the implementation and verification expected from a new provider.
