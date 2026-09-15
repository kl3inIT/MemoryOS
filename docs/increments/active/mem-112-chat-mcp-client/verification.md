# MEM-112 verification

## Phase 1 — 2026-09-15

Branch `mem-112/chat-mcp-client`, based on main `82ef9050` (MEM-110 merged). JDK 25.0.2, Gradle 9.7.1, Docker 29.7.2 for Testcontainers.

| Check | Command | Result |
| --- | --- | --- |
| Core compile | `gradlew :core:compileJava :core:compileTestJava` | Passed |
| MCP client against a real SDK server | `McpClientsIntegrationTest` (embedded Tomcat, `HttpServletStreamableServerTransportProvider`, bearer check in front) | 7 tests passed: tool listing with schemas/annotations; text, `isError` and structured-content conversion (text starting with `[` is not mistaken for an omission marker); rejected bearer (401) and `insufficient_scope` (403) → `MCP_AUTHORIZATION_REQUIRED`; 700 ms deadline → `MCP_TIMEOUT`; closed port → typed failure without host detail; endpoint/header policy (internal hosts allowed; credentials, query, fragment, reserved and CR/LF headers rejected) |
| Secret sealing | `McpSecretsTest` | 4 tests passed: Tenant/record/purpose binding, fresh IV, tamper and wrong-key rejection, missing/invalid key handling |
| V63 schema and entity mapping | `McpPersistenceIntegrationTest` (PostgreSQL 18.4, Hibernate `validate` over IAM, Chat and MCP entities) | 4 tests passed: several labelled OAuth clients per server, Group access, tool snapshot, per-User credential; slug uniqueness, OAuth mode and `NONE` performer checks, cross-Tenant Group rejection, one credential per owner and one shared, client bound to its own server, cascade on server delete, object-only tool schema, `MCP_MANAGE` grantable and unknown capabilities rejected |
| Capability fan-out | `IamCapabilityTest`, `DefaultGroupServiceAuthorizationTest`, `GroupSchemaIntegrityTest`, `GroupMigrationSeedTest` | 24 tests passed |
| Module boundaries | `ModulithArchitectureTest`, `CoreDependencyRulesTest` (`mcp.persistence` owned by `mcp`) | Passed with eight closed modules |
| OpenAPI contract | `MEMORYOS_OPENAPI_WRITE=true gradlew :api:test --tests io.memoryos.api.OpenApiContractTest` | Passed; `GroupCapability` enums gained `MCP_MANAGE`. The first attempt's test JVM was killed by host native-memory exhaustion (`hs_err` "insufficient memory … G1 virtual space"); the unchanged rerun passed and the crash log was removed |
| Web client and types | `pnpm generate:api`, `pnpm exec tsc -b --noEmit` | Generated types updated; typecheck passed |
| Web capability copy | `vitest run src/i18n/app-translation.test.tsx src/features/groups`, `pnpm check:i18n`, `oxlint --deny-warnings`, `oxfmt --check` on changed files | 9 tests passed; Vietnamese translations added for the new label and description; audit, lint and format clean |

Repository gate on commit `e46c6c8d`, on a host with 14 GB RAM and 1.5–3 GB free:
- **First runs:** `--max-workers=2` died of native-memory exhaustion (`hs_err` in the `:api:test` executor and the `:connector:test` Tika subprocess). A `--max-workers=1` rerun was killed by the OS for low memory.
- **`gradlew clean check --no-daemon --max-workers=1 --continue`:**
  - `:api:check`, `:connector:check` and `:worker:check` passed.
  - `:core:test` lost one test JVM to `OutOfMemoryError: Java heap space` (1 GiB fork heap). The error pointed at the 100 MiB allocation in `ObjectWriteLifecycleIntegrationTest.binaryWritesRejectOneByteBeyondOneHundredMiB`, although that suite's report records 8/8 passed.
  - 81 of 94 core suites reported, with no failures. The 13 that never ran were the remaining `objectstorage` and `retrieval` suites.
- **Rerun:** `gradlew :core:test --max-workers=1 --tests 'io.memoryos.mcp.*' --tests 'io.memoryos.objectstorage.*' --tests 'io.memoryos.retrieval.*'` passed 18 suites and 95 tests, with 0 failures and 1 opt-in measurement skipped. The heap error did not reproduce.

Every Gradle test suite has therefore passed, but across two runs rather than one uninterrupted `clean check`. CI remains the single-run gate.

## Phase 2a — 2026-09-16

| Check | Command | Result |
| --- | --- | --- |
| Server rules | `gradlew :core:test --tests io.memoryos.mcp.McpServerRulesTest` | 7 tests passed: slug, header template (`{api_key}` only on API-key servers, reserved and duplicate headers), default bearer header and key substitution, OAuth scope/parameter rules, model tool-name limits, snapshot bounds and hints, fail-closed stored JSON |
| Module boundaries | `ModulithArchitectureTest`, `CoreDependencyRulesTest` | 3 tests passed |
| IAM effective authorization | `gradlew :core:test --tests 'io.memoryos.iam.group.*'` | 41 tests passed |
| Administration API | `ChatSessionApiIntegrationTest.mcpAdministrationSealsSecretsRefreshesToolsAndFencesRevisions` (shared context, in-process Streamable HTTP server requiring the resolved headers) | Passed: `MCP_MANAGE` required; key and static header value sealed (`v1:`) and absent from responses and request `toString`; duplicate slug 409; refresh lists tools with the sealed key and template, status `CONNECTED`; over-long composed tool name refused at enablement; stale revision 409 `MCP_CONFLICT`; URL change removes credentials and tools (`AWAITING_AUTH`); userinfo URL 400; delete then 404. The catalog test in the same class still passes |
| OpenAPI contract | `MEMORYOS_OPENAPI_WRITE=true gradlew :api:test --tests io.memoryos.api.OpenApiContractTest` | Passed with seven `/api/mcp` paths; the other diff is operation reordering only (no operation removed) |
| Web client | `pnpm generate:api`, `tsc -b --noEmit`, `oxlint --deny-warnings` and `oxfmt --check` on changed files, `vitest run src/features/groups src/i18n/app-translation.test.tsx` | Generated; typecheck, lint and format clean; 9 tests passed |

Phase 1 defect found by the API test: the effective-capability SQL in `IamAuthorizationRepository` and `GroupProjectionRepository` listed ordinary grants explicitly without `MCP_MANAGE`, so a granted Group did not authorize. Both lists now include it; the API test is the regression.

Not run for 2a: `gradlew clean check` and the full web `pnpm check`. Also not run: the full web `pnpm check` and live Google Drive MCP (deferred by the owner). JetBrains MCP was unavailable, so no IDE-inspection claim is made.
