# MEM-46 — Implementation evidence

## 2026-09-08 — Start and runtime prerequisites

- User approved implementation of the single Onyx-aligned normalized hybrid Search flow and selected OpenAI credentials for embedding.
- Branch: `mem-46-search`; existing unrelated planning files preserved.
- Selected embedding: `text-embedding-3-large`, 3072 dimensions. Values are not shortened; vectors remain only in OpenSearch.
- Infisical project configured by `.infisical.json`: `SPRING_AI_OPENAI_API_KEY` stored in `dev` and `staging`; retrieved values compared in memory and matched. No credential value is recorded here.
- A minimal Vietnamese embedding request to the official OpenAI endpoint returned HTTP 401. Endpoint/key clarification requested; live embedding acceptance remains pending.
- JetBrains MCP connection repaired from obsolete port 64342 to `http://127.0.0.1:65400/stream` in both user and Orca-managed Codex configuration. Initialized IntelliJ IDEA MCP Server 2026.1.1 and fetched live inspection schemas.
- Docker server responds with version 29.7.2.

## Implemented and verified locally

- Branch `mem-46-search`, based on `de803928f109b62c1db47cdc558d432a792c7333`; not committed, pushed, merged or deployed in this run.
- V17 current chunk/search generations, chunk text/provenance and artifact-reader leases; synchronous Document event + durable `search_index_operations`; existing Redis dispatch/claim/renew/ack runtime extended with SEARCH.
- Spring AI 2.0.1/OpenAI adapter and native OpenSearch Java 3.9.0/HttpClient5; actual SDK HTTP fixture verifies model, dimensions, FLOAT encoding, input, decoding and 401 handling.
- Real OpenSearch 3.5.0 at digest `sha256:dbb01641baadae5104e18acd888bf05e8fdd9af3567fd30624a76ba3e5a31dec`: 3072-dimensional Faiss vector writes, min-max/arithmetic-mean hybrid, filters, surviving-vector reuse, obsolete-generation cleanup, deletion, physical-index loss and rebuild. Embeddings in this test are synthetic, not OpenAI quality evidence.
- Current-generation/source eligibility batch checks, document grouping, bounded pagination, passage preview, separate Source Search status, safe dependency errors and redacted MVC debug formatting.
- Local development Compose service parsed successfully; no server OpenSearch/snapshot repository provisioned.

| Gate | Result |
| --- | --- |
| `./gradlew.bat clean check --no-daemon --console=plain` | Passed; `.tmp/mem46-clean-check3.log`, 3m30s. Initial failures exposed missing SEARCH worker component scan/topology and old two-workload test assumptions; fixed. |
| Final bounded chunker + durable work integration, API/worker compile | Passed; `.tmp/mem46-final-chunker.log`, 34s. Bounds tokenization lookahead and expanded table cells, preserving Unicode. |
| Final search service/redacted formatting and actual Search HTTP error/session contract | Passed; `.tmp/mem46-final-contracts.log`, 50s. |
| `web/pnpm check` | Passed; `.tmp/mem46-web-check.log`; 52 unit tests, lint, format, typecheck, generated API/route stability and production asset build. |
| Chromium Search + identity shell | Passed, 17 tests; `.tmp/mem46-browser.log`. Browser HTTP fixtures cover query/filter/paging/preview, escaping, unavailable/retry/empty and out-of-order responses. |
| `git diff --check`, Compose configuration | Passed. |
| JetBrains semantic inspection | Rechecked successfully for all 69 changed/new supported files with warnings enabled; 21 files still have diagnostics. SQL datasource/schema resolution, two shared-YAML resource references and Java/configuration warnings remain open. `openapi.yml` passed on a longer retry. IDE build passed. No IDE-clean claim. |

## Acceptance still open

1. A usable OpenAI credential or the correct gateway endpoint. The supplied value is stored and matched in Infisical dev/staging but the official endpoint returned 401. No further paid/live model corpus calls were made.
2. Representative Vietnamese/identifier/table corpus relevance, latency/concurrency/cost and full deployed FILE→MinIO→worker→OpenSearch→API→browser acceptance. Provider/server fixtures establish contracts, not production quality.
3. Original-file/native-source opening and native Google acceptance when that provider exists; the current reader opens current extracted passages.
4. Deployment OpenSearch TLS/roles, configured MinIO snapshot repository, restore/catch-up/retention and RPO/RTO/capacity drill. Model migration currently rebuilds gradually; no zero-downtime cutover claim.
5. Complete subsequent PR/CI/deployment lifecycle when requested. Remaining IDE diagnostics are substantiated below: user-excluded SQL datasource resolution, upstream IDEA-308405 for cross-module YAML imports, and conventional launcher/Testcontainers lifecycle warnings. No blanket IDE-clean claim.

