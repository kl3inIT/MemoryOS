# Localization verification

- Preference: `ActorLanguageServiceTest`, `JpaActorProfileRecorderTest`, bearer identity integration cover active-member authority, CSRF, stale managed state, profile refresh and readback.
- Language hints: `ChatLanguagePromptTest` and `ChatTurnSetupTest` cover precedence and per-turn snapshots.
- Catalogs: `catalog.test.ts`, `app-translation.test.tsx`, `check:i18n` cover keys/placeholders, HTML language, registry/provider copy, nested errors, inert filenames and locale formatting.
- Settings, session, Users, ConfirmDialog, Chat dialog and problem tests preserve drafts/DOM on language changes and transient failures, without duplicate writes or raw diagnostics.
- Existing browser suites assert selected account locale; public invitation screens default to Vietnamese. Renderer cases cover English desktop/Vietnamese mobile, draft/focus and reload.
- Production CSP: built assets must show Shiki colored tokens without WASM/eval allowances, Mermaid images and inert model strings under checked-in CSP. A local fixture is not live-provider/staging acceptance.

Run frontend `check`, browser tests and repository `clean check`. Latest evidence, including failed gates, is in the active MEM-74/MEM-22 verification document.
