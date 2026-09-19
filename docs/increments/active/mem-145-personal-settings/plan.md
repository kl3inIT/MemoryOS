# Implementation plan

One pull request on `kl3inIT/mem-145-personal-settings`, one commit per step so it can ship early.

- [ ] 1. Settings shell: layout route with General · Chat · Connections · Usage; voice and prompt shortcuts move to Chat; theme cards and read-only profile in General.
- [ ] 2. Usage tab: `GET /api/ai-costs/mine`, spent this period, tokens per model, model prices, "No budget set".
- [ ] 3. Preferences: V86 `chat_preferences`, `GET/PATCH /api/chat/preferences`; personal default model in `resolve`/`availableModels`; role and preferences in the system prompt; start page; auto-scroll.
- [ ] 4. Delete all chats.
- [ ] 5. Connections tab over the existing MCP connection endpoints.
- [ ] 6. Collapse large pastes (or a recorded follow-up).
- [ ] 7. Tests: HTTP (own usage only, personal default and fallback, prompt section, delete-all refused while running), vitest per tab, screenshots 1280/390; `pnpm check`, `gradlew clean check`; specs and verification matrices.
