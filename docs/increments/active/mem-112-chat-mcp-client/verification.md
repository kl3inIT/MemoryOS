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

Repository gate not yet green: `gradlew clean check --no-daemon --max-workers=2` failed only because the host ran out of native memory (14 GB RAM, ~3 GB free). The `:api:test` executor and the `:connector:test` Tika extraction subprocess both died with `hs_err` "insufficient memory … malloc failed", so `TikaSourceContentExtractorTest` reported `ExtractionException`. The `--max-workers=1 --continue` rerun was killed for low memory during `:api:test`. Rerun on a host with free memory or in CI before merge. Also not run: the full web `pnpm check` and live Google Drive MCP (deferred by the owner). JetBrains MCP was unavailable, so no IDE-inspection claim is made.
