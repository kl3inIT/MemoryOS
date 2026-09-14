# MEM-88 verification

## Environment and isolation

Branch `nhuxuanviet/mem-88-google-drive-acl-sync`, isolated Orca worktree based on main `3e4e31859c7cbf95e499b82b2c47bc8197827595`. JDK 25 at `C:/Users/adim/.jdks/ms-25.0.3`; checked-in Gradle wrapper. The initial collection phase made no staging deployment, merge, permission mutation, data update or restart of the existing Tasco local runtime. The approved interface phase and its local runtime changes are recorded separately below.

## Real Google read-only evidence

Used the existing selected Source's encrypted OAuth credential on staging, decrypting and refreshing only in process memory. Existing `drive.readonly` was sufficient for `permissions.list` on two already-selected financial-report files. Each returned one user/owner permission with emailAddress, deleted, pendingOwner and permissionDetails fields. Output excluded tokens, secrets, emails and permission IDs. No extra OAuth consent or scopes were requested. The SSH session was closed.

This establishes readable ACLs for those two files only. It does not establish real Google pagination, permission mutation/revocation, Shared Drive ACL behavior, group expansion or separate Tasco reader identity. Multi-page and failure semantics below use controlled fixtures.

## Focused checks

Passed with core/connector compilation:

```text
gradlew.bat :core:test --tests "*PostgresGoogleDriveAclRepositoryTest" --tests "*PostgresGoogleDriveSyncTest" --tests "*PostgresSourceRunHistoryTest" :connector:test --tests "*RestGoogleDriveProviderTest" --no-daemon
```

The successful invocation additionally emitted temporary runtime classpaths using project-owned Gradle tasks and disabled configuration caching for that throwaway init script. Build succeeded in 2m19s. The initial run exposed and led to fixes for Mockito restubbing and the valid generation-zero legacy run: V43 now admits nonnegative traversal generations, matching the existing sync schema. No existing behavioral assertion was weakened.

## Actual synchronization runtime

A throwaway Java main used the real RestGoogleDriveProvider, local HTTP OAuth/Drive fixture, encrypted credential repository and DefaultGoogleDriveConnectionService, Flyway V1–V43, disposable PostgreSQL, real repository/transaction fencing, source operation dispatch and DefaultConnectorSyncService. Only object storage used an explicit memory fixture. The program exercised the runtime rather than invoking a JUnit test method.

Observed output:

```text
ACL_SMOKE phase=0 revision=1 status=SUCCEEDED permissions=2 downloads=1
ACL_SMOKE phase=1 revision=2 status=SUCCEEDED permissions=1 downloads=2
ACL_SMOKE phase=2 revision=2 status=FAILED permissions=1 downloads=2
ACL_SMOKE PASS real HTTP OAuth/provider + encrypted credential + PostgreSQL migrations + fenced SOURCE_SYNC; memory-only object-storage fixture; pages=8
```

Phase 1 removed a permission and increased provider metadata version without changing bytes. Phase 2 failed a later permission page. Assertions retained exactly one immutable content version and one indexing attempt across all phases, preserved the last complete ACL after the failure, and avoided another download once the new observed version was known. The HTTP server stopped and database pool closed; Testcontainers owns disposal of its PostgreSQL fixture.

This proves no new extraction work was enqueued for the identical-byte binary change, not live OCR execution, financial accuracy or semantic deduplication of native Docs/Sheets envelopes.

## Static analysis boundary

JetBrains get_file_problems is not mounted; this worktree has no `.omp/mcp.json` endpoint. LSP reports no configured language servers. IDE semantic inspection is unavailable, and no IDE-clean claim is made. Gradle compilation and behavioral checks provide the fallback evidence. Existing unchecked-operation compiler notes in BoundedDoclingClient are outside the changed files.

## Authorization boundary

No reader identity linking, effective permissions, Google Group expansion, Search/Chat/citation policy or Owner/PUBLIC bypass was implemented. The approved interface extension adds Source-authorized metadata GETs only. CURRENT snapshot context is not an access grant or wall-clock freshness claim. The connector spec owns the handoff fields and interpretation.

