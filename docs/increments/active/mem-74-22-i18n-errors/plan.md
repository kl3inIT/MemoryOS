# Implementation plan

- [x] Re-read MEM-74/MEM-22 and the existing MemoryOS identity, settings and Chat contracts; record scope before code.
- [x] Account locale migration, IAM persistence/service, self API, identity projection and contract tests.
  - [x] Replace the language-specific EntityManager repository with Spring Data `JpaActorRepository`; retain only locked refresh in a custom fragment and cover already-managed state.
- [x] Bundled typed i18next catalogs, account synchronization and General settings with save/recovery tests.
- [x] Stable validation metadata and shared localized problem presentation, consumed by the application surfaces below.
- [x] Users/invitation end-to-end localization and inline error placement.
  - [x] Shared problem descriptors for user/invitation/group-edit errors; email field association; no duplicate dialog/row failure; errors retranslate while open.
  - [x] Remaining labels, loading/empty states, dates/counts, table/filter accessibility and success notices.
- [x] Chat/attachments/Search/reader localization; per-turn MemoryOS language hint and tests.
  - [x] Server-side per-turn language hint, fallback/Persona composition and snapshot tests.
  - [x] Unify message-file and citation readers in the existing responsive right panel using the assistant-ui artifacts example; preserve focus/draft/runtime and authority checks.
  - [x] Horizontal composer attachment cards, localized error/connection/feedback/Sources elements, and focused regression checks. Catalog/example decisions: [audit](chat-elements-audit.md). Full-app localization and renderer implementation are covered by this completion batch.
- [x] Remaining shell/admin/source/group states, shared accessibility labels and Intl formatting.
- [x] Catalog parity and no-new-hardcoded-string checks; unit/API/browser verification in both languages.
- [x] Finish remaining UI catalogs and consumers, then add a repeatable untranslated-string audit.
- [x] Runtime-aware lazy Shiki and Mermaid renderers, bounded inputs, inert diagram images, safe fallback, copy/zoom labels and browser tests.
- [x] Generative UI: implement read-only cards/tables/facts through a bounded backend `render_gui` tool and persisted message artifacts, rendered with assistant-ui's allowlisted primitive. No forms, arbitrary actions, HTML execution or OCR changes. The optional scope question has not received an answer; use this non-mutating scope as announced.
- [x] Static analysis fallback (JetBrains unavailable in the completion session), full repository/frontend gates and canonical spec/test documentation. See verification for opt-in skips, browser reruns and live-acceptance boundaries.

Keep partial checkpoints explicit. Do not mark MEM-74/MEM-22 complete after only installing i18n or implementing the settings picker. Preserve OCR and unrelated `output/` artifacts. The publication follow-up authorizes scoped commits and a PR; no new worktree, merge, deploy or Linear update.
