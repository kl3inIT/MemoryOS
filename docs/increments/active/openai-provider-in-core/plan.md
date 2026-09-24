# Plan

- [x] `git mv` the eleven provider classes, their ten unit tests and `known-models.json` into `core` under `chat.catalog.openai`; change only package, import and visibility lines, and drop the unused Swagger import in `ChatModelValidation`.
- [x] Add the `@NamedInterface("openai")` package-info; move the `okhttp` declaration from `api` to `core`; point `scripts/sync-chat-known-models.mjs` at the new resource path.
- [x] Update `ARCHITECTURE.md`, the MEM-77 plan link, `AGENTS.md` and the audit owner decisions.
- [ ] Full `clean check` in GitHub CI after push.

## Verification — 2026-09-24

The machine was low on memory, so the full gate was not run locally.

- `./gradlew :core:compileJava :core:compileTestJava :api:compileJava :api:compileTestJava :worker:compileJava --no-daemon`: passed.
- `:core:test --tests 'io.memoryos.chat.catalog.openai.*' --tests io.memoryos.ModulithArchitectureTest --tests io.memoryos.CoreDependencyRulesTest`: passed. That is 53 moved tests in 10 classes, plus 1 Modulith test and 2 dependency-rule tests.
- `:api:test --tests '*OpenApiContractTest*' --tests '*ChatModelCatalogConfigurationTest*'`: passed, 1 + 3 tests. The contract test starts the full API context with the configuration picked up from `core`.
- `:api:test --tests 'io.memoryos.api.chat.ChatSessionApiIntegrationTest.configuredProviderRunsThroughAuthenticatedHttpNativeSdkAndPersistedOutcome'`: passed, 2 of 2. This runs the classes compiled in `core` on the API's OkHttp 5.4.0 runtime.
- `git diff origin/main -- openapi.yml web/`: empty.
