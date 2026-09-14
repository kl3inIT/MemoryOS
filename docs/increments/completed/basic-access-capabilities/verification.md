# Basic Access verification

## Implemented scope

Branch: `feat/basic-access-capabilities`. Basic persists `BASIC_ACCESS`; its implication provides `SEARCH_READ`. Admin includes this bundle through `IAM_ADMIN`. Ordinary grants cannot contain protected/derived tokens. V18 preserves memberships and ordinary grants while seeding Basic and advancing affected Tenant revisions. Search and passage reads enforce current global Search authority before provider IO and retain document eligibility. UI distinguishes explicit grants from implications and prevents denied Search/reader queries.

## Executed backend checks

JDK: Temurin 25.0.3; checked-in Gradle wrapper; Docker-backed integration fixtures.

- `gradlew.bat :core:test :api:test --tests "*OpenApiContractTest*" --tests "*SessionSecurityIntegrationTest*" --no-daemon --max-workers=2` with `MEMORYOS_OPENAPI_WRITE=true`: passed, generated the enum contract.
- Initial full gate found the Source API test's manually seeded member lacked Basic membership; updated that fixture to match real invitation/JIT admission without bypassing Search authorization.
- `gradlew.bat :api:test --tests "*SourceApiIntegrationTest" --no-daemon --max-workers=2`: passed.
- Final `gradlew.bat clean check --no-daemon --max-workers=2`: passed in 1m10s, including Gradle cache reuse. Reports: core 146, API 48, connector 20, worker 22; 236 total, 233 passed, no failures/errors, three opt-in live Docling tests skipped.

Coverage includes persisted and derived capability boundaries, Basic-only authority and lack of Source administration, Group projections, migration data preservation/revision, invitation/JIT identity projection, and missing-Search denial before provider/passage IO while preserving document eligibility.

## Frontend checks and visual confirmation

Used isolated Node 24.20.0 and pinned pnpm 11.22.0 because the machine's global pnpm launcher was broken. Node binary SHA-256 was verified against the official distribution checksum: `5c976096e04e5c2c1f091938926234cc9fbebfe9787ddd149351b3b0ecc707b5`. No global toolchain configuration was changed.

- `pnpm generate:api`: passed; generated client committed-source candidates match updated OpenAPI.
- `pnpm check`: passed, including generated-client drift, lint, formatting, TypeScript, 55 unit tests, production build and route generation stability.
- The default `pnpm test:e2e --workers=2` could not launch because bundled Chromium headless shell build 1234 was not installed. A temporary Playwright config preserved repository test settings and changed only browser channel to installed Chrome, paths and reporter.
- First Chrome run exposed an unscoped sidebar locator in the persistent-layout test. Scoped its interactions to the Administration sidebar, preserving the state-persistence assertions without retries or sleeps. Focused scenario passed, then the full suite passed: 22 tests, two workers, no retries. Final fixture formatting check passed.
- Chromium visual inspection of the production build, with sanitized API fixtures: Basic Group showed `Basic access` checked and disabled with implied `search read`; IAM administration remained unchecked. A no-capability active member showed Search access denied and issued zero `/api/search` requests.

The UI fixtures establish browser rendering and request gating, not a live Keycloak or production database rollout. Backend integration tests separately exercise the actual Spring HTTP and PostgreSQL boundaries.

## Cleanup and rollout boundary

Temporary preview/browser sessions were closed and isolated toolchain/configuration artifacts removed. Existing `.run` changes, DOCX and unrelated `output/`/`tmp/` files were preserved. No commit, push, PR, Keycloak change or server deployment performed.

V18 must be applied by normal API startup on the next approved rollout. Do not manually add derived child grant rows. Existing users need fresh current-identity data, not new invitations. Java LSP references/diagnostics were unavailable in this session; no IDE-clean claim is made. The initial implementation omitted future permission tokens; the user's later explicit vocabulary decision is recorded below and supersedes that earlier boundary without adding feature implementations.

## Follow-up: full Admin and read-only system grant trees

This follow-up supersedes the system-group checkbox presentation verified above. `IAM_ADMIN` now returns an immutable enum-wide capability set and exposes all other enum values as implication metadata, without a manual Admin list or self-edge. The registry covers every enum, including noneditable `SEARCH_READ`, with backend-authored labels and descriptions.

