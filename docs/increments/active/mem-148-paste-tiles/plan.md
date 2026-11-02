# Implementation plan

One pull request on `phamnhatanh/mem-148-thu-gon-doan-dan-dai-thanh-the-trong-o-chat-onyx`, one commit
per concern.

- [ ] 1. Docs: this design and plan; an Active row in `docs/roadmap.md`.
- [ ] 2. Backend: V197 adds `chat_preferences.paste_as_tile`; `ChatPreferences`,
      `JdbcChatPreferencesRepository`, `ChatPreferencesRequest`/`ChatPreferencesResponse` carry it;
      `openapi.yml` gains the member only (inserted into the committed file); the Hey API client is
      regenerated.
- [ ] 3. Backend tests: the round-trip in `ChatSessionApiIntegrationTest` (default off, saved on, an
      absent member saves off) and the repository in `ChatPersistenceIntegrationTest`.
- [ ] 4. Web: the Settings › Chat switch; the paste tiles in `ChatThreadComposer` (threshold, tile, edit
      dialog, remove, joined send on Send and Enter, over-limit alert, session draft, Auto-Send hold).
- [ ] 5. Web tests: vitest for the threshold boundary, tile creation, edit, remove, joined send text,
      over-limit blocking and the settings switch; one Playwright spec for paste → tile → send, run
      locally alone.
- [ ] 6. Consolidate into `docs/specs/chat.md` and `docs/tests/chat.md`; gates: targeted Gradle tests,
      `clean check` once, the web gate's steps.