## Cleanup

Removed the owned throwaway Java program, Gradle init script and Java argument file after the successful smoke. Its classpath outputs were under build directories removed by the normal clean gate. No fixture data was inserted into retained application databases; no real Google sharing mutation needed rollback.

## Initial collection repository gate

`gradlew.bat clean check --no-daemon` succeeded in 9m57s. All four server modules compiled and all Test tasks executed; unchanged compilation outputs were partly restored from Gradle's build cache.

| Module | Total | Failed | Skipped |
| --- | ---: | ---: | ---: |
| core | 436 | 0 | 0 |
| connector | 111 | 0 | 4 |
| api | 134 | 0 | 4 |
| worker | 27 | 0 | 0 |
| Total | 708 | 0 | 8 |

700 passed. Skipped scenarios: three real Docling service checks, one ChatFileResourceMeasurementTest, and four real-provider/corpus/vision ChatSessionApiIntegrationTest scenarios. No MEM-88 focused scenario was skipped. The log contained JNA/protobuf JDK warnings and a shutdown-time OTLP connection warning; they did not fail the gate and were not suppressed. This is a backend gate; no UI changes or frontend verification are claimed.

## Approved interface verification

### Automated gates

The focused repository/HTTP inspector suites and OpenAPI contract tests passed. Final `gradlew.bat clean check --no-daemon` succeeded in 3m50s; API tests executed again after correcting a fixture-order-dependent group-search assertion, and unchanged module test results were restored from the preceding full gate's build cache.

| Module | Total | Failed | Skipped |
| --- | ---: | ---: | ---: |
| core | 439 | 0 | 0 |
| connector | 111 | 0 | 4 |
| api | 136 | 0 | 4 |
| worker | 27 | 0 | 0 |
| Total | 713 | 0 | 8 |

705 passed; the same eight external-service/corpus scenarios listed above remain explicitly skipped. No inspector scenario was skipped. JetBrains/LSP inspection remains unavailable as described above.

`pnpm check` passed generated-client stability, CI configuration, i18n, lint, formatting, TypeScript, all 176 tests in 31 unit-test files and route/build checks. Removing the final unused disclaimer translation afterward also passed i18n, lint, formatting and TypeScript checks.

The existing `source-action-feedback.spec.ts`, `google-drive-source-setup.spec.ts` and `file-source-setup.spec.ts` passed all 21 Chromium scenarios with two workers. An initial attempt overlapped SDK regeneration: Vite logged missing generated imports/page reloads and the first two FILE navigations timed out. Running the unchanged browser suites after generation completed passed; no timeout was widened and no scenario was removed.

### Actual browser surface

A throwaway Chromium script loaded the real Vite application with browser-intercepted API responses only. It exercised all four Source tabs, the removed disclaimer, retained stale permissions after a failed attempt, missing observation, successful-empty and failed-only states, 27 permissions across two detail pages, six file errors across two cursor pages, Escape/focus restoration and narrow-screen bounds. Desktop 1440px and mobile 390px screenshots were inspected. Mobile ACL entries render as cards with their status and action visible, rather than requiring horizontal table scrolling.

Fixture responses did not modify the application database or Google sharing. This browser evidence proves rendering and interaction for controlled states, not a fresh live Google synchronization or effective reader authorization. The throwaway browser script and runner configuration were removed after verification.

### Local runtime for inspection

The current branch runs at `http://127.0.0.1:8080/admin`: Vite proxies the current API on port 18082; the ACL-capable worker runs on port 8081. API and worker `/actuator/health/readiness` both returned HTTP 200 with `{"status":"UP"}`. The browser's normal sign-in reaches Keycloak with the registered `http://127.0.0.1:8080/login/oauth2/code/memoryos` callback; no credentials were entered by the assistant.