- `gradlew.bat clean check --no-daemon --max-workers=2`: passed in 4m16s. Reports: core 150, API 48, connector 20, worker 22; 240 total, 237 passed, zero failures/errors, three opt-in live Docling tests skipped.
- `pnpm check` on Node 24.20.0/pnpm 11.22.0: passed, including 63 unit tests, formatting, lint, types, generated API stability and production build.
- Full Playwright suite with the installed Chrome channel and temporary configuration: 22 passed, two workers, no retries.
- Production-build browser inspection with sanitized Group/identity fixtures and labels/implications extracted from the backend source: Admin had one explicit `IAM_ADMIN` root, zero checkboxes and every enum value reachable; Search appeared under Basic, Group read under Group management, and Source read under both Source manage/delete branches. Basic had only `BASIC_ACCESS` and `SEARCH_READ`, zero checkboxes and no unrelated administration nodes. An ordinary Group retained six editable checkboxes, with only its actual Source-management grant selected.
- Tree regression tests cover backend labels, explicit-only roots, shared/transitive descendants, incomplete or cyclic metadata and ordinary readonly/editable behavior. Core tests guard automatic full-enum coverage, immutable metadata/results, null-input validation and complete unique registry metadata.

The browser tests use synthetic fixtures, not real-user sessions or production data. No additional feature enforcement, database migration, Keycloak configuration or server rollout was introduced by this follow-up. Temporary browser/preview/configuration scripts were removed; the checksum-verified Node runtime remains in a user-local tooling cache outside the repository. Java LSP references were unavailable.

### Compact tree presentation

At the user's follow-up request, removed per-node enum codes and descriptions and tightened tree spacing to one label row per capability. Root protection badge and readonly hierarchy remain. The focused `group-permissions-section.test.tsx` suite passed all 10 tests, Vite production build passed, and browser inspection of the rebuilt Admin tree confirmed one root, zero checkboxes and no enum-code rows. Backend authority and ordinary Group editing were unchanged; earlier full-gate results above apply to those unchanged paths.

## Latest UI: Onyx-style direct-grant switches

This supersedes both tree presentations above. Source reference: Onyx checkout `06aa2b09cc4aa5135fa2627e5235814e996f1514`, `web/src/views/admin/GroupsPage/GroupPermissionsSection.tsx`, `web/src/refresh-components/SimpleCollapsible.tsx` and `web/lib/opal/src/components/inputs/switch/styles.css`. MemoryOS reuses its existing Radix/UI and semantic tokens rather than introducing Onyx's Opal dependency.

- Removed the tree component and tree-specific tests. Each row has a backend label/description, icon and switch; dividers separate visual categories and the section can collapse. No raw enum codes or expanded-right rows.
- Admin and Basic each expose eight grant heads and disable all switches. Only persisted IAM_ADMIN or BASIC_ACCESS, respectively, is ON. Derived SEARCH_READ is not a row. Ordinary Groups retain six assignable options; selecting a management grant does not falsely mark its implied read grant as explicitly selected.
- `pnpm check`: passed on Node 24.20.0/pnpm 11.22.0, including 61 unit tests, generated API drift checks, lint, formatting, TypeScript and production build. Eight permission-list cases cover direct-grant states, immutable system controls, backend metadata, ordinary mouse/keyboard editing, readonly viewers, collapse preservation and retry.
- Browser inspection of the production build with sanitized API fixtures confirmed Admin's single ON grant and Basic's single ON grant with all controls disabled, and ordinary Group editing toggled Source read while retaining its Source management grant.
- This iteration changes UI only; no additional backend/DB/Keycloak modifications or deployment. No fresh backend or full E2E-suite run is claimed for this UI-only iteration. Temporary preview/browser sessions and tool wrappers were removed.

## Reserved Basic vocabulary follow-up

