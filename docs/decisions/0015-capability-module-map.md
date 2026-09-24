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

### Step 3: what `library` holds

`library`'s root is its published API: the upload lifecycle and file reads (`ChatFileService`, `ChatFileContentService`, `ChatFileSearchService`, `UserFile`), the library listing, rename, star, copy and publish (`ChatLibraryService`, `ChatLibraryFile`), trash (`ChatLibraryTrashService`, `LibraryTrashProperties`), the storage limit (`ChatStorageQuotaService`, `ChatStorageProperties`), the upload limit (`ChatFileProperties`), archives (`ChatLibraryArchiveService` and its records), what an archive or Chat's export packs (`LibraryContents`), the extraction work the Worker runs (`UserFileWork`, `UserFileWorkPort`, `UserFileMaintenance`), `ImageThumbnails`, `ChatFileInUseException`, `LibraryException`, the two ports below and `LibraryWorkerComponents`. Internal: `library.persistence` (user files, the listing read model, archives) and `library.work` with its own `persistence` (the file work claim and completion). `UserFileWorkPort` keeps its implementation `DefaultUserFileWorkService`: the interface is the published API that `ingestion` and the Worker call, and the implementation is internal. The module has no JPA entity, so neither composition root's JPA package list changed.

What stayed in `chat`: the files and images an answer generates (`chat_file_artifact`, `chat_image_artifact`), their byte-release sweep and the copy of an answer's artifacts into a branch (`JdbcChatArtifactRepository`), the descriptors a message records for its attachments (`ChatFileDescriptor`, an OpenAPI schema through `ChatMessageResponse`), agents, Projects and temporary conversations, and the conversation export, which packs the library through `LibraryContents`.

Judgement calls made while moving:

- **Chat's attachments are asked through a port.** Who may read an upload besides its owner (an agent they can use attaches it or shows it as its avatar) and what keeps it from being deleted (the agents and Projects holding it) are Chat's facts. `library` owns the `FileAttachments` port and `chat` implements it (`ChatFileAttachments`, `JdbcChatFileAttachmentRepository`, which compares the ids as the text `file_ids` stores). The library resolves the agent grant first and passes the granted ids into the statement that reads, and when admitting share-locks, the file row, so a read is still one statement over the file.
- **The listing reads Chat's artifact tables by SQL; every change to them goes through a port.** A page must sort, count and total uploads and generated files together, so `JdbcChatLibraryRepository` keeps its one `UNION` over `chat_user_file`, `chat_file_artifact`, `chat_image_artifact` and, for one conversation's own files, `chat_session` and `chat_message`, and the artifact lookup a copy and an archive read. This is the same class of read as `ai` reading IAM's Group tables in step 2. Renaming, starring, restoring and purging a generated file or image is the `LibraryArtifacts` port, which `chat` implements (`ChatLibraryArtifacts`) over its interpreter and image repositories.
- **The owner lock keeps Chat's key.** A turn admits files under Chat's per-person advisory lock; the library's writes take the same lock by the same key string (`chat-owner:<tenant>:<actor>`) from its own repository, so an upload deleted, restored or copied still cannot race its admission. The key is a shared protocol, not a Java dependency, and changing it on one side alone would break the exclusion.
- **A temporary conversation's uploads.** `chat_user_file.temporary_session_id` is a Chat fact on a library row. The library publishes the two writes (`ChatFileService.claimTemporary` in the turn transaction, `UserFileMaintenance.releaseTemporary` in the purge transaction) and Chat calls them; Chat no longer writes the library's tables.
- **Trash is one window.** `memoryos.chat.retention.trash-after` binds `LibraryTrashProperties`; Chat's `ChatRetentionProperties` binds the rest of the prefix (`hard-delete`, `deleted-after`, `temporary-after`), and Chat's image and interpreter services read the library's window when they trash an artifact.
- **Thumbnails and image decoding are the library's.** `ImageThumbnails` moved with the untrusted-image decoding and area-averaged scaling it needs; Chat's image edits call `ImageThumbnails.decode`, `scale` and `smooth`, keeping the edit's decode subsampling (four times 1024 px).
- **`ChatFileService.admit` answers `UserFile`**; Chat maps it to the `ChatFileDescriptor` its messages store, and a turn refusing an unreadable file now fails with `LibraryException` carrying the same `CHAT_UNAVAILABLE` code.
- **`library → connector`** remains only for `SourceOperationTraceContext`, which the file work carries as its origin trace, as `ingestion` does.
- **No events.** Every cross-module need is synchronous and inside the caller's transaction, so the ports are plain calls.
- **Contracts keep their names.** `LibraryException` answers the `CHAT_` codes (`CHAT_UNAVAILABLE`, `CHAT_INVALID_REQUEST`, `CHAT_CONFLICT`, `CHAT_STORAGE_FULL`) and `ChatFileInUseException` `CHAT_FILE_IN_USE`; configuration keys (`memoryos.chat.files.*`, `memoryos.chat.storage.*`, `memoryos.chat.retention.trash-after`), log event names (`chat.library.*`), table names and the `Chat*` class prefixes are unchanged, so the OpenAPI document is identical. `document` and `ingestion` keep reading `chat_user_file` and `chat_file_work` by SQL as before.
- **Nothing moved is session-serialized.** The JDBC session holds the security context, the invitation, provider logout and MCP and Google Drive authorization states; `V128` is unaffected.

