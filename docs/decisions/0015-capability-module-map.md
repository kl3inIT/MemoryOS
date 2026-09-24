# 15. Capabilities are split by reason to change, and the deployables use only published APIs

Date: 2026-09-25

## Status

Accepted; implementation started 2026-09-25 with step 0 below. Supersedes the module list of [ADR 0001](0001-controlled-modular-monolith.md) (`identity`, `authorization`, `knowledge`, `assistant`, `audit`), which no longer describes the code; ADR 0001's three Gradle modules and closed Spring Modulith modules stand.

## Context

`core` has grown ten closed modules, and several boundaries now follow history rather than ownership:

- `meeting` depends on `chat :: voice` and `chat :: summary`. It needs transcription and one-shot model calls, not Chat. Because it may not depend on the Chat file library, `MeetingController` in `api` imports `ChatLibraryService` and `ChatFileService` and publishes minutes to the library itself: business orchestration sits in a controller.
- `ingestion` depends on `chat` only for `UserFileWork` and `UserFileWorkPort`, the extraction work of library files.
- `iam` exposes all seven of its subpackages as named interfaces, and every other module depends on `iam :: *`, so nothing in IAM is internal.
- The published IAM packages carry a dozen interface/`Default*` pairs with one implementation each (for example `GroupService`/`DefaultGroupService`, `IamAuthorization`/`DefaultIamAuthorization`), which the [persistence policy](../guidelines/persistence.md) already calls unwanted.
- `audit` lives inside `iam` (`iam.audit`) but is written by `chat`, `connector`, `mcp` and `usage`; it is a separate, append-only capability with its own retention and reader ([ADR 0013](0013-server-authored-audit-evidence.md)).
- `api` and `worker` import eleven `persistence` classes of core modules (model catalog, agents, Chat sessions, exports, archives, prompt shortcuts, history, usage reports, AI costs, Search work, the JIT allowlist), and more reach them through return types. Some become HTTP bodies unchanged: `JdbcAgentRepository.AgentRef`, `AiCostQueries.Split`, `JdbcPromptShortcutRepository.PromptShortcut` are OpenAPI schemas today.
- Spring Modulith and `CoreDependencyRulesTest` (ArchUnit, persistence ownership) verify `core` only. Nothing checks what the deployables import.

## Decision

### Module map

Four application modules are extracted inside `core`; there is no Gradle split.

| Module | Owns | Comes from |
| --- | --- | --- |
| `ai` | Provider/model catalog, provider adapters including OpenAI, model flows, one-shot LLM invocation such as transcript summary and correction | `chat.catalog`, `chat.summary` |
| `voice` | Voice connections, batch and realtime transcription, synthesis | `chat.voice` |
| `library` | User files, the file library, trash, quota, thumbnails, copies | `chat` root and `chat.persistence` |
| `audit` | The append-only audit stream, its reader and export | `iam.audit` |

`iam` keeps Tenant, identity, users, Groups, invitations and identity providers. `chat` keeps sessions, turns, streaming and branches, personas/agents, projects, prompts, research, tools, Web search, images, the interpreter and history. `connector` stays one module with provider subpackages ([ADR 0006](0006-shared-connector-bundle-and-jdbc-source-persistence.md)).

Dependency direction afterwards: `meeting → ai, voice, library` (not `chat`); `chat → ai, voice, library`; `ingestion → library` (not `chat`). Every module that records administrative changes depends on `audit`.

### Shared kernel

`TenantId` and `ActorId` move from `iam.tenant` and `iam.identity` to `io.memoryos.shared`, a module of identifier types that every module may depend on. Before the move every module depended on `iam :: *`, most of them for these two records alone, and `audit` could not name them without a cycle with `iam`.

Mechanism: `shared` is an ordinary closed Spring Modulith module, `@ApplicationModule(type = CLOSED, allowedDependencies = {})`, with its types in the root package, and every module lists `"shared"` in its `allowedDependencies`. The alternatives do not fit. `@Modulithic(sharedModules = …)` only adds a module to every `@ApplicationModuleTest` bootstrap; it grants no dependency and `shared` has no beans to bootstrap. `Type.OPEN` exposes internals and removes the module from cycle detection, and the one property worth proving about a kernel is that it is a leaf that depends on nothing. Leaving the types unassigned in `io.memoryos`, as `BusinessException` and the `Failure*` types are, would hide the dependency from the module model instead of declaring it. `ModulithArchitectureTest` therefore still requires every module, `shared` included, to be closed.

What belongs there is a pure identifier or value that several modules carry, with no behaviour and no dependency on a module's services. The inventory, by importing modules outside `iam`:

