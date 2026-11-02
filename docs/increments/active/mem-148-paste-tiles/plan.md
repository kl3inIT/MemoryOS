# Implementation plan

One pull request on `phamnhatanh/mem-148-thu-gon-doan-dan-dai-thanh-the-trong-o-chat-onyx`, one commit
per concern.

- [x] 1. Docs: this design and plan; an Active row in `docs/roadmap.md`.
- [x] 2. Backend: V198 adds `chat_preferences.paste_as_tile`; `ChatPreferences`,
      `JdbcChatPreferencesRepository`, `ChatPreferencesRequest`/`ChatPreferencesResponse` carry it;
      `openapi.yml` gains the member only (inserted into the committed file); the Hey API client is
      regenerated.
- [x] 3. Backend tests: the round-trip in `ChatSessionApiIntegrationTest` (default off, saved on, an
      absent member saves off), which runs the repository on PostgreSQL; no separate core test.
- [x] 4. Web: the Settings › Chat switch; the paste tiles in `ChatThreadComposer` (threshold, tile, edit
      dialog, remove, joined send on Send and Enter, over-limit alert, session draft, Auto-Send hold).
- [x] 5. Web tests: vitest for the threshold boundary, tile creation, edit, remove, joined send text,
      over-limit blocking and the settings switch; one Playwright spec for paste → tile → send, run
      locally alone.
- [x] 6. Consolidate into `docs/specs/chat.md` and `docs/tests/chat.md`; gates: targeted Gradle tests,
      the web gate's steps; `clean check` is open (see below).

## Verification so far

- Web: `tsc -b`, `oxlint --deny-warnings`, `oxfmt --check`, `i18n-audit --check`; vitest for
  `src/features/chat/composer`, `thread`, `settings`, `agent-editor.test.tsx`, `channel-direct-assistant.test.tsx`
  and `src/features/identity`; `chat-paste-tiles.spec.ts` alone against the fixture server (Chromium).
- Backend: main and test sources of core, api, worker and channels compile; `ChatTurnSamplingTest`,
  `ModulithArchitectureTest`, `CoreDependencyRulesTest`.
- Open: no Docker daemon was available on the development machine, so `OpenApiContractTest`, the PostgreSQL-backed
  `ChatSessionApiIntegrationTest` case and `clean check` are left to CI; `openapi.yml` was edited by hand with the
  two members and the client regenerated from it.
