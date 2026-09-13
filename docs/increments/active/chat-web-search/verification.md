# Web search verification — local slice, 2026-09-13

This is local implementation evidence, not deployment, paid-provider acceptance or completion of the entire approved increment. Reference checkout: Onyx `40eb240df`.

## Passed checks

- Core: 26 tests across `WebHttpTest` (4), `WebProviderClientTest` (6), `WebToolsTest` (4), `ChatModelGuardTest` (9), `CoreDependencyRulesTest` (2) and `ModulithArchitectureTest` (1); zero failures. The same invocation compiled Worker; API/core compilation passed separately.
- API: `OpenApiContractTest` (1), `OpenAiChatProviderAdapterTest` (3) and the Web method in `ChatSessionApiIntegrationTest` (1); zero failures. The final run compared the checked-in OpenAPI contract without the write flag.
- The Web integration method uses real PostgreSQL/Flyway/JPA, authorization/CSRF, encrypted credential storage, a real local HTTP search server and the native Embabel tool loop with a mocked model. It checks manager-only connection reads, configuration without network/activation, explicit selection, Send/Edit/Regenerate, persisted URL citations, replay reuse, changed-intent conflict and off without another provider request. It does not call a paid provider.
- Frontend: 34 tests in `chat-web.test.tsx` and `chat-transport.test.ts`; zero failures. Lint, i18n audit, TypeScript, formatting of touched Web files and Vite production build passed. The i18n audit reports zero missing static keys/direct UI literals. Build retains the existing large renderer chunk and runtime-config script notices.
- OpenAPI and generated client were refreshed through the existing generator. No handwritten client/schema fork was introduced.
- Browser CLI with synthetic identity/provider/evidence: desktop one-row composer Web menu, separate seven-provider settings, 390 px settings/menu layout, real Web element rendering from event metadata, URL citation opening the existing right panel and mobile dialog, and source restoration after reload. The original-page link remained `https://example.com/releases`; no indexed Document reader was substituted. Opening a source now closes its citation tooltip.

Local screenshots under ignored `output/playwright/`: `web-menu-desktop.png`, `web-settings-mobile.png`, `web-source-desktop.png`, `web-source-mobile.png`. The fixture browser has the existing `/runtime-config.js` 404; API-client regeneration caused transient Vite HMR 404s, followed by a successful reload. These screenshots are not staging/model answer-quality evidence.

## Static inspection boundary

Every changed Web Java/Kotlin file was inspected with JetBrains warnings enabled, including the adapter capability change and tests. No Java errors remain. Retained diagnostics: existing inverted-helper/unused-return suggestions, conventional Hibernate `@Version` assignment and new JPA table/column names absent from the IDE datasource snapshot, plus intentional HTTP/custom-header test fixtures. Real Flyway migration/JPA startup and the persistence integration test passed; no staging/IDE database was migrated to silence those warnings.

`openapi.yml` inspection returned no findings but timed out at both 10 and 30 seconds. This is not an IDE-clean YAML claim; the actual Spring-generated contract comparison and client generation passed instead. The final focused core/API compiles/tests establish the Java compile floor, not a whole-repository gate.

## Remaining accepted work

- Native provider-hosted search using existing LLM credentials: OpenAI/Gemini/Anthropic protocol adapters, native events/citations/continuation and provider usage. None is exposed as enabled or inferred from a model name. Current required mode forces the external `web_search` function through Chat Completions; it is not native hosted search.
- Live key/provider/model acceptance and repeat final gates after native adapters before release. The external slice's full backend gate passed; no targeted selector/UI recheck was added.
- Batch admission limits (8 queries/5 URLs) are MemoryOS bounds; parallel execution reuses the existing four-worker SearchTasks implementation, not Onyx's five-worker URL reader. Chat title length remains the accepted MemoryOS eight-word rule rather than Onyx's five-word rule.
- No commit, PR, push, deploy, paid probe, Linear update or OCR change was made in this implementation slice. Unrelated checkout changes were preserved.

## Reproduction

### PDF, site guidance, preferences and dependency follow-up

