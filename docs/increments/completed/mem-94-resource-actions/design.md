# MEM-94 — Per-resource permissions from the backend

## Goal

Each resource the browser administers carries a `permissions` map of booleans that says whether the current actor may perform each action on that resource. The browser shows or hides controls from this map only. The design follows the reference read-side capability projection.

## Principles (as in the reference)

- **Hint, not authority.** The map is an affordance hint. Every mutating route keeps its own guard as the security boundary; nothing reads the map to authorize.
- **Same decision.** Each key is computed from the same predicate the corresponding write guard enforces (for example the managed-scope editable decision `SourceScopeSql.WRITE`, or the groupless-creator carve-out of Source deletion). There is no independent rule.
- **One key per distinct decision.** Operations guarded by the same decision share a key. For example, Source rename, Group grants, uploads, reindex and synchronization all use the scoped write decision and share `edit`.
- **Typed vocabulary.** Each resource has a fixed key set defined once in the backend (an enum per resource) and exported to OpenAPI. The web mirrors it as a typed vocabulary, so a misspelled or foreign key is a compile error.
- **Fail closed.** A missing resource or key reads as `false` in the web (`can(resource, action)`).
- **Authorization only.** Transient state preconditions stay in the service and in existing status fields: a `DELETING` Source, reindex replay availability, Google Drive roots not configured, pause state, revision conflicts, last-administrator and last-Group invariants. Type-specific affordances (uploads only for FILE Sources) use the resource type the client already has.

## Current state

- Sources and Groups return an `actions` token list. It is built in separate code: `JdbcSourceQueryRepository.actions`, which is authorization logic inside a persistence repository, and `DefaultGroupService.summary`. The list also encodes state (pause/resume).
- Personas return an `editable` boolean. It is true for the built-in assistant when the actor holds `MODELS_MANAGE`, although deleting the built-in assistant is always rejected.
- Some web code checks global capabilities in addition to the resource list, for example `source-detail-page.tsx` requiring global `SOURCES_MANAGE` for access changes.

## Resources and keys

| Resource | Key | Decision (same as the guard) |
|---|---|---|
| Source | `edit` | global `SOURCES_MANAGE`, or scoped write through managed Groups (`SourceScopeSql.WRITE`): rename, Groups, upload, reindex, synchronize, schedule, pause/resume |
| Source | `delete` | global `SOURCES_DELETE`, or the actor's own groupless restricted Source while managing a Group |
| Source | `publish` | global `SOURCES_MANAGE`: change PUBLIC/RESTRICTED access |
| Source | `manage_configuration` | global `SOURCES_MANAGE`: Google Drive roots and linked-document discovery |
| Source | `remove_items` | global `SOURCES_DELETE` |
| Group | `manage` | ordinary Group, and global `GROUPS_MANAGE` or the actor manages it: rename, assign/remove managers |
| Group | `manage_members` | system Group: `SYSTEM_ADMIN`; ordinary Group: global `GROUPS_MANAGE` or the actor manages it |
| Group | `delete` | ordinary Group and global `GROUPS_MANAGE` |
| Group | `edit_permissions` | ordinary Group and `SYSTEM_ADMIN` |
| Group | `manage_sources` | global `SOURCES_MANAGE`, or an ordinary Group the actor manages |
| Persona | `edit` | owned Persona, or the built-in assistant with `MODELS_MANAGE` |
| Persona | `delete` | owned, non-built-in Persona |

Where a key exists in the reference, its name is reused. `manage_configuration`, `remove_items` and `manage_sources` are separate keys because MemoryOS guards those operations with a different decision than `edit` or `manage`.

The projections are pure functions in each capability's application layer (`SourcePermissions`, `GroupPermissions`, `PersonaPermissions`). They take the booleans the guards use. The SQL facts stay in the persistence repositories.

## Not covered (as in the reference)

- **Users and invitations** have no per-row map. The Users page stays behind the global `USERS_MANAGE`/`SYSTEM_ADMIN` capabilities its routes require.
- **Group member rows** have no map. The per-target guards (protected owner, a scoped manager removing themselves) stay as the service rules, and the web's existing row hints stay.
- **Chat Projects** are owner-only, so a map would be constant.
- **Route gating** of the administration area and the creation pages, which have no resource yet, stays on identity capabilities.
- **Tenant-level settings** such as Chat Web settings (`MODELS_MANAGE`).
- **Chat and Search.** Pages are not hidden by `CHAT_READ`/`CHAT_WRITE`/`SEARCH_READ`; their enforcement is MEM-93.

## Contract change

`actions` on `SourceSummary` and `GroupSummary` and `editable` on Personas are replaced by `permissions`. The API and web ship together, so no compatibility window is kept. Mutations still return their existing problem codes.