| Type | Used by | Decision |
| --- | --- | --- |
| `TenantId` | all ten other modules (`connector` 54 files, `chat` 39, `document` 11, `objectstorage` 9, `retrieval` 4, `ingestion` 3, `usage` 2, `mcp` 1, `meeting` 1, and `audit` as a UUID) | Included. The partition key of every row; `document`, `objectstorage` and `ingestion` needed IAM for nothing else. |
| `ActorId` | `chat` 55, `connector` 34, `mcp` 5, `retrieval` 4, `meeting` 4, `usage` 3, and `audit` as a UUID | Included. Who is asking, carried by every command and the signed-in principal. |
| `GroupId` | `connector` 11, `mcp` 1 | Excluded. It names an IAM-owned aggregate, and both consumers also call `IamAuthorization`, `GroupScopeService` and `GroupIdentity` from `iam.group`, so moving it would remove no dependency. |
| `IamCapability`, `Authority`, `IamAccess` | always used together with `IamAuthorization` | Excluded. They are the vocabulary of IAM's authorization decision, not free-standing values. |
| `TenantAccessResolver`, `TenantMembership` | `chat`, `retrieval`, `connector` | Excluded. Behaviour and IAM state, not identifiers. |
| `SourceId`, `CredentialId`, `DocumentId`, `StoredObjectId`, `ObjectUploadId` and similar | the owning module and its declared consumers | Excluded. Each is owned by one module and reaches others through that module's dependency. |

Dependencies after the move: `audit → shared`; `iam → shared, audit`; `objectstorage → shared`; `document → shared, objectstorage`; `ingestion` no longer depends on IAM; `chat → iam :: tenant, iam :: group, iam :: identity`; `connector` and `retrieval → iam :: tenant, iam :: group`; `mcp`, `meeting` and `usage → iam :: group`. No `iam :: *` remains. The IAM named interfaces are the ones consumers call today; step 4 collapses them into IAM's root API.

`audit` takes `TenantId` and `ActorId` again in `AuditRecord`, `AuditTrail.person`, `AuditReaders` and the `AuditLog` readers, query and events; its rows and the HTTP contract stay on UUIDs, so the OpenAPI document does not change.

The signed-in principal (`IdentityContext`, holding an `ActorId`) is stored Java-serialized in the JDBC session. `V128__clear_sessions_after_actor_id_move.sql` deletes the sessions, as `V14` did for the previous move of `ActorId`: every user signs in once more after the deployment, and a mixed-version API rollout is not supported.

### Layout inside a module

- The module root package is the published API: services, identifiers, views, events and exceptions.
- Feature subpackages are internal implementation, each with its own `persistence` package.
- `@NamedInterface` is used only for a deliberate extra surface, and no module depends on another with `module :: *`.
- An interface stays only when it is the published API and its implementation is internal. Otherwise an interface/`Default*` pair collapses into one class.
- `api` and `worker` use only the published API of modules, never a `persistence` package, and do not return core types as HTTP bodies; `api` maps them to its own `contract/` records ([conventions](../conventions.md)). Modulith and `CoreDependencyRulesTest` keep verifying `core`; the owner chose not to extend ArchUnit to the deployables, so their boundary is held by review.

### Order

One pull request, in this order, each step compiling and passing its targeted tests:

0. `api` and `worker` stop reading `persistence`; OpenAPI schema names may change.
1. Extract `audit`; then introduce the `shared` kernel, replace every `iam :: *` dependency, and give `audit` typed identifiers.
2. Extract `ai` and `voice`; move meeting orchestration out of `MeetingController` into `meeting`.
3. Extract `library`; `ingestion` depends on `library`.
4. Internal layout of `chat`, `connector` and `iam`; drop `:: *` dependencies and collapse single-implementation pairs.
5. Web feature folders mirror the modules.

### Step 2: what `ai` and `voice` hold

`ai`'s root is its published API: `ModelCatalogService` and the catalog records, the provider adapter contract (`ChatProviderAdapter`, `ChatProviderAdapters`), `ProviderCredentials`, `ChatModelResolver` and `ChatModelClients`, the native binding and its request policy (`ChatModelBinding`, `ChatRequestPolicy`, `ChatSampling`, `ReasoningEffort`), admission and accounting (`ModelGuard`, `ChatAdmissionLedger`, `ModelAccounting`), `ChatModelTurns`, `ChatModelValidation`, `ModelCalls` and the transcript calls (`TranscriptSummarizer`, `TranscriptCorrector`, `TranscriptSummary`, `TranscriptCorrections`), and `AiException`. The OpenAI adapter is internal (`ai.openai`, no named interface; it registers its own adapter bean), as are the catalog's JPA and JDBC persistence (`ai.persistence`) and the package-private `ModelCatalogProvisioner`. `voice`'s root is the connection, transcription and synthesis services and their values; the provider clients (Azure, ElevenLabs, OpenAI and Soniox realtime, the chunked transcribers) stay package-private in the root rather than moving to a subpackage, which would have made them public. Its JPA persistence is `voice.persistence`.

Judgement calls made while moving:

