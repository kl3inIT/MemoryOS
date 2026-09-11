# Chat provider/model catalog

The backend lives in `core.chat.catalog` and `chat.persistence.ModelCatalogRepository` with Spring Data JPA lifecycle repositories, native provider composition and HTTP controllers in `api.chat`. `/admin/models` implements provider/model administration and Tenant/Persona defaults. Main's MEM-11 Chat workspace supplies its session-aware model picker and private assistant editor; Access administration remains outside this slice. This contract extends [Chat](chat.md); [MEM-77 verification](../increments/active/mem-77-provider-backend/verification.md) distinguishes implemented source, historical local checks and current merged/runtime acceptance.

## Configuration and access

Flyway V33 creates `llm_provider`, `model_configuration`, `chat_model_default`, provider Group/Persona associations and optional Persona model selections. Composite foreign keys enforce Tenant ownership. The deployment still has one Tenant. Model API names are unique within a provider; stable model configuration UUIDs distinguish identical names at different endpoints.

`MODELS_MANAGE` is a global IAM capability, grantable through ordinary Groups and implied by `IAM_ADMIN`. It does not follow from Source management or scoped Group management. Model administration revalidates authority under the IAM Tenant lock; writes take the exclusive lock. Expected revisions reject stale updates/deletes. Limits are 64 providers and 256 model configurations per Tenant.

Models navigation uses `MODELS_MANAGE`, independently of Tenant administration. A Models-only manager can enter from either administration entry; Sources → Groups → Users retain precedence, with Models as fallback. Denied routes mount no protected catalog queries, and authority changes purge private query/mutation state.

Public providers bypass Group restrictions. Restricted providers require Group membership or model management. A nonempty Persona allowlist applies to everyone, including model managers. A restricted provider without matching Groups is manager-only. Disabled providers are unavailable; hidden models leave selection lists but retain their access policy. List and send use the same access predicate; selection lists additionally filter hidden models.

The Tenant Chat default must be visible, enabled, credential-ready, public and unrestricted by Persona. Hiding/deleting the default or disabling/restricting its provider requires choosing another default first. Deleting another model/provider clears affected Persona defaults; message history is preserved.

## Selection and lifetime

Send accepts optional `modelConfigurationId`: explicit selection → Persona default → Tenant Chat default. No model-name lookup or invented model tier participates. A missing or unauthorized selection falls back only to an authorized Tenant default; otherwise send fails before reserving a message. `202` returns actual `modelConfigurationId` and optional `SELECTION_UNAVAILABLE` without exposing inaccessible configuration details.

Request equality includes parent, text and the requested model ID. Repeating an accepted request returns its original message IDs and selection even after a catalog edit/deletion. The request and selected IDs are historical metadata without cascading foreign keys; configuration and credentials are not snapshotted in the transcript. The final outcome retains the actual model name and native accounting.

`ChatModelResolver` resolves authorized configuration before constructing clients, outside the catalog transaction. Each turn acquires a `ChatModelClients.Lease`; endpoint/key/model-option edits advance a revision and subsequent turns acquire a new client binding. Retired clients close after the last active lease; idle clients can be evicted for capacity. The cache counts both current and retired clients, defaults to 32, and rejects admission when all slots are occupied. Shutdown retires clients without closing resources still in use. A disconnected SSE reader does not release or cancel execution.

The binding supplies a native `SpringAiLlmService`, final-request policy, tokenizer and model context/output limits. Input is capped by both the deployment limit and model context window minus reserved output; output is capped by both deployment and model settings. Authorization is never cached with a binding.

`ModelSettings.tokenizerProfile` is required. The installed `openai` adapter exposes `openai-o200k-v1` and `smollm2-135m-12fd25f-v1` through required `tokenizerProfiles: [{id, displayName}]`. Unknown/incompatible profiles fail local catalog validation without loading native assets inside a transaction. V37 backfills legacy settings to O200K without changing configuration identity, revisions, associations or transcript history; main's V34–V36 are preserved. There is no missing-field alias, model-name tokenizer inference or local O200K fallback.

One immutable binding policy counts mandatory instructions/question before reservation, selects bounded newest-first history and checks every native request after framework conversion, including Validate. SmolLM2 counts the pinned template's role markers and generation prefix, not raw text alone; O200K retains the hosted allowance rather than claiming knowledge of a hosted server template. Output reservation, every-call capability enforcement and independent last-cycle tools-off remain separate. Unsupported text-only media/tool messages are rejected; unknown provider usage/pricing is never filled from estimates. See [Chat execution](chat.md#execution-lifecycle).

## Credentials and provider extension

On first catalog access, the existing deployment OpenAI model initializes the catalog once. Its credential refers to `MEMORYOS_CHAT_API_KEY` or `SPRING_AI_OPENAI_API_KEY`. Admin catalog changes become authoritative; later reads do not overwrite them from deployment settings. The old Persona model string is retained for compatibility, not used to resolve a catalog selection by name.