Dependencies after step 3 (every list also includes `shared`):

| Module | Before | After |
| --- | --- | --- |
| `library` | — (inside `chat`) | `iam :: tenant`, `objectstorage`, `document`, `retrieval`, `connector` |
| `chat` | `ai`, `iam :: tenant`, `iam :: group`, `iam :: identity`, `retrieval`, `connector`, `objectstorage`, `document`, `mcp`, `usage`, `audit` | the same and `library` |
| `meeting` | `iam :: group`, `ai`, `voice`, `chat :: library`, `objectstorage`, `usage` | `iam :: group`, `ai`, `voice`, `library`, `objectstorage`, `usage` |
| `ingestion` | `connector`, `document`, `objectstorage`, `retrieval`, `chat` | `connector`, `document`, `objectstorage`, `retrieval`, `library` |

The `chat :: library` named interface is gone. The named interfaces that remain are IAM's six (`group`, `identity`, `invitation`, `tenant`, `tenant.bootstrap`, `user`), which step 4 collapses.

## Consequences

- Meeting, Chat and ingestion depend on what they use; the orchestration in `MeetingController` returns to a module where Modulith checks it.
- `audit` sits below `iam` and depends only on the `shared` kernel, so it takes typed Tenant and actor identifiers without a cycle, and asks IAM who may read the stream through the `AuditReaders` port that `iam` implements. It first took plain UUIDs at its boundary; the shared kernel above replaced them.
- A type enters `shared` only if it is a pure identifier or value carried by several modules. Anything with behaviour, or the identifier of one module's aggregate, stays in that module; `shared` must not grow into a common utilities package.
- Most of the published surface of IAM and Chat becomes internal, which is the point and also the cost: every consumer of a subpackage has to move to the root API, and a few types have to be promoted to it.
- OpenAPI schema names that came from repository records change, and the web client is regenerated with them. There are no external API consumers yet.
- Package moves touch many files. The JPA package lists in both composition roots (`@EntityScan`, `@EnableJpaRepositories`) name persistence packages by string and must follow each move; they are bootstrap configuration, not a use of those packages.
- The deployables' boundary depends on review, not a test. If review misses it in practice, extending `CoreDependencyRulesTest` to the `api` and `worker` classpaths is the fallback.

## Step 5 (web)

The web feature folders follow the module map; the move changed imports only, not behaviour, and every file moved with `git mv`.

