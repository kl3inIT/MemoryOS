# MEM-119 — Custom agents and prompt shortcuts (Onyx parity)

Linear: [MEM-119](https://linear.app/memory-os/issue/MEM-119). Context: [Vinaconex 9 Tower 3 meeting analysis](https://linear.app/memory-os/document/phan-tich-cuoc-hop-vinaconex-9-tower-3-djinh-huong-ai-office-memoryos-dface094cba0). Dependents: [MEM-129 intent router](https://linear.app/memory-os/issue/MEM-129), [MEM-131 knowledge spaces](https://linear.app/memory-os/issue/MEM-131).

## Accepted scope

Turn the owner-private `Persona` (UI: assistant) into an Onyx-equivalent custom agent and add Onyx prompt shortcuts, in one pull request. Reference: Onyx checkout under `.tmp/onyx` — `backend/onyx/db/models.py` (`Persona`, `InputPrompt`, `PersonaLabel`, `user__pinned_persona`), `backend/onyx/db/persona.py`, `persona_sharing.py`, `pinned_personas.py`, `input_prompt.py`, `llm.py`, `backend/onyx/server/features/persona/api.py`, `input_prompt/api.py`, `backend/onyx/chat/llm_loop.py`, and the web views `AgentEditorPage`, `AgentsNavigationPage`, `AgentViewerModal`, `AppInputBar`, `SettingsPage`.

Owner decisions (2026-09-17): follow Onyx for creation authority, public visibility, owner departure, the viewer snapshot and model access through agents; include prompt shortcuts; update existing behavior freely; one pull request.

Onyx over LibreChat: MemoryOS answers from Tenant connectors whose document permissions are enforced per reader; LibreChat grants every agent user all agent files. Onyx has no automatic agent router, versioning, duplicate or agent @-mention; those stay out of scope.

## Before this increment

- `persona` (V18, V33, V36, V38): name, description, instructions, starters (max 8), Sources (max 100), `search_enabled`, model and token limits, `file_ids`, soft delete, revision, builtin default.
- Owner-only predicates in `ChatPersonaService.owned`, `JpaPersonaRepository.readable`, `JdbcChatRepository.usablePersona` and `.persona`, `ModelCatalogRepository.personaExists/personas/personaModel/setPersonaModel`.
- Builtin edits require `MODELS_MANAGE`.
- `SearchTool` narrows `search.scope(actor)` to agent Sources, so sharing cannot widen document access.
- Session agent switching exists; Onyx lacks it and MemoryOS keeps it.

## Authority

`IamCapability` gains `AGENTS_CREATE` (Onyx `ADD_AGENTS`) and `AGENTS_MANAGE` (Onyx `MANAGE_AGENTS`, implies `AGENTS_CREATE`). Both are ordinary Group grants implied by `SYSTEM_ADMIN` and not by `SYSTEM_BASIC` (Onyx Enterprise default). The migration adds them to the capability check constraint and bumps `authorization_version`.

| Operation | Allowed |
| --- | --- |
| Create | `AGENTS_CREATE` |
| Use: list, snapshot, select for a session, admit a turn, model list | builtin; owner; member of the owner Group; public; direct share; share to a Group the actor belongs to; `AGENTS_MANAGE` |
| Edit | owner or owner-Group member; EDITOR share (direct or Group); public with `public_permission = EDITOR`; `AGENTS_MANAGE`; a Group manager for a private agent whose share Groups are all managed by them |
| Replace shares and labels | edit authority; `isPublic`/`publicPermission` changes are applied only for the owner, owner-Group members or `AGENTS_MANAGE` and silently kept otherwise (Onyx) |
| Listed, featured, display priority | `AGENTS_MANAGE` |
| Delete (soft) | owner, owner-Group member or `AGENTS_MANAGE` |
| Undelete, admin list, label rename/delete, public prompt administration | `AGENTS_MANAGE` (Onyx uses full admin; MemoryOS keeps it inside the agent capability) |
| Leave a share | the direct sharee |
| Transfer ownership | owner or owner-Group member; `AGENTS_MANAGE` when vacant. Target: active member or ordinary Group. Previous Actor owner keeps an EDITOR share |
| Builtin default | used by all; edited with `AGENTS_MANAGE` |

A non-builtin agent is **vacant** when it has no owner Group and its owner Actor has no ACTIVE membership. MemoryOS has no Actor deletion path, so Onyx's delete-time cleanup has no trigger here; membership revocation leaves the agent unchanged and vacant.

All use/edit checks come from one SQL predicate in `JdbcAgentAccessRepository`, reused by list queries, `usablePersona`, turn resolution and catalog persona reads. Share Groups must be ordinary Groups (V48 trigger pattern).

## Data model (V71)

`persona` columns: `is_public` (default false), `public_permission` (`VIEWER`/`EDITOR`), `is_listed` (default true), `is_featured`, `display_priority`, `icon_name`, `avatar_file_id` (chat user file, image), `task_prompt` (≤32000), `replace_base_system_prompt`, `datetime_aware` (default true), `knowledge_cutoff` (timestamptz), `owner_group_id`; `search_enabled` is replaced by tools. V36 `ck_persona_owner` becomes: builtin has no owner; custom has at most one of Actor/Group owner.

`owner_group_id` references `iam_groups` with `ON DELETE SET NULL`, so deleting the owner Group makes the agent vacant. Share, label, tool, MCP and pin tables below cascade with their persona, Group or Actor.

Tables (Tenant-qualified FKs):

- `persona_user_share(tenant_id, persona_id, actor_id, permission)`, `persona_group_share(tenant_id, persona_id, group_id, permission)`.
- `persona_label(tenant_id, id, name)` unique per Tenant; `persona_label_assignment`.
- `persona_tool(tenant_id, persona_id, tool_key)` with keys `search`, `web_search` (web_search and open_url), `image_generation` (generate_image, edit_image); `persona_mcp_server(tenant_id, persona_id, server_id)`. Backfill: `search` when `search_enabled`, `web_search` and `image_generation` for every existing agent, all Tenant MCP servers for existing custom agents. The builtin agent has all tool keys and no MCP rows, which means every MCP server the actor can access; custom agents must attach servers registered later (Onyx per-persona tool attachment).
- `actor_pinned_persona(tenant_id, actor_id, persona_id, position)` plus `actor_agent_preferences(tenant_id, actor_id, pins_seeded, shortcuts_enabled)`.
- `prompt_shortcut(tenant_id, id, owner_actor_id null for public, name, content, active)` with unique name per owner and among public; `prompt_shortcut_hidden(tenant_id, shortcut_id, actor_id)`.

## Runtime

- **Prompt (adapted from Onyx `llm_loop.py`)**: MemoryOS keeps the agent instructions inside the system message instead of Onyx's separate user message before the last question. Builtin unchanged (base prompt plus Project instructions). Custom agent: base system prompt, then the agent instructions; with `replace_base_system_prompt` the instructions are the only system prompt. `datetime_aware` fills `{{CURRENT_DATETIME}}` and adds the date when absent; otherwise the placeholder is removed. `task_prompt` is sent as the final `<system-reminder>` user message of every inference, together with citation and last-cycle reminders.
- **Knowledge**: Source intersection unchanged; `knowledge_cutoff` is a lower bound on document update time in `SearchTool` filters, never earlier than a requested filter.
- **Tools**: `ChatTurnService.sendLocked` reads the session agent's tool policy under agent use authority before the Web, image and MCP checks; admission rechecks the persona revision. The server rejects a command whose Web, image or MCP selection the agent does not allow (`webUnavailable`/`providerUnavailable`); `search_knowledge` is registered only with the `search` tool. MCP selection must be a subset of the agent's MCP servers. The composer only offers allowed tools.
- **Agent files**: files attached by an editor are admitted for any agent user during a turn; they remain unreadable through the file API, except the avatar image served by the agent avatar endpoint.
- **Model (Onyx `can_user_access_llm_provider`)**: a provider restricted to agents is usable only through those agents; a public provider is usable by all; a provider with Groups requires a Group (or `MODELS_MANAGE`); a non-public provider without Groups but with agents is usable by anyone using a listed agent (`ModelCatalogService.available` gains that clause). The Tenant default provider still requires a public provider without agent restriction. If the agent default is inaccessible, selection falls back to the Tenant default. MemoryOS does not reproduce Onyx clearing the agent default during a read.
- **Concurrency**: agent mutations lock the persona row (`PESSIMISTIC_WRITE`) and check revision; turn resolution keeps `FOR SHARE OF p`. `lockOwner` still serializes one actor's own sessions.
- **Revocation**: lost use authority fails the next admission with `CHAT_UNAVAILABLE`; admitted turns keep their captured context.

## Viewer snapshot

Every agent user receives the full snapshot, as Onyx. Source names come from a Tenant-scoped connector lookup that ignores the reader's Source authority: Sources with names, files, instructions, task prompt, starters, tools and MCP servers, model, labels, owner (name, email), user and Group shares, flags, vacancy and permission hints (`edit`, `share`, `setPublic`, `delete`, `transfer`, `leave`, `manage`). Names never grant search or preview authority.

## Prompt shortcuts (Onyx `InputPrompt`)

- Users create, edit and delete private shortcuts (unique name). `AGENTS_MANAGE` creates, edits and deletes public shortcuts (Onyx has no public-creation endpoint; this closes that gap). Users hide public shortcuts for themselves. `active` stays in the contract but, as in Onyx, has no UI toggle.
- Names are free text on one line, including spaces and diacritics (`Tóm tắt hợp đồng`); control characters are rejected. Onyx allows free-text names too; an earlier draft of V71 banned whitespace and is superseded before merge.
- Typing `/` at the start of the composer opens active, unhidden shortcuts. As in Onyx the whole draft after `/` is the query, so names with spaces match; MemoryOS folds diacritics so `/tom tat` finds `Tóm tắt hợp đồng`. Each row shows the name and the first line of content; choosing one replaces the draft; the last row opens shortcut settings. Onyx offers shortcuts only through `/`, so the composer `+` menu does not list them.
- Settings and `/admin/agents` edit shortcuts inline as Onyx `SettingsPage`: a name field with a fixed `/` prefix and a three-line content field per shortcut, saved when focus leaves the pair, and a trailing empty pair that creates one. Members see public shortcuts read-only with a hide-for-me toggle. The per-user shortcut toggle defaults to enabled in MemoryOS (Onyx defaults off).
- Bounds: name ≤ 100, content ≤ 8000, at most 200 private shortcuts per Actor.

## Pins and discovery

- Pins are an ordered per-Actor list replaced as a whole; inaccessible, duplicate and builtin IDs are dropped. On an Actor's first agent list read, pins are seeded once from featured, public, listed agents ordered by display priority. Starting a chat from the gallery pins the agent (Onyx). The sidebar reorders pins by drag or keyboard and unpins on hover.
- The administration list orders by display priority then name (featured no longer sorts first there); dragging writes each moved agent's priority as its position. The gallery still shows featured agents first.
- A copied share link is `/agents?agent={id}` and opens that agent's detail view.
- Labels: agent users list labels; users with create or edit authority create labels; `AGENTS_MANAGE` renames and deletes.
- Avatar: an uploaded chat image file (PNG, JPEG, WebP, GIF; ≤ 2 MiB, bounds Onyx lacks) or an icon name from the frontend set; choosing one clears the other.

## HTTP

Existing `/api/chat/personas` CRUD keeps its paths with the extended body. Additions:

| Method and path | Contract |
| --- | --- |
| `GET /api/chat/personas?view=all|mine|shared&labelId=&q=&offset=&limit=` | usable listed agents (own always), featured first, display priority, name |
| `PUT /api/chat/personas/{id}/sharing?revision` | user/Group shares with permission, public flag and permission |
| `DELETE /api/chat/personas/{id}/sharing/me` | leave |
| `POST /api/chat/personas/{id}/owner?revision` | transfer to Actor or Group |
| `PUT /api/chat/personas/{id}/listing?revision` | listed, featured, priority |
| `POST /api/chat/personas/{id}/restore` | undelete |
| `GET /api/chat/personas/admin` | all agents including unlisted, deleted and vacant |
| `PUT/DELETE /api/chat/personas/{id}/avatar`, `GET /api/chat/personas/{id}/avatar` | set or clear the uploaded image (edit authority); read the image with agent use authority |
| `GET/POST /api/chat/persona-labels`, `PUT/DELETE /api/chat/persona-labels/{id}` | labels |
| `GET/PUT /api/chat/persona-pins` | ordered pins |
| `GET /api/chat/persona-share-options?q=` | active members and ordinary Groups for the picker |
| `GET/POST /api/chat/prompt-shortcuts`, `PUT/DELETE /{id}`, `PUT /{id}/hidden`, `GET/PUT /api/chat/prompt-shortcuts/preferences` | shortcuts |

Errors reuse Chat RFC 9457 problem types. The OpenAPI snapshot and Hey API client are regenerated.

## UI

Composition follows [component reuse](../../../conventions.md#component-and-library-reuse): shadcn primitives, existing Chat components (`ModelSelector` with provider logos, `ChatFilePicker`, source provider icons, `ConfirmDialog`, action notifications) and a small `web/src/components/composites` layer. Visual references are Onyx Opal (kept only under `.tmp` for reference, not vendored) and Mobbin catalogs: Langdock, Lindy, Sana and SchoolAI for the gallery; Langdock, StackAI and Relevance AI for the editor; Perplexity and GitBook for sharing. MemoryOS tokens already match Opal values; the web theme adds only the missing faint, strong, selection and card-gradient tokens.

- **Gallery `/agents`** (`/assistants` redirects): underline tabs All / Mine / Shared, full-width search, a multi-select creator filter and label toggle chips with facet counts (counts reflect search and creator; more than eight labels collapse). Featured agents lead one grid with a badge instead of a sparse section. Cards: topic-tinted icon, two-line description, footer with owner and visibility and "Start chat"; edit, share and more actions appear on hover (always on touch). Create stays visible but disabled with an explanation without `AGENTS_CREATE`.
- **Editor `/agents/create`, `/agents/{id}/edit`**: a page instead of a dialog. Sections General, Instructions, Knowledge, Tools and Advanced stack with a title and description; a sticky preview shows what colleagues will see. Icons mark list items only (sources with provider icons, tools, attachments, cutoff) and the model picker's provider logo; fields and switches carry none (Mobbin survey). The icon picker opens a topic-tinted grid or an uploaded image; labels are removable chips with search-or-create; each starter prompt has its own field. Save is enabled only with changes; a new agent keeps a browser draft until saved or discarded; leaving an edited agent asks for confirmation. The preview cannot send: running an unsaved configuration has no backend path (ADR 0002).
- **Detail view**: owner, description, labels, starter prompts that open a new conversation and send immediately, a composer that does the same, and the full configuration snapshot collapsed. The question travels as `/chat/{id}?ask=` and is sent once into the empty conversation, then removed from the URL.
- **Share dialog**: invite field whose results appear only while typing; people and Groups with avatars and a role menu (View and chat, Edit, Remove access); an owner row with Transfer; an assistant-administrators row; organization-wide access; copy link; Save enabled only with changes.
- **Sidebar**: pinned agents with drag handle, unpin and an "Explore assistants" link.
- **`/admin/agents`**: sortable rows (drag or keyboard) with hover actions for listing, transfer and edit, and a star to feature; labels; public shortcuts.
- Drag and drop uses `@dnd-kit/core` and `@dnd-kit/sortable` (MIT, as Onyx) through `SortableList`.
- Vietnamese and English strings.

## Out of scope

Automatic routing (MEM-129), knowledge spaces (MEM-131), per-document attachment, versioning, duplicate, marketplace, chains/handoffs, agent @-mention, code interpreter tool toggle until that tool exists on `main`.
