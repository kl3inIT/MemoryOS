# MEM-46 — Implementation evidence

## 2026-09-09 — Search UI visual hierarchy polish

- Reframed the initial page as direct document Search: `Knowledge search` and `Find information in your workspace` replace Chat-like copy. The empty state retains one query action and keeps attachment/add-context controls absent.
- Grouped time and file-type controls under an explicit `Filters` toolbar, refined the desktop file-type facet into a contained summary, and reordered result-card emphasis to title, friendly type/date metadata, then bounded context. Empty and unavailable states now share an accessible, contained treatment; controls retain visible keyboard focus and existing reduced-motion paths.
- Corrected desktop file-type facets so all supported types remain selectable after applying one filter; counts are sourced from the cached unfiltered query/time scope. Search keeps prior results with an updating indicator for a new filter/query response, uses a 30-second stale window, and disables window-focus refetch to avoid tab-switch reload churn.
- Verified without Playwright: `pnpm --dir web typecheck`, targeted `oxlint --deny-warnings` and `vitest run src/features/search/search-page.test.tsx` passed (`11/11`).

## 2026-09-09 — Semantic relevance floor

- Reproduced the reported false positives on an isolated three-document local corpus. `Aurora Lighthouse` ranked the intended document at `1.000000` but top-K semantic retrieval also returned unrelated documents at `0.011580` and `0.000500`; a nonsense query still produced hybrid scores near `0.50` because min-max normalization is relative to the returned candidate set.
- Compared the checked-out Onyx implementation with current official OpenSearch, Typesense, Meilisearch and Elastic guidance. Onyx separates a broad internal hybrid candidate pool from a smaller result window but does not provide an absolute hybrid score floor in the inspected path. MemoryOS therefore keeps the existing normalized hybrid fusion while switching the semantic clause to OpenSearch radial k-NN `min_score` before normalization.
- Added `memoryos.search.minimum-semantic-score` / `MEMORYOS_SEARCH_MINIMUM_SEMANTIC_SCORE`, default `0.70`. With the current Faiss `cosinesimil` mapping this equals cosine similarity `0.40`. The value was calibrated against the local MEM-46 acceptance corpus. `candidate-limit` remains `ef_search`, hybrid pagination-depth and response-size budget. BM25 remains independent so exact identifiers and phrases do not depend on vector admission.
- Focused `SearchPropertiesTest` and real `OpenSearchRetrievalIntegrationTest` passed in 2m23s on Temurin 25.0.2 with the `0.70` default. The integration fixture proves a related vector remains searchable, an orthogonal vector is excluded and Tenant/MIME boundaries remain intact. It does not establish the final threshold for a production corpus; representative judgments and staging acceptance remain open.
- Initial authenticated HTTP acceptance used the isolated local PostgreSQL/OpenSearch data through a temporary API on port `18082`. At the earlier `0.65` candidate, `Aurora Lighthouse` returned only `MEM-46 Search Handbook`; `unique verification phrase for search indexing` retained the three lexical/semantic matches in ranked order; `banana spaceship zxqv` returned zero results. A subsequent query exposed one low-ranked neighbor for `Nebula Recovery`, so that candidate was rejected and the default raised to `0.70`. The temporary API was stopped and the user's IntelliJ API on `18080` remained running. Authenticated HTTP acceptance of `0.70`, a representative relevance judgment set and staging acceptance remain open.

## 2026-09-09 — Remove Chat-oriented add action from Search

- Removed the `+` trigger and the complete `SearchAddMenu` component after product clarification that attachment/add-context belongs to Chat, not direct document Search. Search no longer exposes FILE upload or Source browsing inside its query composer; those existing administration routes remain available through the Admin surface.
- Preserved the controlled Search input, clear action, browser voice input and Search submit without changing API requests, filters or result behavior. The landing retains one primary task and avoids advertising a capability outside MEM-46.
- The Search page regression test now explicitly asserts that `Add to search` is absent for the owner session. Focused verification passed 11/11 Search page tests. Full `pnpm --dir web check` passed generated API/route stability, image policy, lint, format, TypeScript, 61/61 unit/component tests and the production build. Per user direction, Playwright was not run locally; pull-request CI remains the browser evidence.

