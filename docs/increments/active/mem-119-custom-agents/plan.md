# Implementation plan

One pull request on `kl3inIT/mem-119-custom-agents`, committed in compiling slices. Onyx reference: `D:\MemoryOS\.tmp\onyx` (not in this worktree). If `main` adds migrations past V70 before merge, renumber V71.

## 1. Authority and schema

- [x] V71: persona columns, owner Group (`ON DELETE SET NULL`), shares, labels, tools, MCP attachments, pins, preferences, prompt shortcuts; backfills; capability check constraint; `authorization_version` bump.
- [x] `IamCapability.AGENTS_CREATE`/`AGENTS_MANAGE` (ordinary, implication), `DefaultGroupService` registry, `GroupProjectionRepository` (2 lists), `IamAuthorizationRepository`, web capability copy and icon, application session context.
- [x] Capability, migration and schema tests (`IamCapabilityTest`, `GroupSchemaIntegrityTest`, `DefaultGroupServiceAuthorizationTest`, `PostgresIamAuthorizationTest`).

## 2. Agent access

- [x] `JdbcAgentAccessRepository`: use/edit predicate SQL fragments and vacancy; replace owner predicates in `JpaPersonaRepository`, `JdbcChatRepository.usablePersona/persona`, `ModelCatalogRepository.personaExists/personas/personaModel/setPersonaModel`, `JdbcUserFileRepository` agent file admission.
- [x] `PersonaEntity`: new fields, mutable owner, tools/MCP/labels collections.
- [x] `ChatPersonaService`: create (`AGENTS_CREATE`), get/list views, update, delete, select with the authority table; row lock and revision; builtin with `AGENTS_MANAGE`.
- [x] PostgreSQL integration matrix: owner, owner Group, direct/Group VIEWER/EDITOR, public VIEWER/EDITOR, Group manager, `AGENTS_MANAGE`, none; revocation on next admission; Source intersection with full-name snapshot.

## 3. Sharing, discovery and lifecycle

- [x] Sharing replace (public rule), leave, share options picker.
- [x] Transfer to Actor/Group, vacancy, restore, admin list.
- [x] Listing, labels, pins with featured seeding.
- [x] Avatar set/clear/read (image type and size bounds).
- [x] Tenant-scoped Source name lookup for snapshots.

## 4. Chat runtime

- [x] Remove `search_enabled` consumers: `ChatTurnOptions`, `JdbcChatRepository.persona`, `ChatTurnPersistence`, `ChatModelExecutor` (normal and research agents), prompts.
- [x] Tool policy read before Web/image/MCP checks in `ChatTurnService.sendLocked`; MCP subset; builtin unrestricted MCP.
- [x] Prompt: replace base prompt, date awareness, task prompt reminder in `ChatPrompts.forInference` via the guard.
- [x] Knowledge cutoff in `SearchTool` filters.
- [x] `ModelCatalogService.available` Onyx persona clause; keep default-provider rule.
- [x] Unit tests for prompt/reminder and model rule; integration test for tool rejection and cutoff.

## 5. Prompt shortcuts

- [x] Persistence, service, bounds, public administration, hide, preferences.
- [x] Integration tests for ownership, uniqueness, public/hidden visibility.

## 6. HTTP and client

- [x] Controllers and contracts; security coverage per endpoint.
- [x] OpenAPI snapshot; `pnpm install`; Hey API regeneration.

## 7. Web

- [x] `/agents` gallery, `/assistants` redirect, navigation.
- [x] Editor, avatar/icon, labels, tools, MCP servers, cutoff, prompts.
- [x] Share dialog, viewer dialog, transfer.
- [x] Sidebar pins; composer tool controls follow agent policy.
- [x] `/admin/agents`.
- [x] Prompt shortcut settings and composer `/` menu (assistant-ui trigger popover primitives).
- [x] vi/en strings; vitest; Playwright with realistic Tasco fixtures; screenshot self-review.
- [x] UI redesign (2026-09-17): composites layer and tokens; editor as a page with preview and drafts; catalog gallery with facet filters; Perplexity-style sharing; detail view with composer; sortable sidebar pins and administration; Onyx inline shortcut editing with free-text names.

## 8. Documentation and gates

- [ ] `docs/specs/chat.md`, `chat-models.md`, `identity.md`, `docs/tests/chat.md`, `identity.md`, `ARCHITECTURE.md`, `verification.md`.
- [ ] `gradlew clean check`; web lint/typecheck/test; PR through the MemoryOS PR loop.