The previous Tasco local web process and worker were replaced, while its API and retained development PostgreSQL were left running. A loopback-only Redis container on port 56379 supplies the new worker; its first jar launch exposed the missing prior worker-owned Redis dependency, which was restored explicitly before readiness passed. Services use the existing development secret file without copying credentials into tracked files. Local jars and screenshots remain ignored runtime/evidence artifacts. No staging runtime, Google sharing, retained Source data or deployment was changed. Local inspection uses development data and does not import staging Sources; real snapshots appear after an authorized Source synchronization.

## Real Orca feedback verification — 2026-09-13

### Gates

The follow-up `clean check :api:bootJar :worker:bootJar` exposed one new cross-Tenant test fixture that violated the deployment-wide single-Tenant uniqueness constraint before reaching its assertions. The isolated test now follows the existing cross-Tenant fixture convention, dropping that constraint only in its fresh disposable database. The complete `PostgresSourceRunHistoryTest` then passed. `gradlew.bat check :api:bootJar :worker:bootJar --no-daemon --max-workers=1 --console=plain` passed in 12m46s, completing the full gate after the clean/build phases. OpenAPI generation and `pnpm generate:api` were followed by a successful complete `pnpm check`. No test timeout was increased or failing contract assertion removed.

The isolated API and Worker jars were refreshed after compilation. The executable Worker's bounded Tika child JVM uses the extracted `BOOT-INF/lib/*` dependency classpath through the existing extraction-classpath configuration; it does not try to execute native extraction from an invalid boot-jar classpath.

### Real files and parser recovery

Only the user-selected Google Drive folder `4. YOUNGXV AUTO` was synchronized. Its Source is `8d1ac76a-d230-4b66-a586-20dda331d0b8`; the actual owner session was used through the Orca embedded browser. No routed/fake API responses, broader Drive scope or sharing mutations were introduced.

The previously configured private Docling endpoint failed to connect. The retained local `memoryos-tasco-ocr-docling` container was restored on loopback port 15062. Its EasyOCR attempt failed because its model directory was read-only; the actual installed Tesseract languages were `vie`, `eng` and `osd`. The Worker now uses the existing supported Tesseract configuration with `vie,eng`. Real PDF, scanned PDF, DOCX and PPTX reindexes succeeded, and the real Source items API returned all 12 files `INDEXED`, no current errors and no next page. This is extraction/publication evidence, not verification of every financial value.

A second bounded live smoke deliberately stopped only that isolated Docling container and requested one scanned-PDF reindex through the actual UI. Operation `64209762-af82-44bb-92ad-5771a80a07a1` ended with `SOURCE_EXTRACTION_CONNECTION_FAILED`, not a document timeout. The real Source alert instructed the operator to check the service address, network and availability. Its preceding sibling was the complete synchronization overview (bottom 294.11px), the alert began at 314.10px and its next sibling was the tab list. No database error state was injected.

Docling was immediately restarted. Recovery operation `eaf041ee-0e32-4d41-852b-99071ee7479e` succeeded for `YXA-12_Scan-OCR.pdf`. Final API readback again reported 12/12 `INDEXED`, no failures and `nextCursor=null`. The isolated API, Worker and Docling remain running with the recovered configuration; no staging service was touched.

### Real history and ACL interaction

Run `bc3b1bca-6929-4e25-a3a6-6bb9ae7959d4` retains its original 12 checked, 4 indexed and 8 indexing failures. Its actual dialog shows the old generic timeout separately from a now-INDEXED DOCX and its successful current-version timestamp. The second real error page exposes the retained `SOURCE_EXTRACTION_INTERNAL` code and correlation IDs; Next is disabled on that final page and Previous is enabled. No traceback or underlying historical cause is invented.

The real permission inspector listed 13 tracked files/folders. Opening a retained snapshot showed successful observation, a user/owner permission and its principal identity; identities were not printed in verification output. This is not evidence for live multi-page permissions, Google Group expansion or changed-sharing authorization.