## 2026-09-09 — Search-only add menu correction

- Replaced the direct `Add file source` icon link with a ChatGPT-familiar `Add to search` menu while keeping the capability strictly inside MEM-46 Search. The menu offers the real FILE Source upload flow to global `SOURCES_MANAGE` and connected-source browsing to global/scoped `SOURCES_READ`; sessions without either authority receive no unusable control. It does not add Chat actions, generate answers or attach an unindexed local file to the Search API.
- The Radix trigger exposes menu/expanded state and inherits keyboard navigation, Escape dismissal and trigger focus return. Both labeled items use Lucide icons as decorative context, bounded responsive width, visible highlight/focus states and reduced-motion-safe entrance.
- Focused component verification passed 13/13 Search page tests, including two-item owner scope, Escape focus return, no source action for unauthorized members, and browse-only behavior for scoped readers. Full `pnpm --dir web check` passed generated API/route stability, image policy, lint, format, TypeScript, 63/63 unit/component tests and the production build. Per user direction, Playwright was not run locally.
- [PR #83 CI at code head `583b897`](https://github.com/kl3inIT/MemoryOS/actions/runs/34326419353) passed 63/63 unit/component tests, 23/23 browser states, repository check, frontend/backend images, secret scan and CI Gate. Publish was correctly skipped for the pull request; the branch remains unmerged.

## 2026-09-09 — Search composer Source and voice actions

- Added three explicit composer actions around the controlled Search input: a capability-gated `Add file source` link, browser voice input and the existing Search submit. The Source action is present only for global `SOURCES_MANAGE` and deep-links to `/admin/sources/new/file`; scoped/read-only members do not receive it.
- Voice input uses the browser-provided `SpeechRecognition`/`webkitSpeechRecognition` implementation. It appends interim/final transcript text to the current query for review and never auto-submits or sends audio through the MemoryOS API. Listening, unsupported-browser, permission-denied and generic failure states have pressed/disabled semantics, concise live feedback and unmount cleanup; nonessential pulse animation honors reduced motion.
- Focused `search-page.test.tsx` verification passed 11/11 tests, including unsupported-browser state, Source shortcut authorization, transcript composition without an API call, stopping/focus return and microphone-permission recovery. Full `pnpm --dir web check` passed generated API/route stability, image policy, lint, format, TypeScript, 61/61 unit/component tests and the production build.
- Manual authenticated Chrome inspection (not Playwright) at a 1005×1032 window confirmed the three controls remain aligned in the centered landing, the Source shortcut resolves to the real FILE setup route and accessibility exposes named link/button/pressed semantics. Per user direction, Playwright was not rerun locally.
- Initial PR head `aaf8b91` exposed one browser-contract regression: the new idle voice live region also used `role=status`, making the established Search status locator ambiguous. The follow-up keeps voice announcements through `aria-live` while preserving exactly one page status landmark, with a component assertion preventing recurrence. No Search request or voice behavior changed.
- PR #83 CI at code head `24f605e` passed: 23/23 browser states, repository check, frontend/backend images, secret scan and CI Gate. Publish was correctly skipped for the pull request; the PR remains open and unmerged.

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

## 2026-09-08 — Authenticated sample indexing repair

- Uploaded four clearly synthetic Vietnamese TXT/Markdown files through the normal authenticated API and checksum-bound MinIO PUT/finalize flow. Extraction completed, exposing `SEARCH_INDEX_FAILED` on secured OpenSearch. The exact service request to global alias lookup returned 403, while selecting the read alias as the index target returned 200. Cluster alias permission alone did not repair the scope and was removed.
- Changed alias inspection to select the actual read-alias targets; retained the one-physical-index check. Extended the real OpenSearch integration to attach a second target, assert rejection, remove the extra alias target and verify recovery. The service role adds only bulk coordination while keeping target-index and human-role permissions unchanged.
- Final `clean check` passed in 5m28s (`.tmp/search-acceptance-20260908/final-clean-check.log`). Changed production Java and YAML inspections are clean with warnings enabled; the integration fixture retains the documented JUnit-managed Testcontainers lifecycle warning. Full sample Search/reader and deployment evidence is recorded in MEM-46 after rollout, not inferred from extraction success.

## 2026-09-09 — Assigned Search UI/UX and Dashboards completion

- Continued from `main` at `c4f32a1` on `phamnhatanh811/mem-46-trien-khai-search-tu-json-embedding-va-opensearch-den-giao`; MEM-46 was re-read as In Review and assigned to `phamnhatanh811`. A kickoff comment recorded the agreed UI/UX, Dashboards and verification scope without moving i18n or Chat into this increment.
- Search cards now show bounded, query-anchored snippets instead of full sections. Literal full-query/token matches are highlighted with React text nodes; semantic-only results receive no fabricated highlight. Exact generated `Title: <filename>` prefixes are removed, MIME values are mapped to friendly file types and only API-provided metadata is shown.
- The passage reader is a responsive Radix Dialog. Desktop and 390×844 mobile browser checks cover opening immediately from the selected result, related-match navigation, long plain-text scrolling, safe rendering of markup-like content, Escape close, focus trap/return, and viewport containment. The UI deliberately does not infer headings, lists or tables from whitespace because the API exposes plain text only.
- Loading, cancel, unavailable/retry, empty, filtering/reset, paging and out-of-order response behavior are covered in the focused Search browser suite. The live region contains concise status text instead of the result list.
- Added an idempotent operator provisioner for the global-tenant `memoryos-chunks*` index pattern and `memoryos-chunks-inspection` saved search through the OpenSearch Dashboards Saved Objects API. It reads the protected service credential from a mode-0600 mounted curl config, never writes `.kibana*` directly and verifies controlled state after create/update. Unit tests cover create, unchanged replay, drift correction and fail-closed verification while retaining the inspector role's read-only boundary.
- Node 26 exposes an unusable global Web Storage implementation to jsdom. Vitest conditionally starts workers with Node's `--no-webstorage` option on Node 25+; Node 24 remains unchanged. This restores the existing component suite without altering application storage behavior.

| Gate | Result |
| --- | --- |
| `pnpm --dir web check` | Passed: format, lint, typecheck, generated API/route stability, 58 tests and production build. |
| Focused Chromium `web/tests/e2e/search.spec.ts` | Passed: 3/3 on desktop/error/mobile after the final focus-navigation change. |
| OpenSearch operator Python suite | Passed: 7 tests; two existing POSIX certificate tests skipped on Windows. All five new Dashboards-provisioning tests passed. |
| Python compilation and YAML parse | Passed for the changed provisioners and Search deployment/Dashboards YAML. Local Docker lacks the Compose plugin, so no Compose-resolution claim is made. |
| `./gradlew.bat clean check --no-daemon --console=plain` with Temurin 25.0.2 | Passed in 10m03s: 23 tasks, including real PostgreSQL/OpenSearch integrations. |
| PR #83 CI at `19b75c1` | Passed: backend check, frontend/browser, frontend image, backend images, secret scan and CI Gate. Publish was correctly skipped for a pull request. |

CodeRabbit reviewed 14 code/config files at `337a921` and requested three accessibility/test-stability changes: focus Search before removing Cancel, preserve a forced-colors outline fallback on result actions and poll for settled dialog focus in Playwright. Those changes and a direct Cancel-focus assertion are implemented in `19b75c1`; the bot confirmed each response and resolved all three threads. The follow-up `pnpm --dir web check`, focused Chromium Search 3/3, `git diff --check` and PR CI all passed. The formal changes-requested review remains in history because the OSS integration requires a new manual review after the follow-up commit; it was not dismissed or overridden.

Live staging provisioning, authenticated Discover inspection and exact-SHA deployment remain open. The deployment workflow accepts verified `main` releases only; the pull request remains open and unmerged by explicit user direction. Local Saved Objects fixtures do not prove that the four deployed sample chunks are visible or that the live inspector session is unable to mutate saved objects/documents.

## 2026-09-09 — Dedicated Search workspace redesign

- Reworked the Search route from a generic form/card page into a dedicated Onyx-referenced workspace while retaining MemoryOS semantic tokens and application shell. Before the first request it centers the brand mark, `How can I help?` prompt and single primary query field. After submit it reveals the filter toolbar, divider-based result feed, pagination and desktop file-type facets; the mobile layout keeps secondary facets out of the core result flow.
- Existing real `searchDocuments` query/cancellation behavior, bounded safe snippets, literal React-node highlights and responsive document dialog remain the data and interaction contracts. File-type facet selection sends the exact MIME filter and resets pagination without introducing Chat or generative behavior.
- Focused Vitest/jsdom verification passed 15/15 tests across `search-page.test.tsx` and `search-presentation.test.ts`, including the initial workspace, real SDK call shape, facet pressed state and existing Search presentation boundaries. Full `pnpm --dir web check` then passed API/client stability, static Playwright-image policy, lint, format, TypeScript, 59/59 unit/component tests, route generation and the production build. Per user direction, Playwright was not rerun locally for this redesign; browser CI remains a separate post-push signal.
- Replaced the browser-native file/date controls after visual review with token-driven Radix radio menus: `All time` plus bounded 7/30/365-day presets and `All file types` plus supported MIME choices. Selecting either menu immediately updates the existing Search request and resets paging; the error state is width-bounded. The query form now animates from its measured centered position to the result header with a 320 ms FLIP transform, while filter/result entrances honor `prefers-reduced-motion`.
- A manual authenticated Chrome smoke through desktop accessibility actions (not Playwright) observed the centered landing, submitted `alo`, received HTTP 200 after starting the checked-in local OpenSearch service, and exposed the new `Updated: All time` and `File type: All file types` controls in the result state. The query had no local matches, so this proves local runtime recovery and UI state switching, not relevance quality. Full `pnpm --dir web check` passed again with 59/59 tests and production build after the filter/motion update.

## 2026-09-09 — Search landing and repeated-query feedback

- Simplified the initial Search workspace to one visible `Search your workspace` heading above the centered composer; the prior document icon, eyebrow and helper sentence were removed to keep the product-specific Search route quiet and distinct from Chat.
- A submitted query now changes the fixed-size submit control to a spinner immediately, including during the render hand-off to TanStack Query. Existing results remain in place and retain their `Updating` marker while the new response is pending; the existing Cancel action remains available. This prevents a second query from appearing to do nothing or causing the composer layout to jump.
- `pnpm --dir web check` passed: generated API/route stability, lint, formatting, TypeScript, production build and 62/62 unit/component tests. The dedicated Search test holds a second response pending and proves that the prior result, spinner and `Updating` signal coexist until it resolves. Per user direction, no Playwright run was made.

## 2026-09-09 — Artifact and structured-chunk golden cases

- Added direct `DocumentChunkServiceTest` coverage for rejecting an artifact above the 32 MiB contract before object access and for rejecting a same-length checksum mismatch. Both paths prove the artifact reader lease is released; the checksum path also proves the opened object closes and no chunk publication occurs.
- Expanded `StructuredDocumentChunkerTest` with merged multi-column headers, a row header repeated through its declared row span, numeric Vietnamese table values, exact table-row provenance, a wide 80-column row split into bounded passages without dropping cells, and rejection of an oversized column span.
- Ran `:core:test --tests io.memoryos.document.StructuredDocumentChunkerTest --tests io.memoryos.document.DocumentChunkServiceTest --rerun-tasks` with Temurin 25.0.2: passed in 22 seconds with all four involved Gradle tasks executed. This closes the local artifact/chunker golden-case checklist only; it does not close representative retrieval quality, live model, deployment, snapshot or staging acceptance.
- Ran `:core:check --no-daemon --console=plain` with Temurin 25.0.2: passed in 6m43s; 137 Core tests across 42 suites, zero failures/errors/skips. `git diff --check` also passed before commit.
- PR #83 CI at `d7e16e2` passed: backend check, frontend/browser, frontend image, backend images, secret scan and CI Gate. Publish was correctly skipped for the pull request. The review still requires a human follow-up and no merge or deployment was performed.
