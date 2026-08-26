# MEM-28 verification: Organization-only ownership

## Contract matrix

| Contract | Boundary | Required evidence |
| --- | --- | --- |
| Direct migration | V4 on V1–V3 schema | Historical Workspace tables/columns disappear; Organization and invitation history remain |
| Bootstrap replay | Core runtime | Existing Organization replays with `created=false` using Organization fields, owner binding, and change reference only |
| Admission | Organization resolver + HTTP | Active Organization membership and active Organization are sufficient; inactive rows deny; multiple active Organizations fail |
| Invitation authority | Core + HTTP | Owner operations remain allowed; member operations remain `403 INVITATION_NOT_OWNER` |
| Invitation acceptance | Transaction + PostgreSQL concurrency | Exact binding, one Organization `MEMBER`, and invitation acceptance commit once; no Workspace membership exists |
| Browser contract | OpenAPI + generated client | Current identity contains Actor, nullable Organization, role, and capabilities with no Workspace schema or field |
| Frontend authority | Unit + Playwright | Owner/member presentation and gates remain correct; no Workspace role/default-Workspace claim remains |
| Configuration cutover | API startup + runbook | No default-Workspace property is required or documented; obsolete external secret entries are removable after deploy |
| Architecture cutover | Living docs + Linear | Organization owns future resources; Groups are audiences/capabilities; source ACL is the read ceiling; completed evidence is unchanged |
| Deployment safety | Restored staging copy | Verified dump exists; V4 and exact cutover image start; Flyway/counts reconcile; owner login and member invitation pass; restore-only rollback is documented |

## Evidence log

Evidence is appended only after the corresponding test, command, inspection, or runtime scenario is observed.

### 2026-08-26

- Independent reviewer verdict: `READY` for Organization-only ownership. The review confirmed that the Tasco evidence requires identity-bound access and source-ACL ceilings, not a nested operational container. The user selected a direct clean V4 rather than a compatibility or divergence-preservation migration.
- V4 applied after immutable V1–V3 in every core H2 and PostgreSQL migration list. `DefaultInitialOrganizationBootstrapperTest.currentSchemaContainsNoWorkspaceArtifacts` observed zero `workspaces`/`workspace_memberships` tables and zero Workspace ID columns in the final schema.
- Bootstrap and invitation repositories executed against the Organization-only schema. Invitation acceptance persisted one Organization `MEMBER` row and no second membership model.
- `PostgresInitialOrganizationBootstrapperConcurrencyTest`: 1 test, 0 skipped, 0 failures. `PostgresInvitationAcceptanceConcurrencyTest`: 1 test, 0 skipped, 0 failures.
- Browser/bearer integration passed owner, member, no-membership, anonymous, invitation lifecycle, and member `403 INVITATION_NOT_OWNER` behavior using the Organization-only current-identity contract.
- `OpenApiContractTest` passed; `openapi.yml` and generated Hey API code contain no `CurrentWorkspace`, `defaultWorkspace`, or Workspace field.
- JetBrains inspections ran with warnings enabled for every changed Java file plus `application.yaml`, V4 SQL, and `openapi.yml`. No errors or unresolved code/configuration warnings remain. The intentional custom `X-MemoryOS-CSRF` header retains its existing inspection suppression; V4's no-configured-datasource SQL warning is covered by H2 and PostgreSQL execution.
- `gradlew.bat clean check --no-daemon`: passed.
- `pnpm check`: passed, including generated-client stability, zero-warning lint, formatting, TypeScript, 18 unit tests, route stability, and production build.
- `pnpm test:e2e`: 12/12 Chromium scenarios passed with `Organization owner`/`Organization member` presentation, member deep-link denial, zero invitation requests, and Organization-only invitation copy.
- Linear baseline was superseded by `MemoryOS — Organization ownership, Groups, Provisioning và Source ACL`; MEM-9, MEM-10, and MEM-24 now carry Organization-only scope.

## Staging migration evidence

On 2026-08-26, the pre-cutover production schema reported Flyway V1–V3 and `actors=1`, `bindings=1`, `organizations=1`, `organization_memberships=1`, `workspaces=1`, `workspace_memberships=1`, `invitations=1 (PENDING)`. The historical Workspace and invitation scopes matched the supported default-Workspace flow.

The API writer stopped before backup. The ops profile produced `memoryos-20260826T135912Z.dump`, `keycloak-20260826T135912Z.dump`, both restore lists, and `20260826T135912Z.sha256`; checks passed inside the mounted backup context and again after the archives were copied off-host. The MemoryOS dump restored into isolated database `memoryos_mem28_rehearsal` with identical counts. Exact PR #32 application code applied V4 and became healthy; Flyway V1–V4 succeeded, counts remained `1/1/1/1/1`, and `workspace_tables=0`, `workspace_columns=0`. The rehearsal container, database, runtime-secret export, smoke scripts, and worktrees were removed after verification; rollback archives remain retained.

The rehearsal exposed that `infisical login` honored the configured self-hosted domain while `infisical run` fell back to the cloud default. PR #33 added the missing explicit domain, passed local and latest-head CI, and used the documented CodeRabbit rate-limit fallback with no captured findings or threads. Merge SHA `a17ea6c88e647fdd6c2f638dc26cf65061d23b48` main CI run `32978942562` passed all jobs.

Staging deployed API and web images labeled and tagged with `a17ea6c88e647fdd6c2f638dc26cf65061d23b48`. Both containers became healthy; public `/actuator/health` returned `UP`; anonymous `/api/identity/me` remained `401`; production Flyway showed V1–V4; Workspace tables/columns were absent; the original counts and pending invitation were preserved.

A real browser smoke used Keycloak administrator impersonation for the existing owner without changing the owner password, then created a temporary verified local user for a new invitation. The owner projection was `OWNER + INVITATIONS_MANAGE`; the member projection was `MEMBER + no capabilities`; neither response contained Workspace data; invitation acceptance committed; the member received `403 INVITATION_NOT_OWNER`. Cleanup deleted all temporary Keycloak and MemoryOS evidence and restored `actors=1`, `bindings=1`, `organizations=1`, `memberships=1`, `invitations=1 (PENDING)`, `sessions=0`.