MEM-46 remains In Progress and assigned to `dathip04`. MEM-11 Chat remains separate. No acceptance checkbox was closed solely because fixture tests passed.

Linear implementation update was saved and re-read successfully; the CLI reported `linear_write_unconfirmed` because Linear normalized Markdown bullets. The re-read contains the implementation, diagrams, all validation evidence and open acceptance items, with status/assignee preserved.

## 2026-09-08 — Requested recheck

- Both Codex configurations still use `http://127.0.0.1:65400/stream`; native JetBrains tools are now mounted. Module discovery confirms the imported core/api/worker/connector modules. Every changed/new supported file was inspected with `errorsOnly=false`; raw results are in ignored `.tmp/mem46-ide-recheck.json`. The initial OpenAPI timeout cleared on the 60-second retry.
- IDE `build_project` completed successfully without build problems. The checked-in wrapper also passed `:core:compileJava :api:compileJava :worker:compileJava` in 14 seconds, with all tasks up-to-date (`.tmp/mem46-recheck-compile.log`). This recheck did not rerun the earlier complete behavioral suites.
- Inspection is not clean: SQL inspections explicitly report no configured datasource and unresolved schema objects; API and worker YAML still cannot resolve `memoryos-search.yaml` in IntelliJ after the build, although that core resource exists and the earlier runtime integration contexts passed. Java nullability/redundancy and test/configuration warnings also require triage. No diagnostics were hidden with suppressions.
- Infisical dev/staging secrets were read again, are present and match in memory. A fresh minimal embedding request still returns HTTP 401 from the official OpenAI endpoint; no secret or provider error body was printed.
- Live Linear read confirms MEM-46 remains In Progress and assigned to `dathip04`. Branch is still `mem-46-search`; no commit, PR, merge or deployment was performed.
- `git diff --check` passed; all 112 changed/new paths were scanned without an OpenAI project-key match or a broken local Markdown link. Corrected the README capability list and architecture worker-scan description to include the implemented Retrieval code.

## 2026-09-08 — Diagnostic fixes, JdbcClient and shared resources

