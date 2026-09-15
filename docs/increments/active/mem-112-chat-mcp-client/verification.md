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

Every Gradle test suite has therefore passed, but across two runs rather than one uninterrupted `clean check`. CI remains the single-run gate. Also not run: the full web `pnpm check` and live Google Drive MCP (deferred by the owner). JetBrains MCP was unavailable, so no IDE-inspection claim is made.
