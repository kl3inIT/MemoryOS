# Implementation plan

One pull request on `kl3inIT/mem-145-personal-settings`, one commit per step so it can ship early.

- [x] 1. Settings shell: layout route with General · Chat · Connections · Usage; voice and prompt shortcuts move to Chat; theme cards and read-only profile in General.
- [x] 2. Usage tab: `GET /api/ai-costs/mine`, spent this period, tokens per model, model prices, "No budget set".
- [x] 3. Preferences: V86 `chat_preferences`, `GET/PUT /api/chat/preferences`; personal default model in `resolve`/`availableModels`; role and preferences in the system prompt; start page; auto-scroll.
- [x] 4. Delete all chats.
- [x] 5. Connections tab over the existing MCP connection endpoints.
- [x] 6. Collapse large pastes: recorded as MEM-148.
- [x] 7. Tests: HTTP (own usage only, personal default and fallback, prompt section, delete-all cancels running replies), vitest per tab, screenshots 1280/390; `pnpm check`, `gradlew clean check`; specs and verification matrices.