- User explicitly excluded SQL datasource/schema diagnostics from this fix. Database schema and datasource settings were not altered to silence the IDE.
- Replaced fully qualified type references and wildcard imports in changed Java files with explicit imports. Fixed non-null return contracts, redundant optional/null checks, unused lambda parameters and return values, repeated row-prefix construction, fixture usage metadata and the quoted Compose port mapping.
- `JdbcDocumentChunkRepository` now only injects `JdbcClient` and `ObjectMapper`. It writes parameter-bound multi-row INSERT statements in groups of 128 under the existing publication transaction. Real PostgreSQL verification round-trips 259 chunks including Unicode and quoted text, then proves a duplicate ordinal in the second batch rolls back all replacement writes and preserves the prior committed chunks/count.
- Added Spring Boot configuration metadata generation in `core`; the built JAR contains `META-INF/spring-configuration-metadata.json` including `memoryos.search`.
- Moved `memoryos-observability.yaml` and `logback-spring.xml` into `core/src/main/resources` alongside Search YAML, removed the empty root config directories and both custom Gradle resource source sets. API and worker import the shared YAML through their normal `core` dependency. Canonical architecture/observability/Search documentation reflects the final layout.
- Re-ran per-file semantic inspections for all 73 changed/new supported files with warnings enabled (`.tmp/mem46-diagnostics-inspections.json`), plus focused rechecks after final edits. SQL datasource/schema findings remain as requested. The only retained Java warnings concern the conventional public Spring Boot `main` method and the `@Container` field whose lifecycle is owned by JUnit's Testcontainers extension.
- Reloaded the IntelliJ Gradle project through its Gradle toolbar and ran IDE build successfully. API/worker YAML still shows cross-module resource resolution errors. This matches the open upstream [IDEA-308405](https://youtrack.jetbrains.com/issue/IDEA-308405). Both imported YAML files and Logback XML are present in the core JAR, and real Spring test contexts start successfully. Kept standard runtime imports; did not copy resources into multiple source roots or suppress inspection rules.
- Full `./gradlew.bat clean check --no-daemon --console=plain` passed in 6m47s (`.tmp/mem46-diagnostics-clean-check.log`): 207 tests discovered, 204 executed successfully, three existing live-Docling tests skipped. All five Search PostgreSQL work tests and the real OpenSearch integration test executed successfully. Frontend behavior was unchanged in this fix; the previous frontend/browser evidence still applies.
- Live OpenAI and deployment acceptance remain open; this fix did not re-probe or replace the previously rejected credential, commit, push, open a PR, merge, or deploy.
- Final focused PostgreSQL work tests plus `:api:bootJar :worker:bootJar` passed in 1m1s (`.tmp/mem46-diagnostics-final.log`). Both executable JARs contain all three shared resources and generated Search property metadata in their core dependency, with no duplicate copies under `BOOT-INF/classes`. Final diff whitespace validation passed.

## 2026-09-08 — Adjacent Search sections matching Onyx

- Read local Onyx reference `06aa2b09cc4aa5135fa2627e5235814e996f1514`: `context/search/pipeline.py::merge_individual_chunks` and `context/search/utils.py::inference_section_from_chunks`. Implemented adjacency only among retrieved hits, newline concatenation in document order, best-hit section ranking/anchor and per-chunk provenance. No missing-neighbor retrieval, model call or reindex was added.
- Search now returns `sections` inside document results, after merging all valid candidates and then limiting to three sections per document. Individual `passages` remain the preview contract. UI shows section ranges and opens each section around its best match; switching sections within the same document resets the preview offset. Generated OpenAPI/Hey API types were refreshed from the real API schema.
- `:core:test --tests '*DocumentSearchServiceTest' :api:test --tests '*OpenApiContractTest'` passed in 1m8s (`.tmp/mem46-sections-focused.log`). Six service tests cover gaps, document/generation eligibility, duplicate hits, score ties, ranking versus content order, provenance and merging before the section limit. The no-neighbor-read assertion verifies section creation does not expand context.
- JetBrains inspections with warnings enabled passed for all Java files changed in this addition, `openapi.yml`, the Search TSX page and browser test. IDE `build_project` passed without problems. Previously documented SQL/import diagnostics outside these files remain unchanged.
- Repository `./gradlew.bat clean check --no-daemon --console=plain` passed in 5m25s (`.tmp/mem46-sections-clean-check.log`): 210 tests discovered, 207 executed successfully, three existing live-Docling tests skipped. This includes real PostgreSQL/OpenSearch integrations.
- `web/pnpm check` passed (`.tmp/mem46-sections-web-check.log`): 52 unit tests, lint/format/types, generated client/routes stability and production build.
- Chromium Search + identity-shell run had 16 passes including both Search tests, and one existing multi-route member-administration test hit its overall 30-second timeout during the parallel run (`.tmp/mem46-sections-browser.log`). That single failing test passed unchanged with `--grep 'hides owner UI' --workers=1` in 8.6s, 17.8s total (`.tmp/mem46-sections-browser-recheck.log`). No application change or timeout increase was made to mask it. Browser evidence uses HTTP fixtures, not live model relevance.
- Architecture, Search spec/matrix and this increment now document section behavior. Existing production/model/recovery acceptance remains open; MEM-46 stays active. No commit, push or deployment was performed for this addition.
- MEM-46 description was updated with the section flow, Onyx comparison and verification evidence, then read back and verified after Markdown normalization (`.tmp/mem46-sections-linear-verified.json`). The CLI's initial `linear_write_unconfirmed` did not require a retry. State remains In Progress and assignee remains `dathip04`. Final `git diff --check` passed.

## 2026-09-08 — Staging Search infrastructure and latest release

- Provisioned native OpenSearch TLS/Security and OpenSearch Dashboards with Keycloak OIDC; both 3.5.0 containers became healthy before the user requested the latest version. Dedicated service identities and the existing `memoryos-inspector` role separate indexing from read-only inspection. No existing user role assignments changed.
- Both official GitHub latest-release endpoints report stable 3.8.0; Docker registry manifests for OpenSearch and Dashboards were verified. Updated local/staging Compose, bootstrap provisioner and the real integration test to that exact version/digest. Prior 3.5.0 evidence above remains historical; 3.8.0 gates and rollout evidence follow after execution.
- Re-tested the exact later key variant supplied by the user, without changing the stored secret: HTTP 401 from OpenAI embeddings. The stored key also returned 401. No provider response body or secret value was recorded.
- Inspected resolved Spring AI 2.0.1 sources: DocumentTransformer supplies a transformation contract, while TokenTextSplitter may append an unbounded remainder after its iteration limit. Retained the implemented structured chunker with its 768-token bound and provenance rather than adding an unused interface wrapper.
- Removed the obsolete Dockerfile COPY of the deleted root config directory. Existing shell variables are defined within the same RUN command; IDE weak unresolved-shell-reference warnings require actual image-build verification. All new staging YAML files passed IDE inspection with warnings enabled before the version update.

## 2026-09-08 — OpenSearch 3.8.0 verification and PR preparation

- The already-started repository `clean check` for the 3.8.0 image pins completed successfully in 8m28s (`.tmp/mem46-380-clean-check.log`): 23 actionable tasks, 12 executed, 10 from cache, one up-to-date. Core result XML records 125 tests with zero failures/errors/skips; that is a module count, not a whole-repository total.
- Re-inspected the changed OpenSearch integration test and both Search Compose overlays with warnings enabled. Both YAML files are clean; the test retains only the previously documented Testcontainers-managed resource warning. IntelliJ `build_project` passed with no build problems.
- Current spec/matrix/runbook version references now agree with the pinned 3.8.0 images. Earlier 3.5.0 evidence remains historical.
- Live pre-deployment inspection still shows healthy OpenSearch/Dashboards 3.5.0 and API/worker/web at `c0397a96ee1a3d5ec759bdae0e4915e276905a87`. Downloaded 3.8.0 images are not deployment proof. Record the subsequent exact-PR-SHA rollout separately.

- The newly supplied OpenAI credential was stored and read-back matched in Infisical dev and staging. One official embedding probe succeeded with `text-embedding-3-large`, 3072 finite dimensions and 16 input tokens. This resolves the earlier credential-401 blocker; it does not establish corpus relevance or full deployed Search acceptance. No secret value was logged.

- Installed the exact Dashboards HTTPS origin through NPM's supported custom HTTP include as the existing SSH/Docker operator. Nginx syntax validation and reload passed, the existing ACME account issued the certificate (expires 2026-12-07), and an explicit twice-daily operator renewal job was installed without changing other cron entries. Certbot `renew --dry-run --cert-name memoryos-search` passed. This certificate is managed by the checked-in operator scripts, independently of NPM's Proxy Hosts UI/database; browser SSO and role acceptance remain separate checks.

- First deployed browser SSO check exposed a redirect loop. Keycloak recorded `Invalid scopes: openid profile email address phone` for the Dashboards client; those are the plugin's default scopes, whereas the managed client intentionally enables only profile/email. Set `opensearch_security.openid.scope` to `openid profile email`, matching the documented setting and installed 3.8.0 plugin. YAML inspection with warnings enabled is clean and the Gradle compile floor passed in 16s. Browser acceptance is rechecked against this corrected configuration.

## 2026-09-08 — CodeRabbit review fixes

- Captured the completed review of `aed6ac4` on PR #79 once. Corrected request null validation, provider exception classification, application-owned embedding bean registration, credential transport validation, generated response requiredness, Keycloak origin/redirect validation, Dashboards upstream TLS, inspector backend permissions and internal certificate renewal. The deployed V17 migration remains unchanged; representative large-table migration and lock evidence is tracked by MEM-73 and its review thread remains open.
- Inspected every changed Java/YAML file with warnings enabled; final inspections are clean, including the OpenAPI retry. The final checked-in-wrapper `clean check` passed in 8m39s (`.tmp/mem46-review-final-clean-check.log`): 218 tests discovered, 215 passed and three existing live-Docling tests skipped. Module counts are core 133, API 43, worker 22 and connector 20. This includes actual PostgreSQL/OpenSearch integrations and the final interrupted-response-body regression test.
- `pnpm --dir web check` passed, including 52 unit tests, static checks, generated-contract consistency and production build (`.tmp/mem46-review-web-check.log`). Four isolated Linux operator tests passed on the staging host, exercising origin rejection before credentials/network access, redirect rejection, real certificate rotation and rollback (`.tmp/mem46-review-operator-tests.log`).
- These are pre-push checks. Record latest-head CI, deployment and live certificate rotation in Linear and PR replies after execution. Authenticated human SSO, representative corpus quality/load and large-table migration acceptance remain open; passing fixtures does not close them.
