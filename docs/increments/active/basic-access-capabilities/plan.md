# Basic Access implementation plan

1. Add enum bundle, protected grant persistence, V18 migration and bootstrap reconciliation; update IAM regression contracts.
2. Enforce SEARCH_READ in retrieval application methods while preserving Source/document eligibility; update constructor callers and tests.
3. Integrate API expectations, regenerate OpenAPI and web client, and show actual Basic grant plus Search gating in the browser.
4. Run focused checks, repository clean check, frontend check and browser validation. Record exact evidence and limitations.
5. Consolidate durable identity/Search/UI contracts after verification. Keep increment active until merge.

Main 00a42f7 is integrated into feat/basic-access-capabilities with local work restored unstaged and retained in a backup stash. Chat/Google Drive/richer Search/model management coexist with local bundles and Onyx Groups UI; feature migrations are now V37–V40. Identity icons are shared exact Onyx paths. Latest frontend verification: 122 unit tests and 87 browser tests passed. Backend merge-specific failures were fixed, but one unchanged-main OpenSearch integration test remains unstable; the full backend gate is not green. See [verification](verification.md). No commit/push/deployment.

## Read-only grant-tree follow-up (superseded)

1. Derive full Admin authority and all-except-self implication metadata from the enum, not a manual list; add noneditable Search metadata and registry coverage.
2. Render Admin/Basic as read-only trees of explicit roots and backend-defined implied capabilities; ordinary Groups keep editable-only grant options.
3. Verify full enum authority, metadata completeness, transitive/shared tree relationships, no system mutation controls, ordinary editing, and browser visuals before recording completion.

## Onyx-style permission-list cutover

The user superseded the tree UI after reviewing Onyx's actual GroupPermissionsSection, SimpleCollapsible and switch styles. Render backend-authored title/description rows with icons, grouped dividers and right-aligned switches in an initially open collapsible card. Subsequent refinements leave system Groups with their own disabled bundle row and ordinary Groups with three assignable management options. Preserve full Admin semantics, readonly/explicit states, keyboard toggles, collapse and failure recovery.

## Reserved Basic permission vocabulary

The user explicitly approved reserving all Onyx-equivalent Basic child names now: SEARCH_READ, CHAT_READ, CHAT_WRITE, IMAGE_GENERATE, LLM_GATEWAY_USE. CHAT_WRITE implies CHAT_READ. All children remain noneditable/derived-only; service, repository and existing DB constraints reject direct leaf grants. Update effective-permission responses, metadata and generated clients/fixtures together. No migration changes, no future feature endpoints, and no additional navigation links.

## System-grant rename and Groups-list alignment

Completed the clean enum/API/generated-client cutover to SYSTEM_ADMIN and SYSTEM_BASIC with Administrator access/Basic access labels. Added V19 instead of rewriting V1–V18; parameterized PostgreSQL upgrades from V17 and V18 preserve existing identities, memberships, statuses, ordinary grants and timestamps. Coordinated rollout/rollback requirements are recorded in the design.

Completed the outer Groups-list redesign using the checked-out Onyx layout, exact user icons with MIT attribution and scoped palette. Preserved server filtering/pagination, real counts, navigation, guarded rename and readonly authorization. Final repository/frontend gates, 24 browser scenarios and production-build visual checks passed; current contracts and verification evidence are consolidated.

## Onyx system-group detail refinement

1. Read Onyx EditGroupPage and member-table source; replace the previous multi-row system permission contract with exactly one readonly bundle row.
2. Align detail header/banner/name and member table with the screenshot, preserve existing authorized commands and suppress system Source sections and their queries.
3. Verify both system groups, ordinary-group controls, member protections and responsive browser rendering; update canonical contracts and record evidence.

Completed and visually verified: both system details now show exactly one readonly bundle row, no Source-sharing UI/queries, the Onyx Edit Group frame and compact member table. Existing member commands remain immediate and guarded; settings Save is disabled with no applicable edits. Frontend check (61 unit tests) and all 24 existing browser scenarios passed. Additional production-build browser smoke covered Basic member search/add/remove, protected owner, Admin, ordinary controls, 390px layout and settled light/dark palettes; see verification.

Completed the requested typography/content correction: equal Group Name/Permissions heading scale, visible Group Members heading, email-only identity cells without badges, no permissions section subtitle. Frontend check passed (61 unit tests); production-browser smoke confirmed all three headings at 18px/600, a named protected owner's email-only row, disabled owner removal, no subtitle and working guarded member search/add/remove.

