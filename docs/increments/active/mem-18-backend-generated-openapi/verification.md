# MEM-18 verification

Date: 2026-08-22

## Implemented contract

- Added springdoc 3.0.3 `springdoc-openapi-starter-webmvc-api` without Swagger UI and kept `springdoc.api-docs.enabled=false` in normal runtime configuration.
- Added one `browser` OpenAPI group limited to `/api/**`, stable same-origin metadata, browser-session and bearer security schemes, and the MEM-16 `ApiProblem` schema.
- Identity and Invitation controllers now own stable operation IDs, response statuses/media types, security requirements, header/path metadata, and request/response schema constraints.
- `OpenApiContractTest` starts the full API context, retrieves `/v3/api-docs/browser` through MockMvc, asserts the exact five-path browser API set, and compares the generated semantic document with committed `openapi.yml`.
- `MEMORYOS_OPENAPI_WRITE=true` is the only snapshot write path. The API test task tracks both `openapi.yml` and the write flag as inputs, so contract edits and write/compare transitions cannot be skipped as Gradle up-to-date work.
- Regenerated the committed Hey API client from the backend-generated snapshot. Public operation names and `ApiProblem` semantics remain stable.
- `.github/workflows/ci.yml` needs no new job: its existing backend `clean check` and frontend `pnpm check` jobs already execute both drift gates on every pull request.

## Behavioral evidence

- A normal focused contract run failed against the previous hand-maintained snapshot, proving backend/snapshot drift detection.
- The explicit write-mode run refreshed `openapi.yml`; a subsequent compare-mode run passed without another change.
- `OpenApiContractTest.committedContractDescribesOnlyTheLiveBrowserApi` excludes browser redirects, OAuth callbacks, static routes, and actuator endpoints while retaining Identity and Invitation operations.
- `ApiApplicationSmokeTest.apiDocumentationEndpointIsDisabledByDefault` starts the normal API configuration and verifies the grouped springdoc route has no MVC handler (`404`) when security filters are bypassed for handler inspection.
- `pnpm test:e2e` passed all 9 Chromium browser contracts against the regenerated client.

## Static and repository gates

- JetBrains inspections with warnings enabled reported no problems in every changed Java, Kotlin DSL, YAML, and TOML file.
- LSP diagnostics reported no issues across the generated Hey API TypeScript tree.
- `./gradlew.bat :api:compileJava :api:compileTestJava --no-daemon` passed.
- `./gradlew.bat :api:test --tests "*OpenApiContractTest*" --tests "*ApiApplicationSmokeTest*" --rerun-tasks --no-daemon` passed with all focused tasks executed.
- `./gradlew.bat clean check --no-daemon` passed.
- `pnpm --dir web check` passed generated-client stability, CI image policy, lint, format, TypeScript, unit tests, route stability, and production build.
- `pnpm --dir web test:e2e` passed 9/9 Chromium contracts.

## Remaining delivery boundary

MEM-18 is stacked on the unmerged MEM-16 head because generated Problem Detail metadata depends on that contract. Its pull request targets the MEM-16 branch and can be retargeted to `main` after PR #14 merges.

## Pull-request evidence

- PR #15 targets the MEM-16 feature branch intentionally and carries only the MEM-18 delta.
- Implementation-head CI run `32563070515` passed backend, frontend, and production frontend-image jobs for `4b3d1986afdfa3fbf879d5ec3e920945485901ac`.
- The single requested CodeRabbit review was rate limited. The bounded evidence collection found no submitted review, inline comment, or unresolved review thread. No merge fallback was used.
