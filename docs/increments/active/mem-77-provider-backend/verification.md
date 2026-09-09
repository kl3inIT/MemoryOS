# Backend verification — 2026-09-09

Implementation is on `feat/mem-77-provider-backend`; the checks below were run against the initial main base `287ca9c4bb79878bf49855934bddd4781f1c931a`. Publication and CI evidence are tracked on the pull request. This evidence does not close MEM-77, certify a local provider, or establish deployment of this change.

| Check | Observed result |
| --- | --- |
| `gradlew.bat clean check --no-daemon` | PASS, all 23 tasks across core/API/connector/worker; 5m47s. Existing optional live checks remain separate. |
| Final focused Chat/catalog + API/OpenAPI tests | PASS: core 35/35; API 21 passed, 1 optional live check skipped. Includes PostgreSQL constraints, access/defaults, secret encryption, configuration races, second adapter and native SDK HTTP request assertions. |
| Live OpenAI check with `MEMORYOS_CHAT_LIVE_TEST=true` | PASS, 1 test, zero skips. Credential read from Infisical dev only into the child process environment; normal send, native runner and persisted history were exercised. |
| OpenAPI generation and typed success-response assertions | PASS; generated Hey API client includes concrete catalog results rather than `unknown`. |
| `corepack pnpm@11.22.0 --dir web check` | PASS: 64 tests, lint/typecheck/generated stability and production build. Existing Chat chunk-size advisory remains. No UI feature was added. |
| JetBrains file inspection and project build | All changed Java/Kotlin DSL/configuration metadata files inspected with warnings included; IDE build successful with no build problems. Custom HTTP-header/internal-HTTP hints and framework/Mockito ownership conventions were reviewed. The unchanged application YAML has existing IDE classpath-resource resolution diagnostics; both shared resources load successfully in the real Gradle API/worker contexts. |
| Infisical configuration | Created `MEMORYOS_CHAT_CATALOG_ENCRYPTION_KEY` independently in dev/staging and verified readback. Values were never printed or written into repository files. Environment resolution uses the existing Spring property mechanism; preserve each key with its database backups. |

The first broad gate exposed excessive heap use from constructing a separate O200K tokenizer vocabulary for each native binding. The immutable vocabulary is now shared across configurations/revisions. The subsequent full gate passed. Test HTTP clients and native provider clients have explicit cleanup; factory leases are released on dispatch rejection and execution completion/cancellation/failure.

The HTTP provider/issuer tests use local fixtures and real application filters, database transactions and the native OpenAI SDK. The additional live model check proves the existing OpenAI model path, not real Keycloak login, arbitrary local model behavior, hosted web search, image generation or UI model selection.

Temporary scripts, process diagnostics and raw local test logs stayed under ignored `.tmp/`. No QA profile, test credential, provider fixture or operational bypass was added to production runtime. Production source includes the real OpenAI adapter only; the second adapter exists exclusively in the test source set.
