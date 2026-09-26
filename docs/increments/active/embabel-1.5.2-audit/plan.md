# Plan

1. [x] `gradle/libs.versions.toml`: Embabel 1.5.2.
2. [x] Date:
   - `ChatPrompts.resolve` always dates the prompt;
   - `ChatTurnService` drops `CurrentDate`;
   - remove `datetimeAware` from the persona records, entity and repository;
   - V131 drops the column;
   - refresh `openapi.yml` and `web/src/lib/hey-api`;
   - remove the switch from the agent editor and its strings.
3. [x] `ModelCalls`: attempts from `embabel.agent.platform.llm-operations.data-binding.max-attempts`; system and user messages.
4. [x] Javadoc on `StreamingLlmService.supportsStreaming`; Chat and chat-models spec notes.
5. [ ] CI green on the pull request; owner approves the merge.
6. [ ] Staging: regenerate one meeting's minutes and compare them with the stored ones.
7. [ ] After merge: move this increment to `completed/`.