Tenant BYOK uses explicit `KEEP`, `REPLACE`, `REMOVE`. Reads return `credentialConfigured`, never a key or ciphertext. Spring Security `AesGcmBytesEncryptor` encrypts credentials with a random IV and verifies integrity; encrypted content is bound to the Tenant/provider IDs. `MEMORYOS_CHAT_CATALOG_ENCRYPTION_KEY` is a base64-encoded random 32-byte key kept in Infisical. Missing encryption configuration disables BYOK writes/decryption, not deployment-reference credentials. Preserve this key with database backups; changing it without re-encrypting stored credentials makes them unreadable. No personal BYOK is implemented.

`ChatProviderAdapter` is the extension point. A bean declares its stable type and credential requirement, validates local configuration, then creates a native binding plus owned-client cleanup. OpenAI uses official synchronous/asynchronous SDK clients, explicit deadline, zero automatic retries, native Embabel options conversion and native observations/meters. The conservative completion-token option family omits sampling parameters; the standard family supports validated temperature/top-p/penalty settings. Reasoning effort is explicit. Helper operations request native `withoutThinking()` on the same selected binding. After native conversion, the OpenAI adapter overrides only helpers with `helperReasoningEffort` (`none`, `minimal`, or `low`); the default is `minimal` for the verified GPT-5 mini baseline. Managers must select a supported minimum for another reasoning model: `none` is not supported universally. Non-reasoning bindings receive no reasoning override. Answer `reasoningEffort` and output settings are unchanged; this does not route helpers to a smaller model. Unknown pricing stays unknown; a finite cost budget requires pricing on both the configuration and native service.

HTTP(S) endpoints are supported, including internal HTTP and private/loopback hosts. This is an accepted deployment policy: `MODELS_MANAGE` is trusted authority to configure outbound provider endpoints and use their credentials, not a permission for untrusted end users. HTTP can carry the configured credential in cleartext; operators choose HTTP for their trusted internal network and HTTPS across untrusted networks. Application code does not enforce a host allowlist; deployment network policy owns egress restrictions. URL credentials, query strings and fragments are rejected. Do not replace this accepted policy with HTTPS-only or private-host rejection during review. Authorization failures, credentials exposed in responses/logs, or calls to a destination other than the configured endpoint remain defects.

The deployment import accepts explicit `memoryos.chat.provider.max-completion-tokens`, `tool-calling`, `vision` and `reasoning` booleans. Unset properties retain the legacy GPT-5 defaults; explicit values override them for differently named deployments. These settings initialize the catalog once and supply platform-default metadata; they do not overwrite subsequent admin catalog edits. A finite deployment cost budget with unknown deployment pricing fails at startup, while selected catalog models are also checked per turn.

Web search and image generation remain separately configured tools. Vision input capability does not mean image generation support. Neither tool is implemented by this catalog change.

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
| `GET/PUT /api/chat/personas/{id}/model` | Read / set a nondeleted builtin or current actor-owned Persona default; omit model ID to inherit |
| `GET /api/chat/model-personas?cursor=...&limit=...` | Model-manager-only, Tenant-scoped ID/name projection of nondeleted builtin/current actor-owned Personas; default 25/max 100, ascending UUID keyset, `limit+1`, required nullable `nextCursor`; invalid, foreign, private, deleted or missing cursor anchors rejected |
| `POST /api/chat/models/{id}/validate` | Explicit connectivity check, at most two concurrent checks, 15-second stream deadline and at most 32 output tokens; generic result without provider error payload |

All configuration/default/validation endpoints require `MODELS_MANAGE`. Validation does not certify streaming cancellation, tool behavior, capabilities or model quality. OpenAPI and the generated client are the exact HTTP schemas. [Adapter handoff](../increments/active/mem-77-provider-backend/adapter-handoff.md) lists the implementation and verification expected from a new provider.

The manager projection (`listChatModelPersonas`) provisions the builtin only after authorization and does not reset its saved selection/revision. It does not replace MEM-11's `/api/chat/personas` / `listChatPersonas` assistant list or grant access to another actor's private assistant. UUIDs, not display names, identify Persona/default choices. Catalog OpenAPI required/nullable fields are generated from source; required request booleans describe Jackson 3's existing rejection of missing primitive fields, not new unrelated request strictness.

## Models administration and defaults

Management reads retain hidden models and disabled providers. Provider edits preserve one coherent revision and exact stored Access snapshot; new providers are manager-only (`isPublic=false`, empty Group/Persona lists). No implicit public promotion or new grants accompany creation/default selection. Conflicts retain only non-secret drafts and require reconciliation before manual retry.

KEEP/REPLACE/REMOVE use transient secret refs and direct generated SDK calls, outside React Query variables/data/errors. Secrets clear on consumption, settlement, close and session/authority changes. Late completions are ignored. Validate applies only to clean saved state; both provider/model revisions are refetched and must still match before showing its result. A false result is distinct from an HTTP failure and is not permanent health certification.

Model forms expose installed profiles, bounded context/output, supported options and nullable paired pricing. The local profile uses 1,024 context/128 output and text-only capabilities; this is the candidate configuration, not completed target capacity acceptance. Switching option families removes incompatible sampling/reasoning fields. Tenant default candidates follow the server eligibility predicate (public providers can retain Group associations); manager-only local providers are excluded. Persona choices respect its allowlist, retain a saved hidden choice and provide explicit Inherit. Deletion refetches affected selections/revisions, including the delete/refetch race, without erasing transcript history.