Fixed stacked Add UI with one shared mode-aware search toolbar and mutually exclusive candidate/member lists and pagination. Frontend check passed (61 unit tests); all 25 browser tests passed, including a new Add/Done regression preserving the member draft. Production-build smoke verified empty/populated candidates, guarded addition returning to members, and 390px rendering without overflow.

## Derived-only Group read cutover

Completed GROUPS_READ derived-only policy and V20 revocation without elevation. Preserved Manage/Admin-derived and scoped read authority, existing Users/Source picker boundaries and all unrelated grants/memberships. Integrated clean check, frontend check, 25 browser tests and production-build five-row/save smoke passed. Coordinated rollout deliberately revokes old standalone read grants; no production mutation or deployment performed.

## Unified Manage Sources cutover

Completed Source read/delete derived-only policy and global SOURCES_MANAGE expansion to both, with scoped-manager deletion still denied. V21 handles revoked-only, manager-only, combined and unaffected data with correct revisions and preservation. Repository/frontend gates, all 25 browser scenarios, real PostgreSQL Source removal/deletion authorization and production-build three-row/save smoke passed. Restart the matching API to load new registry metadata and run V21; no live deployment or DB mutation was performed.

## Main refresh and Onyx identity icons

Fetched origin/main and fast-forwarded main and feat/basic-access-capabilities to 00a42f7. Recovered local work from stash 5a8e57ae881f846ad8ff91e770c63ae8f98109af without committing it; stash remains a backup. Preserve main Chat/Google Drive/richer Search/model management, move unpublished feature migrations to V37–V40, and resolve both textual and contract conflicts. Prior verification sections retain their historical pre-merge migration numbers/results.

The user's follow-up requires exact Onyx Users/Groups icons throughout identity surfaces. A shared MIT-attributed SVG module replaces local/group-only copies and Lucide identity variants in navigation, Users actions/account cells, Groups headers/cards/member controls/permissions, Source group selectors/associations and invitation identity presentation. Preserve behavior and unrelated icons. Verify integrated backend/frontend and actual browser surfaces before completion.

Completed the merge conflict/index recovery and icon cutover. Git confirms main ancestry and no unresolved paths; original stash is retained. Frontend checks and 87 browser tests passed. Backend API/connector/worker checks passed; core has one non-2xx OpenSearch dependency failure also observed on the unchanged original test after removing temporary diagnostics. This known gate limitation is recorded, not suppressed.

## Scoped manager implementation

Implement the approved Groups/Source manager model without adding absent capability surfaces. Shared contracts cover scoped exclusive IAM mutations, managed-group options/validation, Source read/manage/creation policy, actor-bound private FILE Search, and Google credential/selection/scheduler fencing. V41 preserves legacy data while advancing the authority epoch. Verify group delegation/last-group guards, public versus private and multi-group Source boundaries, non-global creation, credential ownership, async revocation, private Search/Chat expansion, and browser action gates. Keep all work uncommitted and avoid live/review database changes.

## Manager permission visibility correction

Hide the entire ordinary Group Permissions section and suppress its registry query without manage_grants; keep authorized grant editing and system-group readonly bundles unchanged. Verify the actual browser surface for scoped manager and administrator using isolated API fixtures, including authority loss. Do not run test suites per user instruction.

Completed the rendering/query gate and removed the Source section's dangling reference to grants "above". Browser smoke against the actual Vite UI with isolated API fixtures confirmed: scoped manager has no Group Permissions or switches and makes zero capability-registry requests; administrator retains an editable switch and Save enables after toggling it; refreshed Group actions without manage_grants remove the section. System-group rendering logic was intentionally preserved, not newly runtime-verified. An initial fixture used the wrong Source-list response shape and was corrected; a synthetic visibilitychange did not trigger the intended refresh, so the successful revocation check used the existing post-mutation query refresh. No test suites ran, no live data changed, and no deployment occurred. This narrow UI evidence does not complete the wider scoped-manager verification.

## Source deep-configuration authority correction

Separate replacement of an existing Google OAuth app from scoped reconnect, retain current global-only roots/discovery/access backend guards, and gate deep-configuration UI by current global authority plus backend actions. Preserve scoped creation and operational controls. Verify with focused runtime/browser smoke rather than test suites, per user preference; no deployment or live identity/data mutations.