The user explicitly requested four future child tokens before implementing their features. BASIC_ACCESS now implies exactly SEARCH_READ, CHAT_READ, CHAT_WRITE, IMAGE_GENERATE and LLM_GATEWAY_USE; CHAT_WRITE also implies CHAT_READ. Metadata clearly labels the four upcoming feature rights as reserved, not available/enforced features. IAM_ADMIN continues to expand all enum values. No endpoint, future feature package, Keycloak configuration, navigation or DB migration was added.

- Core, API callback identity projection, OpenAPI and generated web types/fixtures were updated together. Ordinary grants use an explicit six-capability allowlist in service/repository and SQL projections; all Basic children remain derived-only and are rejected by existing DB constraints.
- OpenAPI generation plus core/API targeted verification passed in 4m52s.
- `gradlew.bat clean check --no-daemon --max-workers=2`: passed in 4m01s. Core 152, API 48, connector 20, worker 22: 242 total, 239 passed, zero failures/errors, three optional live Docling tests skipped.
- `pnpm generate:api` and `pnpm check`: passed on Node 24.20.0/pnpm 11.22.0; 61 unit tests plus lint, formatting, types, API drift and production build. Permission tests confirm eight locked system heads/six ordinary heads and no derived leaf controls.
- Initial two-worker Chrome E2E run had four page-load assertion timeouts (captured state included the route's Loading page), with 18 passes. After backend verification completed, the unchanged suite ran with the repository CI's one-worker policy: all 22 passed, no assertion/time-limit changes and no per-test retries. Browser configuration was temporary and used installed Chrome because the bundled Chromium is absent.
- The real Spring HTTP/OIDC integration scenario verifies the expanded six-token Basic identity response and existing deactivation/session boundaries. This is not runtime verification of future Chat/image/gateway functionality, which remains absent.

Temporary wrapper and browser configuration artifacts were removed. No commit, push or server deployment performed.

## System-grant rename and outer Groups-list alignment

- Current names supersede the historical names in earlier evidence: SYSTEM_ADMIN (Administrator access) and SYSTEM_BASIC (Basic access), consistently across persistence, Java, API/OpenAPI and generated web types. No runtime aliases remain.
- V19 upgrades existing data in place. `GroupMigrationSeedTest.upgradesExistingGroupsWithoutChangingIdentitiesMembershipsOrOrdinaryGrants` passed against isolated PostgreSQL from both V17 and V18 starting schemas, preserving Actor/Group IDs, active/inactive memberships, ordinary grants and grant timestamps while advancing Tenant revisions. Retired strings and illegal protected/derived grants are rejected. V1–V18 were not rewritten.
- OpenAPI generation plus focused core/API contract/session verification passed in 4m29s.
- `gradlew.bat clean check --no-daemon --max-workers=2` passed in 2m38s with JDK 25. JUnit reports: core 153, API 48, connector 20, worker 22; 243 total, 240 passed, zero failures/errors, three optional live Docling tests skipped.
- Final `pnpm check` passed on Node 24.20.0/pnpm 11.22.0: API drift, CI image assertion, lint, formatting, types, all 61 unit tests, generated route stability and production build.
- All 24 Playwright scenarios passed with installed Chrome and one worker in 1.7m, including new Groups ordering/search/navigation and CSRF-protected rename/readonly/mobile coverage. No assertion or timeout was relaxed. The first run passed 19 then hit five navigation timeouts with repeated Vite reloads; moving the temporary output directory from an unignored frontend path into ignored `.tmp/` eliminated the reload loop and the entire unchanged suite passed.
- Production-build browser inspection with sanitized API fixtures confirmed three equal 840×74px black cards, the Onyx dark page/info palette, Default badges and source SVGs. The real frontend Admin/Basic details consumed current backend-authored metadata: eight switches disabled, only Administrator access checked for Admin and only Basic access checked for Basic. These are UI runtime checks with fixtures, not a claim of live production integration.
- Layout references: `.tmp/onyx` commit `06aa2b09cc4aa5135fa2627e5235814e996f1514`, GroupsPage/GroupsList/GroupCard and shared color tokens. Copied users/user-manage SVG paths retain the MIT copyright/license notice. Descriptions do not invent connector, document-set or agent counts.
- No production database was connected to or changed, and no commit, push or deployment was performed. Rollout must back up the database and stop old API/worker writers before the normal Flyway startup; after V19, rollback requires the matching pre-cutover database/release rather than old binaries against renamed grants.

## Onyx system-group detail refinement

- This refinement supersedes the earlier eight-switch system presentation. Admin now displays only the disabled Administrator access switch; Basic only the disabled Basic access switch. Checked state still comes from the explicit persisted grant. Backend authority, API metadata, grants and migrations did not change.
- Referenced actual Onyx `web/src/views/admin/GroupsPage/EditGroupPage.tsx` header, system banner, readonly name, member columns/table and default-group resource suppression. Reused the existing Onyx users SVG and scoped palette. MemoryOS retains its own application shell and authorized command semantics.
- `pnpm check` passed in 31s: API drift, lint, formatting, typecheck, all 61 unit tests and production build. Existing permission regressions now enforce one system row and preserved ordinary editing. The navigation regression checks the destination field value rather than capitalization of its label.
- All 24 existing Playwright scenarios passed with installed Chrome, one worker and ignored temporary artifacts in 1.7m.
- Actual production-build browser smoke with sanitized API fixtures confirmed: Edit Group frame width 840px; dark background rgb(25,25,30); readonly system name; exactly one checked/disabled own bundle; no Source API requests even when a system-group response carries `manage_sources`; disabled owner removal and settings Save with no dirty edits.
- Basic member search returned the empty state and clearing restored cached results; candidate selection/add and confirmed removal updated the table from one to two to one member. Both POST requests carried `X-MemoryOS-CSRF: 1`. This exercises the real frontend commands with fixtures, not a live backend mutation.
- Admin likewise issued no Source requests. Ordinary detail retained editable name/six grant switches and Source queries. Basic at 390px had no horizontal overflow; desktop dark and settled light palettes were visually inspected.
- No backend gate was rerun for this frontend-only refinement; prior migration/backend results above remain historical evidence. No production DB changes, commits, pushes or deployment.

## Detail typography and email-only member correction

- Group Name, visible Group Members and Group Permissions share `font-heading-h3`. Removed the section subtitle only; bundle descriptions remain.
- Member/candidate identity cells show email without display names or Owner/Manager/Inactive badges. Account Type and existing protected/member-manager controls remain. Missing email is explicitly unavailable rather than replaced with a display name.
- `pnpm check` passed in 26s, including all 61 unit tests, lint, formatting, types, API drift and production build. No new permanent tests or backend changes.
- Production-build browser smoke used a fixture with a real-shaped displayName and protectedOwner flag. Computed styles confirmed all three headings at 18px and weight 600; member identity text was exactly its email, subtitle absent, owner removal disabled and Basic's sole bundle readonly. Search, candidate add and confirmed removal completed with both POST guards equal to `1`; no Source requests.
- Screenshot inspection confirmed the requested presentation. This correction did not rerun the full E2E suite; its previous 24-test result remains recorded above. No commit, push, deployment or production data change.

## Add-mode stacking fix

- User screenshot exposed simultaneous candidate/member panels, searches and pagers. Removed the independent nested candidate toolbar and made browse/add content mutually exclusive under one mode-aware search toolbar. Done restores member browsing without mutation; selected addition still uses the existing guarded command.
- `pnpm check` passed in 30s with all 61 unit tests, lint, formatting, types, API drift and production build. All 25 Playwright tests passed in 1.8m with installed Chrome/one worker.
- Added the observable Add/Done regression in `groups-list.spec.ts`: exactly one search, only the active pager, disabled add for no selection, restored member draft. The previous stacked layout violates those assertions.
- Production-build browser fixtures covered populated and empty candidate modes. Both had one search/one candidate pager, zero member tables/member pagers underneath; empty mode was also visually verified at 390px with no horizontal overflow. Selecting and adding a candidate returned to the member table; subsequent confirmed removal worked, with both POST requests carrying the same-origin guard. No Source requests or production data mutations.

## Derived-only GROUPS_READ cutover

- Retained GROUPS_READ in the enum, read endpoints and effective/scoped authority. Removed it from ordinary direct-grant policy, all authorization/projection SQL grant allowlists, editable registry metadata and the frontend icon map. Ordinary groups now offer five editable permissions; Manage groups still implies read and Basic/USERS_MANAGE do not gain global Group visibility.
- V20 removes only direct GROUPS_READ rows, increments each affected Tenant revision once and updates the DB CHECK to reject future direct grants. It never adds GROUPS_MANAGE. Historical migrations V1–V19 remain unchanged.
- `gradlew.bat clean check --no-daemon --max-workers=2` passed in 4m12s on JDK 25. JUnit: core 156, API 48, connector 20, worker 22; 246 total, 243 passed, zero failures/errors, three optional live Docling tests skipped.
- New V19 upgrade regression passed for affected/unaffected data. It asserts preserved Actors/bindings, Groups/memberships, other grant rows/timestamps, Sources/associations and other Tenant fields; no elevation of standalone readers; Manage-derived read remains effective; direct INSERT and UPDATE read grants fail. Service/repository tests also reject direct read grants and preserve prior grants on failed replacement; scoped reads remain scoped.
- `pnpm check` passed in 40s: all 61 unit tests, lint, formatting, typecheck, generated API/route drift and production build. The enum/API union remains unchanged intentionally because GROUPS_READ is still a valid effective permission.
- Production-build browser smoke consumed current backend-authored registry metadata with sanitized API fixtures. It showed exactly five ordinary switches and no View groups; enabling Manage groups submitted only `{"capabilities":["GROUPS_MANAGE"]}` with `X-MemoryOS-CSRF: 1`. Visual inspection confirmed the cutover.
- All 25 Playwright scenarios passed in 59.8s with installed Chrome and one worker, including scoped-manager Group visibility/control boundaries and the existing Users/Source flows. The scoped browser fixture no longer fabricates standalone global GROUPS_READ authority.
- Picker audit preserved existing boundaries: SYSTEM_ADMIN-only Users membership editing retains implied read; Users filtering only requests Group options when read authority exists; Source Group options use their separate SOURCES_MANAGE-authorized identity projection. No new endpoints or broader implications were added.
- This is an intentional revocation of standalone read-only access, not a schema reset. No production DB was connected to or changed; rollout must coordinate matching binaries/schema. No commit, push or deployment.

## Unified Manage Sources bundle

- Source read/delete remain endpoint capability tokens but are noneditable and cannot be stored directly. Only USERS_MANAGE, GROUPS_MANAGE and SOURCES_MANAGE are ordinary grant heads. Global SOURCES_MANAGE expands to READ+DELETE; the independent scoped-manager set still excludes deletion.
- V21 revokes standalone Source read/delete grants without promotion. It advances each Tenant revision once for any revoked grant or existing SOURCES_MANAGE grant, including manager-only data whose effective authority changes without grant-row changes. V1–V20 remain immutable.
- `gradlew.bat clean check --no-daemon --max-workers=2` passed in 4m08s with JDK 25. JUnit: core 161, API 48, connector 20, worker 22; 251 total, 248 passed, zero failures/errors, three optional live Docling tests skipped.
- Four V20 migration scenarios passed (revoked-only, manager-only, combined, unaffected), preserving unrelated identities/memberships/grants/timestamps/Source associations and checking direct INSERT/UPDATE rejection. Previous upgrade regressions still run to latest with unaffected ordinary grants.
- `PostgresSourceLifecycleTest.ordinaryMemberWithGlobalSourceManagementCanRemoveItemsAndDeleteUnassociatedSources` exercised the real transactional service with PostgreSQL: a non-Admin, non-scoped member with only global Source management uploaded to an Admin-associated Source, received destructive actions, requested item removal and Source deletion, and observed persisted operations/DELETING states. Existing scoped-denial tests passed; this does not claim completed external object cleanup in that new scenario.
- `pnpm check` passed in 40s, including all 61 unit tests, lint, formatting, types, generated API/route drift and production build. Effective-permission fixtures now include deletion for global Source managers, while scoped fixtures remain unchanged.
- Production-build browser smoke with current backend-authored registry metadata showed exactly Manage users, Manage groups and Manage Sources. Toggling/saving Manage Sources submitted only `{"capabilities":["SOURCES_MANAGE"]}` with the same-origin guard. Visual inspection confirmed no View Sources/Delete Sources switches.
- All 25 Chrome Playwright scenarios passed in 1.1m, including FILE creation/upload/finalization/removal/deletion UI flows and scoped-manager Group controls. No browser assertion or timeout was relaxed.
- Existing global Source managers intentionally gain deletion; standalone read/delete holders are not upgraded. Matching API/worker/schema must be rolled out together. No production DB was connected to or changed, and no commit, push or deployment occurred.

## Main merge and complete identity-icon alignment

- Fetched origin/main `00a42f78b575b0bae8b0990e416a1d828ade2fca`; local main and feat/basic-access-capabilities fast-forwarded from e274390 (132 commits). No merge commit was needed. Original tracked/untracked work was saved in stash `5a8e57ae881f846ad8ff91e770c63ae8f98109af`, restored and left unstaged; the stash remains a safety copy. No push/deployment/DB mutation.
- Resolved 13 conflicted paths without discarding main Chat, Google Drive, richer Search or model management. Ported the direct Search gate into the relocated root Retrieval service and removed the obsolete application-package copy. Regenerated the API client instead of retaining generated conflict fragments.
- Unpublished feature migrations moved V18/V19/V20/V21 → V37/V38/V39/V40; main V1–V36 are unchanged. Local CHECK constraints, direct-grant policy, projections and tests retain MODELS_MANAGE. Current ordinary grants are Users, Groups, Sources and Models management. Prior sections above retain historical pre-merge migration numbers/results.
- Adapted local Source lifecycle regression to main's direct SourceSummary return type and migration fixtures to required Credential.name. Restored Models in all three ordinary-grant SQL allowlists. Fixed the Nginx integration fixture to expand main's new Sentry CSP placeholder; `nginx -t` reproduced the missing-variable failure before that fix.
- Backend `check`: API 125 tests (3 skipped), connector 53 (3 skipped), worker 26 all completed without failures. Core completed 388 tests with one remaining OpenSearch integration failure (non-2xx provider response). An isolated diagnostic run passed; rerunning the original unchanged test failed again. Temporary diagnostics were removed and the OpenSearch implementation/test match main. The complete backend gate is **not green**; no assertion relaxation, retry policy or runtime workaround was added.
- Final frontend check passed: 122 unit tests, API/route generation drift, lint, formatting, types and production build. All 87 Chrome Playwright cases passed, covering main Chat/editor/sharing, Search, Google Drive, Sources and local Groups behavior.
- User-requested identity icons now use one shared exact Onyx SVG module with MIT attribution: navigation, Users header/invitation/actions/account cells, Groups list/create/detail/cards/manager controls/permission rows, Source group selectors/associations/access badge and invitation identity. Removed the obsolete group-only icon file. No remaining Lucide UsersRound/UserRound/UserCog/ShieldPlus/ShieldMinus identity variants in web source.
- Production-build browser inspection covered Users, Groups list and Basic detail. Sidebar Users/Groups paths matched Onyx user/users SVG data; email-only members and readonly Basic bundle remained intact, with no horizontal overflow. Existing action/authority semantics were unchanged by icon updates.
- Git ancestry, empty unmerged index and whitespace checks passed. Old review databases and any database already using the former local V18–V21 history must be explicitly reconciled before runtime startup; never reset or repair their histories blindly.

## Main refresh to 3e4e318

- Fetched origin/main and fast-forwarded both local main and feat/basic-access-capabilities from 00a42f7 to 3e4e318. No merge commit was needed. Saved tracked/untracked work in stash a1ff98dad89ee1255314b638009ea8093e64986b, applied it, resolved conflicts and restored the original unstaged state. The stash is retained.
- Preserved upstream private Chat files/artifacts, uiLanguage and vi/en localization, 100 MiB binary admission, OCR/status behavior and the reorganized architecture docs together with local Basic, scoped managers, private FILE Search and ordinary-only Source associations. Generated clients were regenerated from the merged Spring API instead of choosing a conflicted generated side.
- Published main V37–V42 are unchanged. The six local SQL files moved to V43–V48 with identical Git blob contents; current migration sequence is V1–V48 with no duplicate version. Earlier migration numbers in this record remain historical. Divergent live/local databases were not modified or started against the new layout.
- Backend main/test callers compiled and OpenApiContractTest passed while regenerating openapi.yml. The targeted GroupMigrationSeedTest suite passed all 10 cases with zero failures/skips, covering remapped baselines and system-association preservation. This is not a repository-wide clean check.
- Pinned frontend dependencies installed with frozen lockfile. i18n audit reported zero direct literals/missing static keys; lint and TypeScript passed. Vite production build and four emitted-font checks passed. Existing runtime-config external-script and large lazy chunk warnings remained nonblocking; no limits were relaxed.
- Six production-bundle Chrome scenarios passed with isolated HTTP fixtures: manager Group Permissions hidden, Vietnamese Admin grant editing, public/read-only Source settings hidden while scoped actionable settings remain, Vietnamese ordinary-only Source associations/global clear, scoped private-only 100 MiB FILE setup, and Vietnamese Search denial without a Search request. Screenshots were visually inspected; no fixture scenario issued a mutation. An initial smoke assumption about a scoped visibility select was corrected because the form intentionally renders private-only text, and a conditional empty-state description was routed through its existing translation keys.
- Compared 163 pre-merge user document/output/tmp artifacts against their stash blobs: all unchanged. Both ignored runtime YAML files retained their SHA-256 hashes. No credential, database, provider, deployment, PR or push action occurred.
- Full backend/frontend test suites and live-provider acceptance were not run for this refresh. Temporary verification scripts/screenshots and translation handoff files were removed; the stash remains the recovery checkpoint.

## PR #106 CI repair

The published PR head `7fc0037` contained the frontend but omitted its local backend, migrations and OpenAPI snapshot. CI run `34708036483` failed generated-client stability, frontend image linking and browser scenarios. The user authorized integrating the related local Basic Access implementation into an isolated PR worktree; workstation launch configuration, credentials and unrelated documents remain excluded.

- Integrated the approved backend, V43–V48 migrations, tests and OpenAPI without changing the original dirty checkout or any live database.
- Corrected browser selectors: Add-mode assertions are scoped to Group Members rather than the unrelated Sources search; scoped FILE creation uses the header Add source link rather than ambiguously matching the empty-state link too. Existing behavioral assertions remain.
- `pnpm check` passed with Node 24.14.0/pnpm 11.22.0: 190 unit tests, generated API/route stability, localization audit, lint, formatting, TypeScript and production build. Final changed-test formatting and lint also passed.
- The first two-worker browser run passed 91/93 scenarios, exposing the FILE selector ambiguity and a 30-second aggregate Chat-workspace timeout. After the selector fix, both affected files passed all 20 scenarios with one worker, matching CI concurrency. No timeout or retry policy was relaxed.
- The initial backend gate exposed a schedule authority recheck failure and hit the core-test timeout. `SourceAccessPolicy.lockManage` now rechecks current capability and Source scope after acquiring the Source lock. The retained `GoogleDriveCredentialAuthorityTest` suite passed in 2m32s, including all three schedule/discovery/selection lock-wait cases.
- Production-bundle browser inspection confirmed Add/Done replaces the member search and restores its draft while the independent Sources search remains. This used isolated HTTP fixtures, not live backend acceptance. The first smoke fixture had the wrong Source-list shape; candidate loading in the later smoke was not claimed as verified. The retained browser suite covers candidate loading and pagination.
- The complete one-worker browser rerun passed all 93 scenarios in 11 minutes.
- [CI run 34734308134](https://github.com/kl3inIT/MemoryOS/actions/runs/34734308134) verified repair commit `f9aee0b`: backend `clean check` and infrastructure checks, both browser shards, frontend gate, all image builds, landing and secrets passed. CI Gate passed. Landing initially failed because Docker Hub's token endpoint returned HTTP 502; rerunning only failed jobs passed without a source change. Publish jobs were correctly skipped for a PR; CodeRabbit reported success with manual review required, not an automated review.
- The redundant local full-backend rerun was stopped after the complete Linux CI gate passed; no successful local full-backend claim is made. The focused authorization suite and frontend/browser runs above are local evidence.
- Merge, deployment and divergent-database reconciliation remain outside this repair.
