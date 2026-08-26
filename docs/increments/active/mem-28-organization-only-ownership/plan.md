# MEM-28 implementation plan: Organization-only ownership

## Decision and scope

- [x] Obtain an independent reviewer verdict on the Organization-only cutover.
- [x] Create Linear MEM-28 with replacement invariants and clean-cutover requirements.
- [x] Record the clean-cutover design before implementation.
- [x] Add MEM-28 to the active increment map and roadmap.

## Persistence and core

- [x] Add the direct V4 schema cutover after immutable V1–V3 history.
- [x] Drop invitation Workspace FK/column, Workspace memberships, Organization default Workspace FK/column, and Workspaces in dependency order.
- [x] Collapse bootstrap, access resolution, invitation authority, membership provisioning, repository rows, and services to Organization only.
- [x] Delete Workspace identifiers and all obsolete Workspace contracts.
- [x] Rewrite H2 and PostgreSQL concurrency fixtures for the Organization-only schema.

## API, configuration, and browser

- [x] Remove default-Workspace bootstrap properties and environment placeholders.
- [x] Remove Workspace from current-identity responses and OpenAPI.
- [x] Regenerate the Hey API client and update all frontend fixtures.
- [x] Replace Workspace-role and default-Workspace claims with Organization-neutral product copy.
- [x] Preserve owner/member capability gating, deep-link denial, query suppression, and actor-change cache isolation.

## Living documentation and Linear

- [x] Supersede the Organization/Workspace Linear architecture baseline with Organization ownership, Groups, and source ACL boundaries.
- [x] Re-scope MEM-9, MEM-10, and MEM-24 before their implementation begins.
- [x] Update architecture, roadmap, capability specs, verification matrices, and development/migration runbooks.
- [x] Preserve completed increment records as immutable historical evidence.

## Verification and delivery

- [x] Inspect every changed Java, YAML, and SQL file with warnings enabled where supported.
- [x] Run focused migration, bootstrap, invitation, identity, architecture, and PostgreSQL concurrency tests.
- [x] Run `gradlew.bat clean check --no-daemon`, `pnpm check`, and Playwright.
- [ ] Capture a verified pre-deploy dump, rehearse V4 on a restored copy, and smoke owner/member flows before staging cutover.
- [ ] Keep MEM-28 active until PR merge, exact-main CI, staging rehearsal/deploy, Linear closure, and move to `completed/`.