Completed the OAuth replacement guard and current-global UI gates. Same-app scoped reconnect and scoped operations are preserved; global permission loss is checked at client lookup and completion. Backend compile, standalone 12-scenario service/IAM smoke, scoped/global Chrome/Vite scenarios, TypeScript and changed-file lint passed without running test suites. Exact scope and tooling limitations are recorded in [Connector verification](../../../tests/connector.md#source-deep-configuration-authority--2026-09-12). No live data or deployment changes; temporary verification helpers removed.

## Read-only Source catalog settings shortcut

Gate the row settings shortcut by nonempty server-projected Source actions, preserving the name link for read-only detail access. Verify manager PUBLIC/read-only rows, scoped actionable private rows and global actionable rows in the rendered catalog; no test suite or live data changes.

Completed the catalog-row gate. Chrome/Vite smoke covered scoped PUBLIC/read-only rows, scoped actionable private rows, global rows and action removal on refresh; name links remain available. TypeScript and changed-file lint/format passed. See [Connector verification](../../../tests/connector.md#read-only-source-catalog-settings-shortcut). No backend policy change, creator-only rule, deployment or test-suite run.

## Persistent workstation PostgreSQL

Inspect installed PostgreSQL and the current dev container; snapshot the database before cutover. Prepare a dedicated local database/application role, restore with compatibility checks, configure the ignored local override in API/worker run configurations, and exercise startup/restart while proving data persists. Keep the original backup and preserve the old runtime until restore verification. Do not run test suites or modify staging.

## Ordinary-only Source associations

Filter global/scoped Source pickers to ordinary Groups; reject system Group IDs in creation/replacement and remove the automatic Admin default. Allow empty selection only with global Source management; preserve scoped nonempty/managed-group checks. Add V42 to remove old system links and enforce the invariant, refresh API/client contracts and affected fixtures, then smoke ordinary selections and Admin global access without mutating the user's database. Source document-read ACL remains separate.

Completed ordinary-only picker/validation/persistence, removed the automatic Admin link, and allowed empty associations for global managers only. Added V42 plus a retained preservation/guard regression, refreshed API/client contracts and fixtures, and verified real isolated PostgreSQL Source/IAM flows plus six Chrome/Vite cases. Admin reads/edits unassociated Sources through GLOBAL capability authority. Exact evidence and rollout caveats are in [Connector verification](../../../tests/connector.md#ordinary-only-source-associations). No user database migration, live runtime restart, deployment or full test-suite run.

## Main refresh to 3e4e318

Fetched origin/main and fast-forwarded main/current feature branch from 00a42f7 to 3e4e318. Restoring stash a1ff98dad89ee1255314b638009ea8093e64986b produced conflicts in localized Groups/Sources/Users/Search UI, canonical docs and generated API clients. Integrate both contracts, regenerate derived clients rather than choosing a generated side, and remap only local migrations V37–V42 to V43–V48. Preserve all local user documents and ignored runtime configuration, keep changes uncommitted and retain the stash. Verification uses isolated dependencies; no live database migration or repair.

Completed conflict integration and focused verification; all local work remains unstaged/uncommitted on HEAD 3e4e318. The backup stash remains a1ff98dad89ee1255314b638009ea8093e64986b. Backend/API generation, all 10 remapped migration cases, frontend localization/lint/types/production build and six browser scenarios passed; exact evidence and nonblocking warnings are in [verification](verification.md#main-refresh-to-3e4e318). Published migrations and protected local configuration/artifacts were preserved. Do not restart against the old feature V37–V42 database without a separately authorized, data-preserving reconciliation.

## Pull request publication

Publish the approved Basic/scoped-manager/Source-access scope from feat/basic-access-capabilities into a non-draft PR against main. Exclude the two workstation run-configuration edits and unrelated user documents/artifacts; keep runtime secrets ignored. Run semantic/static checks, terminating backend and frontend gates, classify in-scope review findings, then commit only approved paths, push without force and verify the remote head. Opening the PR does not authorize merge or deployment. Record exact evidence and the migration-history warning in the PR.

## PR #106 CI repair

Integrate the user-approved omitted local backend, V43–V48 migrations, tests and OpenAPI into an isolated worktree based on PR head `7fc0037`. Keep the original dirty checkout untouched. Repair stale browser fixtures/selectors without weakening behavioral assertions, run `clean check`, frontend checks and browser scenarios, then push normally to the existing PR branch and verify every required remote check. Do not merge or deploy.
