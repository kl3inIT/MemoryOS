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

## Delivery history

PR #14 merged before MEM-18. GitHub auto-closed stacked PR #15 when its deleted MEM-16 base branch disappeared, so replacement PR #16 targeted `main` from the same synchronized MEM-18 branch.

## Pull-request evidence

- PR #15 carried the original implementation delta and implementation-head CI run `32563070515` passed backend, frontend, and production frontend-image jobs.
- Replacement PR #16 head CI run `32563615988` passed the same three jobs after merging exact `main`; the merge changed no MEM-18 file content.
- The single PR #16 CodeRabbit pass found one valid schema defect: RFC 9457 `instance` emits an absolute-path URI-reference such as `/api/invitations`, not an absolute URI. The source schema now declares `uri-reference`, the generated snapshot was refreshed, and the live contract test pins that format.
- PR #16 reviewed head `7342bf906cdbe4e9e7b6c6c4d0724ef10b52c4ec` merged as `be4e3418e0d293e30826c615a69602f96583a93c`; exact merge-SHA CI run `32564368174` passed backend, frontend, and production frontend-image jobs.