- `features/library` is new and holds what the backend `library` module owns: the library page and its parts, the picker, the file preview and its image crop, the storage page and meter, and the user-file upload and picker (`chat-files.ts` became `files.ts`). Files that moved out of `chat` lost their `chat-` prefix; exported names are unchanged.
- `features/chat` keeps the page, `chat-api`, `chat-workspace-api`, the shared dialog and action helpers and the model picker at its root, and groups the rest as `runtime/`, `thread/`, `composer/`, `session/`, `projects/`, `sources/` (citations), `activity/`, `research/`, `mcp/`, `image/`, `web-search/`, `interpreter/`, `settings/`, and `history/`, the former `features/chat-history`, because the backend `chat` module owns history.
- `features/sources` (the `connector` UI) keeps the list, catalog and detail pages at its root and splits into `google-drive/`, `sharepoint/`, `upload/`, `history/` and `shared/`.
- `features/models` is the admin UI of `ai` and keeps its name; `voice`, `meetings` and the smaller features stay flat.
- `library` still imports from `chat`, against the backend direction: the ask-about-this-file composer uses Chat's model picker and composer row, the library settings and storage page reuse Chat's retention section, the library page adds files to a project, and several files use Chat's dialog and action helpers, `chat-api`, and the file size and artifact URL helpers of `interpreter/chat-code` and `image/chat-image`. The web has no module check, and splitting those files was outside a pure move.

## Step 4 — connector, iam

Both modules now follow the layout rule: the root package is the published API, and every feature package, with its own `persistence`, is internal. Every file moved with `git mv`; behaviour, SQL, transaction boundaries and the [ADR 0007](0007-unified-jpa-iam-and-group-authorization.md) authorization semantics are unchanged.

### `connector`

The root keeps what other modules, the deployables and the connector bundle use: the ports (`ConnectorSyncPort`, `ConnectorIndexingPort`, `ConnectorCleanupPort` and their work records), Source, item, operation and credential identifiers, views, pages, events and exceptions, the published service interfaces (`SourceManagementService`, `SourceRunHistoryService`, `SourceRunHistoryMaintenance`, `SourceDocumentAccessResolver`, the Google Drive authorization, service-account, Source and selection services, the SharePoint credential, Source and selection services), `SourceSearchService`, `SourceCollectionScopeResolver`, and the provider SPI the bundle implements (`GoogleDriveProvider`, `SharePointProvider`, `GoogleDriveLinkReader`, `GoogleDriveOAuthClient`, their exceptions, `GoogleDriveServiceAccountKey`, `SourceInputDescriptor`). `application` and `persistence` are replaced by:

| Package | Holds |
| --- | --- |
| `source` | Source management, run history and its pruning, document access, `SourceAccessPolicy`; `source.persistence`: the `JdbcSource*` repositories, `SourceScopeSql`, `SourceHistoryCursor` and `CredentialCipher` |
| `sync` | `DefaultConnectorSyncService` (the `ConnectorSyncPort`), `DefaultConnectorCleanupService`, `ProviderAuthorityService`; `sync.persistence`: `JdbcSourceSyncRepository`, `JdbcIndexAttemptRepository`, `JdbcCleanupAttemptRepository`, `WorkLeases` |
| `googledrive` | the Google Drive services, connection, selection tree and policy, metadata cache, linked discovery, Google Group sync; `googledrive.persistence`: credentials, Sources, selections, ACLs, Google Groups |
| `sharepoint` | the SharePoint services, connection, sync, selection policy, `SharePointAuthentication`, `SharePointCertificate`, `SharePointGlob`, `SharePointUrl`; `sharepoint.persistence`: credentials, Sources, selections, sync |

Judgement calls:

- **Three pairs collapse.** `GoogleDriveConnectionService`, `SharePointConnectionService` and `ProviderAuthorityService` were interfaces with one `Default*` implementation and no consumer outside the module; each is now one class in its feature package. The other `Default*` implementations keep their names and stay behind a root interface, as `UserFileWorkPort` does in step 3.
- **The two sync engines are placed, not merged.** `DefaultConnectorSyncService` implements the provider-neutral port but is still the Google Drive traversal and calls `DefaultSharePointSyncService`; it sits in `sync` next to the attempt and lease machinery, and separating a Google Drive engine from the dispatch is left to the later de-duplication.
- **Shared persistence helpers became public.** `WorkLeases` (used by the attempt, Source, Google Drive and SharePoint repositories), `SourceHistoryCursor`, `GoogleDriveRootValidation` and two `JdbcSourceRepository` row helpers were package-private in the old flat packages; inside a closed module that is still internal.
- **`CredentialCipher` is Source persistence.** Both providers' credential repositories encrypt with it, and credentials exist only to authorize Sources.
- **`GoogleDriveAclReader` stays published** although nothing calls it yet: `GoogleDriveAclChanged` tells listeners to re-read the snapshot through it.
- The ownership rule of `CoreDependencyRulesTest` now matches nested persistence packages (`io.memoryos.<capability>..persistence..`), so `connector.source.persistence` and `library.work.persistence` are guarded too.

