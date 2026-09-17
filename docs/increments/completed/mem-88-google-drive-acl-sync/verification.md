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

- **Same-content re-synchronization.** Main's `b9b800af` and this branch both handled a provider version that advances while bytes stay identical, with contradictory contracts. At the user's direction main's rule is kept: the current version's provider version is refreshed in place. The branch's pre-staging `unchangedBinary` path was removed, and its ACL test now expects provider version `2` and one adopted object write, because main's path stages and then discards the duplicate write. `content_provider_version` now always equals the refreshed version for new observations; the column and the `unchanged()` fallback were later removed from V61 before merge ([MEM-104](https://linear.app/memory-os/issue/MEM-104), see [below](#redundant-content-version-removal--2026-09-14)).
- **Signature drift.** Main's new test used the two-argument `ConnectorIndexingPort.fail`; it now uses this branch's four-argument form.
- **Migration numbering.** Main's `V53__jit_allowed_provider` collided with the branch's V53. The branch-only migrations became V61 (ACL snapshots) and V62 (run error messages), renumbered above main's V54–V62 when main was merged on 2026-09-15. A local database that applied the old branch V53/V54 fails Flyway validation and must be recreated; no deployed environment applied them. The 2026-09-16 main merge then collided again: main had grown to V68, so the branch migrations moved to V69 (ACL snapshots) and V70 (run error messages). A local database that applied the intermediate branch V61/V62 likewise fails Flyway validation and must be recreated; no deployed environment applied them.

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

## Live Google sharing evidence — 2026-09-14

Local runtime from this branch: API, Worker and Vite web on a fresh Arconia PostgreSQL at V55 (V62 after the 2026-09-15 renumbering, V70 after the 2026-09-16 renumbering, V76 after the 2026-09-17 post-merge renumbering), connected through the real Google OAuth flow with the user's own account and `drive.readonly`. The Source used Specific scope on a user-owned fixture folder of twelve small files (text, CSV, DOCX, XLSX, PPTX, PDF). The user made every sharing change on that folder in Google Drive. After each change, Sync now was triggered in the Orca browser, and the database and inspector API were read. E-mail addresses were compared in SQL and never printed or recorded.

At baseline the folder held an owner and two direct writers. Every file carried the same three entries with `permissionDetails[0].inherited = true` and no `inheritedFrom`, which is the My Drive behaviour the spec warns about. One writer's folder permission was then changed:

| Step | Revision (folder and 12 files) | That principal on 13 of 13 items | Inspector |
| --- | ---: | --- | --- |
| Re-observation without change | 3 | writer | CURRENT, 3 permissions |
| Writer → reader | 4 | reader | CURRENT, 3 |
| Removed | 5 | absent | CURRENT, 2 |
| Re-added as reader | 6 | reader | CURRENT, 3 |
| Reader → writer, restoring the original sharing | 7 | writer | CURRENT, 3 |

Every run SUCCEEDED. Each change replaced the folder's direct permission and every file's inherited permission with a complete new snapshot. The revocation produced a successful two-entry snapshot, not a failure and not a retained grant. The same inspector response listed five snapshots from an earlier selection of another folder as INVALID after the user replaced the Source selection; their payloads were retained.

That earlier selection was a folder of real financial-report PDFs. Its permissions were only read, its run was superseded by the fixture selection, and no permission on those files was changed.

Not shown live:

- `GoogleDriveAclChanged` delivery. No runtime listener exists yet; `PostgresGoogleDriveSyncTest` covers it.
- Group, domain and `anyone` principals, and expiration.
- A missing-scope 403, which is covered by controlled fixtures only.

The user then added a second root: a folder owned by another account and shared to the connected account as Editor, containing one Google Doc. The first run after the change SUCCEEDED for both items at revision 1. Each snapshot has two entries: the other account as owner and the connected account as writer. The folder's writer entry is direct, while both of the Doc's entries are inherited without `inheritedFrom`. An Editor can therefore read the sharing of a file it does not own.

The owner then reduced the connected account to Viewer. The next run still SUCCEEDED, and the Doc's content item stayed INDEXED. `permissions.list` was refused for both items and recorded as `SOURCE_GOOGLE_ACCESS_DENIED`:

- The retained snapshot kept revision 1 and its two entries, with its last success unchanged.
- The status became FAILED, with a newer last attempt.
- The inspector reported the retained observation as STALE.

This is the live unreadable-sharing path. A Viewer cannot read the sharing of a file it does not own, and the retained grants are older evidence only.

## ACL inspector removal — 2026-09-14

The inspector tab, panel, its 68 panel-only translations, both ACL endpoints and their contract, OpenAPI schemas and generated client were removed, together with `GoogleDriveAclService` and the repository listing ([design](design.md#acl-inspector-removal--approved-2026-09-14)). `read` and `readByDocument` now share one snapshot projection. Removed tests covered only the inspector: its three repository cases duplicated existing `read` coverage of absent, failed-only, successful-empty, stale and invalid snapshots, and the two HTTP cases tested endpoints that no longer exist.

- `openapi.yml` was regenerated with `MEMORYOS_OPENAPI_WRITE=true` and the hey-api client with `pnpm generate:api`; neither contains `GoogleDriveAcl`/`GoogleDrivePermission` any more.
- `PostgresGoogleDriveSyncTest` 46/46, `PostgresGoogleDriveAclRepositoryTest` 19/19, `SourceApiIntegrationTest` 25/25 and `OpenApiContractTest` passed; worker test sources compile.
- `pnpm check` passed: contract stability, i18n audit (0 findings), lint, format, typecheck, 236 unit tests, route/build.
- The full `clean check` gate was not rerun locally for this change; CI runs it on the pull request.

## Redundant content version removal — 2026-09-14

[MEM-104](https://linear.app/memory-os/issue/MEM-104): same-content re-synchronization already refreshes the current version's `provider_version` in place, so `google_drive_membership.content_provider_version` and the `unchanged()` fallback that read it were redundant. V75 (then V69, V61) no longer adds the column; `observe` has one four-argument form, `unchanged()` matches the current version's provider version exactly and `releaseConfirmed` compares it with `m.provider_version`. The migration is unreleased, so it was edited in place; a local database that already applied the earlier version must be recreated (Flyway checksum).

- `:core:compileTestJava`, `:api:compileTestJava` and `:worker:compileTestJava` passed.
- `PostgresGoogleDriveSyncTest` 46/46 (including unchanged, same-content and lost-access paths), `PostgresSourceRunHistoryTest` 13/13 and `PostgresGoogleDriveAclRepositoryTest` 19/19 passed.

## Main integration — 2026-09-16

Merged `origin/main` at `83c61b8d` with no conflicts. Main had grown to V68, so the branch migrations moved to V69 (ACL snapshots) and V70 (run error messages); a local database that applied the intermediate branch V61/V62 fails Flyway validation and must be recreated. No deployed environment applied them.

Focused gate on the merge commit: `PostgresGoogleDriveSyncTest` 46/46, `PostgresSourceRunHistoryTest` 13/13, `PostgresGoogleDriveAclRepositoryTest` 19/19 and `RestGoogleDriveProviderTest` 22/22 passed without skips; `:api:compileTestJava` and `:worker:compileTestJava` passed. The full `clean check` and `pnpm check` gates were last run on the 2026-09-15 merge commit `634d3388`; CI runs them on the pull request.

## Post-merge renumbering — 2026-09-17

After PR #151 merged, main carried duplicate versions: MEM-88's V69/V70 collided with MEM-112's `V69__mcp_client`/`V70__mcp_oauth_client_protocol`, failing every Flyway validation on main CI (run 35202612849). The MEM-88/105 migrations moved to V75 (ACL snapshots), V76 (run error messages), V77 (source access modes) and V78 (default sync interval) on the MEM-105/106 landing PR #218. A local database that applied V69-V72 from these branches fails Flyway validation and must be recreated; no deployed environment applied them.
