# Plan

- [x] Sources: `SourcePermissions` projection (`edit`, `delete`, `publish`, `manage_configuration`, `remove_items`) fed by the guard predicates; replaces `JdbcSourceQueryRepository.actions` and `SourceSummary.actions`; typed `permissions` in OpenAPI.
- [x] Groups: `GroupPermissions` projection (`manage`, `manage_members`, `delete`, `edit_permissions`, `manage_sources`) replaces `GroupSummary.actions`.
- [x] Personas: `PersonaPermissions` (`edit`, `delete`) replaces `editable`.
- [x] Web: typed vocabulary and fail-closed `can()`; Source detail/list, Google Drive panel, Group pages/cards/members/sources and Persona page read `permissions`; remove per-resource global capability checks that duplicate a key.
- [x] Tests: for a global administrator, a scoped Group manager and a plain member, each key is true exactly when the guarded mutation is accepted (Source, Group, Persona); web `can()` fail-closed unit test.
- [x] Specs (`connector`, `identity`, `chat`), test matrices, verification; moved to completed after PR #135 merged.
