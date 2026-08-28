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

## Closure reconciliation

PR #32 and deployment hotfix PR #33 merged and deployed. Backup manifest `20260826T135912Z.sha256` verified MemoryOS and Keycloak dumps plus restore lists on-host and off-host. The MemoryOS dump restored into isolated `memoryos_mem28_rehearsal`; V4 applied, the exact application became healthy, counts remained `actors=1`, `bindings=1`, `organizations=1`, `memberships=1`, and `invitations=1`, and Workspace tables/columns became zero. Final staging source SHA `a17ea6c88e647fdd6c2f638dc26cf65061d23b48` passed Flyway V1–V4, public health, owner/member projection, invitation acceptance, and member `403 INVITATION_NOT_OWNER`. Linear records MEM-28 as Done.
