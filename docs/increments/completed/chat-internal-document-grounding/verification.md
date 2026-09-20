# Verification

## Reproduction — 2026-09-18

- PostgreSQL showed all three relevant SharePoint Documents with `searchable_generation = content_generation`, no `search_error_code`, and successful current Search operations.
- A direct authorized-development OpenSearch query for `cam kết bảo mật` returned passages from `NguyenDucAnh-Cam-ket-bao-mat.docx` and `.pdf`.
- The completed Chat turn persisted `sources=[]` and `activity.steps=[]`; the selected model explicitly decided not to use tools and answered from general knowledge.

This isolates the regression to model tool selection rather than SharePoint acquisition, extraction, Search projection or retrieval content.

## Reference and framework evidence

- Onyx checkout `.tmp/onyx` revision `06aa2b0`: `backend/onyx/prompts/tool_prompts.py` supplies automatic document-search guidance; `backend/onyx/chat/llm_loop.py` uses automatic tool choice unless a client `forced_tool_id` selects one required tool for the first cycle.
- Context7 official Spring AI reference: portable `ChatOptions` has no tool-choice property; OpenAI and Anthropic expose different provider-specific forcing options. The implementation therefore keeps the existing provider-neutral prompt path.

## Automated verification

```text
.\gradlew.bat :core:test --tests "io.memoryos.chat.prompts.ChatWebPromptsTest" --tests "io.memoryos.chat.prompts.ChatLanguagePromptTest" --no-daemon
BUILD SUCCESSFUL

.\gradlew.bat :core:check --no-daemon
BUILD SUCCESSFUL

.\gradlew.bat clean check --no-daemon
BUILD SUCCESSFUL in 3m 23s
```

The focused regression proves the mandatory internal-grounding text appears for internal-only and combined tool sets, and is absent for Web-only requests. Existing prompt coverage continues to verify Persona preservation, one Tools heading and actual-tool discovery.

## Browser acceptance

The Playwright skill opened `http://127.0.0.1:8080`, completed the MemoryOS splash and reached the real MemoryOS Keycloak login page. The isolated Playwright profile has no user credentials, so authenticated live-model acceptance was not run. No credentials were requested, copied or stored.

After API restart on the new build, repeat the Vietnamese question in an authenticated session. Acceptance requires a visible `search_knowledge` activity step, at least one citation resolving to the SharePoint agreement, and persisted nonempty `sources`. Plausible uncited prose is a failure.
