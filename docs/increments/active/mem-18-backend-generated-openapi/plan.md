# MEM-18 implementation plan: backend-generated OpenAPI

## Foundation

- [x] Record the source-of-truth, runtime, snapshot, CI, and stacked-delivery boundaries.
- [x] Add the active increment to the repository map and roadmap.
- [x] Pin springdoc 3.x centrally and add the WebMVC API starter without Swagger UI.
- [x] Disable springdoc API-doc endpoints in normal runtime configuration.

## Live API description

- [x] Add one `/api/**` OpenAPI group with stable info, same-origin server, and browser-session/bearer security schemes.
- [x] Annotate Identity and Invitation operations with stable operation IDs, summaries, security, statuses, media types, and request/response schema constraints.
- [x] Preserve the MEM-16 RFC 9457 `ApiProblem` shape in generated error responses.
- [x] Exclude browser routes, OAuth callbacks, and actuator endpoints.

## Deterministic snapshot

- [x] Add a full-context MockMvc contract test that retrieves the grouped live document.
- [x] Verify the exact public API path set and semantic equality with `openapi.yml`.
- [x] Support explicit `MEMORYOS_OPENAPI_WRITE=true` refresh and emit the exact developer command on drift.
- [x] Refresh `openapi.yml` from the backend and prove a second normal run is stable.

## Client and durable documentation

- [x] Regenerate the committed Hey API client from the refreshed snapshot.
- [x] Update architecture, conventions, README commands, and verification matrices with the generated-contract ownership and drift gates.
- [x] Record exact static, focused, repository, frontend, and runtime evidence in `verification.md`.

## Verification and delivery

- [x] Inspect every changed Java, Kotlin DSL, and YAML file with JetBrains warnings enabled, then compile affected modules.
- [x] Run the focused live contract test in compare mode.
- [x] Run `gradlew.bat clean check --no-daemon`, `pnpm check`, and the changed browser/runtime smoke path.
- [ ] Review, commit, push, and open a stacked PR against the MEM-16 branch; attach it to MEM-18 and move Linear to review.
