# Implementation plan

One pull request on `mem-119/custom-agents`, committed in compiling slices. Onyx reference: `D:\MemoryOS\.tmp\onyx` (not in this worktree). If `main` adds migrations past V70 before merge, renumber V71.

## 1. Authority and schema

- [ ] V71: persona columns, owner Group (`ON DELETE SET NULL`), shares, labels, tools, MCP attachments, pins, preferences, prompt shortcuts; backfills; capability check constraint; `authorization_version` bump.
- [ ] `IamCapability.AGENTS_CREATE`/`AGENTS_MANAGE` (ordinary, implication), `DefaultGroupService` registry, `GroupProjectionRepository` (2 lists), `IamAuthorizationRepository`, web capability copy and icon, application session context.
- [ ] Capability, migration and schema tests (`IamCapabilityTest`, `GroupSchemaIntegrityTest`, `DefaultGroupServiceAuthorizationTest`, `PostgresIamAuthorizationTest`).

## 2. Agent access

- [ ] `JdbcAgentAccessRepository`: use/edit predicate SQL fragments and vacancy; replace owner predicates in `JpaPersonaRepository`, `JdbcChatRepository.usablePersona/persona`, `ModelCatalogRepository.personaExists/personas/personaModel/setPersonaModel`, `JdbcUserFileRepository` agent file admission.
- [ ] `PersonaEntity`: new fields, mutable owner, tools/MCP/labels collections.
- [ ] `ChatPersonaService`: create (`AGENTS_CREATE`), get/list views, update, delete, select with the authority table; row lock and revision; builtin with `AGENTS_MANAGE`.
- [ ] PostgreSQL integration matrix: owner, owner Group, direct/Group VIEWER/EDITOR, public VIEWER/EDITOR, Group manager, `AGENTS_MANAGE`, none; revocation on next admission; Source intersection with full-name snapshot.

## 3. Sharing, discovery and lifecycle

- [ ] Sharing replace (public rule), leave, share options picker.
- [ ] Transfer to Actor/Group, vacancy, restore, admin list.
- [ ] Listing, labels, pins with featured seeding.
- [ ] Avatar set/clear/read (image type and size bounds).
- [ ] Tenant-scoped Source name lookup for snapshots.

## 4. Chat runtime

- [ ] Remove `search_enabled` consumers: `ChatTurnOptions`, `JdbcChatRepository.persona`, `ChatTurnPersistence`, `ChatModelExecutor` (normal and research agents), prompts.
- [ ] Tool policy read before Web/image/MCP checks in `ChatTurnService.sendLocked`; MCP subset; builtin unrestricted MCP.
- [ ] Prompt: replace base prompt, date awareness, task prompt reminder in `ChatPrompts.forInference` via the guard.
- [ ] Knowledge cutoff in `SearchTool` filters.
- [ ] `ModelCatalogService.available` Onyx persona clause; keep default-provider rule.
- [ ] Unit tests for prompt/reminder and model rule; integration test for tool rejection and cutoff.

## 5. Prompt shortcuts

- [ ] Persistence, service, bounds, public administration, hide, preferences.
- [ ] Integration tests for ownership, uniqueness, public/hidden visibility.

## 6. HTTP and client

- [ ] Controllers and contracts; security coverage per endpoint.
- [ ] OpenAPI snapshot; `pnpm install`; Hey API regeneration.

## 7. Web

- [ ] `/agents` gallery, `/assistants` redirect, navigation.
- [ ] Editor sections, avatar/icon, labels, tools, MCP servers, cutoff, prompts.
- [ ] Share dialog, viewer dialog, transfer.
- [ ] Composer agent picker with pins; sidebar pins; composer tool controls follow agent policy.
- [ ] `/admin/agents`.
- [ ] Prompt shortcut settings and composer `/` menu (assistant-ui trigger popover primitives).
- [ ] vi/en strings; vitest; Playwright desktop/mobile with realistic Tasco fixtures; screenshot self-review.

## 8. Documentation and gates

- [ ] `docs/specs/chat.md`, `chat-models.md`, `identity.md`, `docs/tests/chat.md`, `identity.md`, `ARCHITECTURE.md`, `verification.md`.
- [ ] `gradlew clean check`; web lint/typecheck/test; PR through the MemoryOS PR loop.
