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

### Layout inside a module

- The module root package is the published API: services, identifiers, views, events and exceptions.
- Feature subpackages are internal implementation, each with its own `persistence` package.
- `@NamedInterface` is used only for a deliberate extra surface, and no module depends on another with `module :: *`.
- An interface stays only when it is the published API and its implementation is internal. Otherwise an interface/`Default*` pair collapses into one class.
- `api` and `worker` use only the published API of modules, never a `persistence` package, and do not return core types as HTTP bodies; `api` maps them to its own `contract/` records ([conventions](../conventions.md)). Modulith and `CoreDependencyRulesTest` keep verifying `core`; the owner chose not to extend ArchUnit to the deployables, so their boundary is held by review.

### Order

One pull request, in this order, each step compiling and passing its targeted tests:

0. `api` and `worker` stop reading `persistence`; OpenAPI schema names may change.
1. Extract `audit`.
2. Extract `ai` and `voice`; move meeting orchestration out of `MeetingController` into `meeting`.
3. Extract `library`; `ingestion` depends on `library`.
4. Internal layout of `chat`, `connector` and `iam`; drop `:: *` dependencies and collapse single-implementation pairs.
5. Web feature folders mirror the modules.

## Consequences

- Meeting, Chat and ingestion depend on what they use; the orchestration in `MeetingController` returns to a module where Modulith checks it.
- Most of the published surface of IAM and Chat becomes internal, which is the point and also the cost: every consumer of a subpackage has to move to the root API, and a few types have to be promoted to it.
- OpenAPI schema names that came from repository records change, and the web client is regenerated with them. There are no external API consumers yet.
- Package moves touch many files. The JPA package lists in both composition roots (`@EntityScan`, `@EnableJpaRepositories`) name persistence packages by string and must follow each move; they are bootstrap configuration, not a use of those packages.
- The deployables' boundary depends on review, not a test. If review misses it in practice, extending `CoreDependencyRulesTest` to the `api` and `worker` classpaths is the fallback.