Implemented provider-specific `site:` request guidance, built-in text PDF reading without OCR and actor/session browser preference restoration. Jsoup/PDFBox versions are centralized in the catalog; the remaining identified inline qualified types in Web provider/controller/tests are imports. Direct parser reuse versus Spring AI readers is explained in [design](design.md#spring-ai-reader-versus-direct-parser-reuse).

Focused core checks passed: `ChatWebPromptsTest`, `WebToolsTest`, `WebPdfReaderTest`, `WebProviderClientTest`; API and Worker compiled. PDF tests use real generated PDF bytes through the normal provider path and cover unreadable input, truncation and cancellation. Frontend: 37 tests across Web preference/transport/picker, plus typecheck, lint, i18n audit (zero findings) and production build passed. Build keeps existing renderer chunk/runtime-config notices. These are local contract checks, not a deployed browser or paid provider run.

The Java/Kotlin follow-up files, including the final import cleanup, received JetBrains inspection with warnings enabled. Jsoup resolution is now clean. The PDF writer nullability warning was fixed and re-inspected; only an intentional HTTP SSRF test fixture weak warning remains. The whole-repository `clean check --no-daemon` passed in 11m 5s across Core, API, Worker and Connector. Core's long phase progressed through real PostgreSQL/Flyway fixtures; read-only activity inspection found no blocked database clients. No native provider adapter has been enabled, and no OCR, live secret, deployment or Git publication action was taken.

Full-gate totals before the final import-only cleanup: Core 443, API 135 (4 skipped), Worker 27, Connector 104 (4 skipped); zero failures/errors. The eight skipped cases are explicit opt-in API live checks, Docling integration and file resource measurement, not Web acceptance. After the final import-only cleanup, 25 focused core Web tests and the actual Web API integration test passed again; all affected modules compiled (`BUILD SUCCESSFUL`, 1m 13s). No live/paid acceptance is inferred from either gate.

### Batch tools, prompt guidance and Java readability follow-up

The follow-up on 2026-09-13 passed 44 focused core tests and one real PostgreSQL/local HTTP API integration test, zero failures; core/API/Worker compilation passed (`BUILD SUCCESSFUL`, 1m 3s). The API test executes two queries through the actual Embabel tool loop and checks the outbound guidance and post-search reminder, including Send/Edit/Regenerate, replay and off. The model is mocked; this is not paid-provider acceptance.

Java methods now use camelCase while annotations retain `web_search`, `open_url`, `read_file`, `search_files` and `render_gui`. Newly written prompt/tool code uses imports and short type names; the cross-project rule is recorded in [conventions](../../../conventions.md#java-and-gradle).

JetBrains inspections included warnings for all follow-up Java files. Prompt/tool/test files have no new findings; the existing synthetic JSON-construction and intentional HTTP/custom-header fixture warnings remain. After import cleanup, the IDE still reports unresolved Jsoup references in `WebProviderClient`, despite its declared Gradle dependency, a successful targeted IDE build and the successful Gradle compilation/provider tests. The IDE dependency/index state remains to reconcile; this is not a fully IDE-clean claim. `git diff --check` passed. No selector recheck, OCR change, commit or deployment was performed.

```powershell
.\gradlew.bat :core:test --tests '*ChatWebPromptsTest' --tests '*ChatLanguagePromptTest' --tests '*WebToolsTest' --tests '*ChatModelGuardTest' --tests '*ChatTurnSetupTest' --tests '*ChatArtifactTest' --tests '*FileReaderToolTest' --tests '*WebProviderClientTest' :api:test --tests '*ChatSessionApiIntegrationTest.webConfigurationAndChatUseRealPersistenceHttpToolsAndIdempotentIntent' :worker:compileJava --no-daemon
```

### Original external Web slice

```powershell
.\gradlew.bat :core:test --tests '*WebHttpTest' --tests '*WebProviderClientTest' --tests '*WebToolsTest' --tests '*ChatModelGuardTest' --tests '*CoreDependencyRulesTest' --tests '*ModulithArchitectureTest' :worker:compileJava --no-daemon
.\gradlew.bat :api:test --tests '*OpenApiContractTest' --tests '*OpenAiChatProviderAdapterTest' --tests '*ChatSessionApiIntegrationTest.webConfigurationAndChatUseRealPersistenceHttpToolsAndIdempotentIntent' --no-daemon
pnpm --dir web exec vitest run src/features/chat/chat-web.test.tsx src/features/chat/chat-transport.test.ts
pnpm --dir web lint
pnpm --dir web check:i18n
pnpm --dir web build
```