The actual Orca file-table screenshots were visually inspected: icon badges are present and the separate Search-readiness line is absent; the earlier top-of-page screenshot also verifies the simplified Source heading. Screenshot capture for the newly created verification tab failed with `runtime_unavailable` even though tab switching and DOM/API interaction succeeded. No new-dialog screenshot is claimed; its live content, interactions and alert geometry were verified through Orca. Keyboard Escape was interrupted before execution, so this follow-up does not claim a new keyboard-dismissal result.

Onyx error/trace and Pause/Resume source findings are recorded in [design](design.md#additional-onyx-source-evidence-error-details-and-pause). No Pause implementation or historical full-trace persistence is claimed by these follow-up changes.

The owned screenshot helper was removed and the dedicated Orca verification tab was closed, leaving the user's original tab intact. Ignored screenshots and the jars/dependency directory required by the running local services were retained.

## Acceptance gap closure — 2026-09-14

Focused suites passed without skips: `RestGoogleDriveProviderTest` (22) and `PostgresGoogleDriveSyncTest` (43), including the new 403 classification, missing-scope, unreadable-sharing and role-change cases listed in the [connector matrix](../../../tests/connector.md#mem-88-acl-collection-and-synchronization). `gradlew.bat clean check --no-daemon --max-workers=1` succeeded in 18m17s:

| Module | Total | Failed | Skipped |
| --- | ---: | ---: | ---: |
| core | 534 | 0 | 1 |
| connector | 121 | 0 | 4 |
| api | 147 | 0 | 4 |
| worker | 27 | 0 | 0 |
| Total | 829 | 0 | 9 |

Web: `check:i18n`, oxlint and oxfmt passed for the changed `source-errors.ts` and `app-translations.ts`; the full `pnpm check` was not run in this pass. The 403 reasons are verified against controlled HTTP fixtures shaped after Google's documented error bodies, not against a live missing-scope grant. Reconciliation of the handoff example with the enforcement owner remains pending.

## Main integration — 2026-09-14

Merged `origin/main` at `1b23118e`. Git conflicts in `JdbcSourceItemRepository`, the connector spec and `source-detail-page.tsx` imports were resolved by hand; the hey-api client was regenerated from the merged `openapi.yml`.

- **Same-content re-synchronization.** Main's `b9b800af` and this branch both handled a provider version that advances while bytes stay identical, with contradictory contracts. At the user's direction main's rule is kept: the current version's provider version is refreshed in place. The branch's pre-staging `unchangedBinary` path was removed, and its ACL test now expects provider version `2` and one adopted object write, because main's path stages and then discards the duplicate write. `content_provider_version` now always equals the refreshed version for new observations; the column and the `unchanged()` fallback remain and can be removed in a follow-up.
- **Signature drift.** Main's new test used the two-argument `ConnectorIndexingPort.fail`; it now uses this branch's four-argument form.
- **Migration numbering.** Main's `V53__jit_allowed_provider` collided with the branch's V53. The branch-only migrations are now V54 (ACL snapshots) and V55 (run error messages). A local database that applied the old branch V53/V54 fails Flyway validation and must be recreated; no deployed environment applied them.

`PostgresGoogleDriveSyncTest` (45) and `RestGoogleDriveProviderTest` (22) passed without skips. On the merge commit `634d3388`, `gradlew.bat clean check --no-daemon --max-workers=1` succeeded in 17m59s:

| Module | Total | Failed | Skipped |
| --- | ---: | ---: | ---: |
| core | 551 | 0 | 1 |
| connector | 121 | 0 | 4 |
| api | 147 | 0 | 4 |
| worker | 27 | 0 | 0 |
| Total | 846 | 0 | 9 |

The skips are unchanged from the previous gate. The full web `pnpm check` then passed: contract generation, i18n, lint, format, typecheck, 223 unit tests in 41 files, and route/build. Playwright browser suites were not rerun after the merge.

After the gate, the consumer read API gained its first test: `readByDocument` returns the mapped file's snapshot and nothing for a foreign Tenant or unknown Document. `PostgresGoogleDriveSyncTest` then passed 46 of 46 without skips.