### `iam`

The six named interfaces are gone, and the root package is IAM's whole published API: Tenant access and membership (`TenantAccessResolver`, `TenantMembership`, `TenantMemberManagement`, `TenantBootstrapped`, initial bootstrap), the authorization decision and Groups (`IamAuthorization`, `IamCapability`, `Authority`, `IamAccess`, `GroupScopeService`, `GroupService`, `GroupId` and the Group views), identity (`IdentityContext`, `ExternalIdentity`, `ExternalIdentityResolver`, `TrustedIdentityAdmission`, `ActorProfileReader`, `ActorProfileRecorder`, `ActorLanguageService`, `ProviderSessionTerminator`, `AccountType`), users (`UserQueryService` and its records), invitations (`InvitationService` and its records and exceptions) and identity providers (`IdentityProviderAdministration`, its commands and views, `DiscoveredOidcProvider`, `JitAdmissionPolicy`, `JitAllowlistSeeder`). A flat root of about seventy types was preferred to keeping `tenant`, `group` and `identity` as named interfaces, because this ADR already fixed the root as the published surface and a named interface is kept only for a deliberate extra surface.

The feature packages `group`, `identity`, `identityprovider`, `invitation`, `keycloak`, `tenant` and `user` keep the implementations and their JPA persistence. Their package names did not change, so neither composition root's JPA scan strings nor the Worker's scan list changed. `tenant.bootstrap` held only the bootstrapper once its API moved up, and joined `tenant`.

- **Pairs.** The ten `Default*` implementations behind a root interface stay (`DefaultIamAuthorization`, `DefaultGroupService`, `DefaultGroupScopeService`, `DefaultTrustedIdentityAdmission`, `DefaultIdentityProviderAdministration`, `DefaultJitAdmissionPolicy`, `DefaultInvitationService`, `DefaultTenantMemberManagement`, `DefaultInitialTenantBootstrapper`, `DefaultUserQueryService`). `GroupAdministrationGuard` and `GroupProvisioner` were used only inside IAM and collapse into one class each; the tests that replaced them with anonymous classes now stub them with Mockito.
- **Internal seams stay interfaces.** `ExternalIdentityRegistrar`, `TenantMembershipProvisioner`, `IdentityProviderGateway` and `KeycloakRecipientProvisioner` are implemented by persistence or Keycloak adapters and replaced by test doubles; they are not part of the published API. The API's `TestKeycloakProvisioningConfiguration` still substitutes the Keycloak provisioner, as test code may.
- **The Worker names no implementation.** It imports `IamWorkerComponents`, a root class that imports the authorization, Group scope and audit-reader beans, as `LibraryWorkerComponents` does.
- **The session principal moved.** `IdentityContext` is stored Java-serialized in the JDBC session and is now `io.memoryos.iam.IdentityContext`; `V128`, already on this branch, deletes every session, so this move needs nothing more.
- **Not done here.** The `ai` catalog still reads `iam_groups` and `iam_group_memberships` by SQL (step 2); replacing it with an IAM call belongs to the `ai` work.

Dependencies after step 4 (every list also includes `shared`):

| Module | Before | After |
| --- | --- | --- |
| `ai`, `voice`, `connector`, `retrieval` | `iam :: tenant`, `iam :: group` | `iam` |
| `chat` | `iam :: tenant`, `iam :: group`, `iam :: identity` | `iam` |
| `library` | `iam :: tenant` | `iam` |
| `mcp`, `meeting`, `usage` | `iam :: group` | `iam` |

The deployables' main code imports no internal package of `connector` or `iam`. Two API tests still name internal types: `SharePointSourceApiTest` mocks `DefaultSharePointSourceService`, because the selection processor injects the concrete service, and `TestKeycloakProvisioningConfiguration` above. The OpenAPI document is unchanged: no class behind an HTTP body was renamed.
