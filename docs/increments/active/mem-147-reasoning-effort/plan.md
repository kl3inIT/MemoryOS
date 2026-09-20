# Implementation plan

One pull request on `kl3inIT/mem-147-reasoning`, one commit per step.

- [x] 1. Preferences: V88 adds `temperature_default` and `reasoning_effort_default` to `chat_preferences`;
      `ChatPreferences` carries both, validated (temperature 0–2, level in Off/Low/Medium/High).
      `GET/PUT /api/chat/preferences` and the generated client follow.
- [x] 2. Conversation: V88 adds `reasoning_effort_override` to the session table; a repository read at
      admission and one authorized endpoint to pin or clear it.
- [x] 3. Settling: one place that turns the conversation's level, the model configuration's options and
      the member's defaults into the effective options for a turn, in Onyx's order, without specializing
      the cached client binding. Helper flows (naming) keep the model's own configuration.
- [x] 4. Web — Settings › Chat: creativity and reasoning sliders with their current value, saved like the
      other preferences.
- [x] 5. Web — composer: the reasoning level inside the model picker, shown only for a reasoning model,
      pinned on the open conversation.
- [x] 6. Tests: HTTP (precedence, rejection of an unsupported level, a non-reasoning model sending no
      parameter), a unit test for the settling order, vitest for both surfaces, screenshots 1280/390.
- [x] 7. Docs: `docs/specs/chat.md`, `docs/specs/chat-models.md`, `docs/tests/chat.md`; `pnpm check` and
      `./gradlew clean check`.