- **Model runtime in `ai`, turn policy in `chat`.** A single model call outside a conversation needs the binding, the request policy, client leases, budget admission and usage accounting, so those moved to `ai`. The guard's Chat part — Chat's prompt guidance per inference and the Search helper check — stays in `chat` as `ChatModelGuard extends ModelGuard`; `ModelCalls` runs on the plain `ModelGuard`.
- **Which models a member may use is split by what it is about.** The catalog rules (provider access by Group, by agent restriction, visibility, the Tenant default, flow eligibility, native Web search support) are `ai`'s and take an agent as an id. Everything keyed by a conversation or an agent row is `chat`'s: `ChatModelAccess` pages agents for the provider form, reads and sets an agent's model, lists the models for a session or an agent, and selects a turn's model; `ChatModelSelector` then acquires the client from `ai` outside the transaction. The agent and personal-default SQL moved to `chat.persistence.JdbcAgentModelRepository`. Tenant provisioning splits the same way: `ai`'s `ModelCatalogProvisioner` seeds the catalog and `chat`'s `ChatTenantProvisioner` the default agent, both on `TenantBootstrapped` in the bootstrap transaction; they need no order, because the default agent names its model as text, not by catalog id.
- **The catalog reaches agents through a port and an event.** A provider's agent restriction must name agents of the Tenant: `ai` asks the `AgentDirectory` port, which `chat` implements. Deleting a model or provider publishes `ModelsRemoved` synchronously in the same transaction, before the rows go, and `chat` clears the agents that named one — the delete order the single repository used to run. The catalog still reads `iam_groups` and `iam_group_memberships` by SQL, as it did inside `chat`; step 4 replaces it with IAM's API.
- **Provider activity is provider-neutral.** A hosted model reports reasoning, Web search steps and cited pages to a `ChatModelTurns.Listener`; `chat`'s `ChatTurnListener` turns them into Chat events and turn evidence. The OpenAI tests drive the adapter through that Chat listener, so their end-to-end assertions stay; test code is outside the module check.
- **Contracts keep their names.** `AiException` and `VoiceException` answer the same `CHAT_` error codes, the metric names (`memoryos.chat.voice.*`) and configuration keys (`memoryos.chat.catalog.*`, `memoryos.chat.provider.*`, `memoryos.chat.execution.*`) are unchanged, and no class behind an HTTP body was renamed, so the OpenAPI document is identical. `ai` does not read Chat's `ChatExecutionProperties`: the `api` composition root passes the provider read timeout and the cost and token caps, and the two `ai` beans that need a limit read their key directly. The `Chat*` prefixes of classes now in `ai` stay until step 4.
- **Voice settings stay in Chat.** `chat_voice_settings` holds a member's auto-send, auto-playback and playback speed, which decide what Chat's composer and answers do with voice; `VoiceSettings` and `VoiceSettingsService` moved to `chat.preferences`. `voice` depends on `ai` for `ProviderCredentials` and the endpoint rule, which the catalog and every tool connection share.
- **Meetings publish minutes through `chat :: library` until step 3.** `MeetingLibraryService` in `meeting` publishes minutes to the owner's library and withdraws them on a rerun; `MeetingController` only maps HTTP. The five types it uses (`ChatLibraryService`, `ChatFileService`, `ChatLibraryFile`, `UserFile`, `ChatFileInUseException`) carry `@NamedInterface("library")`; step 3 moves them to `library` and replaces the dependency.
- **Nothing moved is session-serialized.** The JDBC session stores the security context, the invitation and MCP authorization states; voice tickets are process memory. `V128` is unaffected.

Dependencies after step 2 (every list also includes `shared`):

| Module | Before | After |
| --- | --- | --- |
| `ai` | — (inside `chat`) | `iam :: tenant`, `iam :: group`, `audit`, `usage` |
| `voice` | — (inside `chat`) | `ai`, `iam :: tenant`, `iam :: group`, `audit`, `usage` |
| `chat` | `iam :: tenant`, `iam :: group`, `iam :: identity`, `retrieval`, `connector`, `objectstorage`, `document`, `mcp`, `usage`, `audit` | the same and `ai` (Chat no longer needs `voice`) |
| `meeting` | `iam :: group`, `chat :: voice`, `chat :: summary`, `objectstorage`, `usage` | `iam :: group`, `ai`, `voice`, `chat :: library`, `objectstorage`, `usage` |

## Consequences

- Meeting, Chat and ingestion depend on what they use; the orchestration in `MeetingController` returns to a module where Modulith checks it.
- `audit` sits below `iam` and depends only on the `shared` kernel, so it takes typed Tenant and actor identifiers without a cycle, and asks IAM who may read the stream through the `AuditReaders` port that `iam` implements. It first took plain UUIDs at its boundary; the shared kernel above replaced them.
- A type enters `shared` only if it is a pure identifier or value carried by several modules. Anything with behaviour, or the identifier of one module's aggregate, stays in that module; `shared` must not grow into a common utilities package.
- Most of the published surface of IAM and Chat becomes internal, which is the point and also the cost: every consumer of a subpackage has to move to the root API, and a few types have to be promoted to it.
- OpenAPI schema names that came from repository records change, and the web client is regenerated with them. There are no external API consumers yet.
- Package moves touch many files. The JPA package lists in both composition roots (`@EntityScan`, `@EnableJpaRepositories`) name persistence packages by string and must follow each move; they are bootstrap configuration, not a use of those packages.
- The deployables' boundary depends on review, not a test. If review misses it in practice, extending `CoreDependencyRulesTest` to the `api` and `worker` classpaths is the fallback.
