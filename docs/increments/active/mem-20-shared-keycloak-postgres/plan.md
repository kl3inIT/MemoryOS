# MEM-20 implementation plan: MemoryOS-owned PostgreSQL and shared Keycloak

## Foundation

- [x] Create and start MEM-20 in Linear.
- [x] Define the shared runtime versus realm-configuration ownership boundary.
- [x] Defer Infisical migration to MEM-17 while allowing secret value updates required by cutover.
- [x] Record the accepted cross-cutting decision in ADR 0004.
- [x] Add MEM-20 to the repository active-increment map and roadmap.

## Repository topology

- [x] Add a pinned PostgreSQL 18.4 service, persistent volume, loopback diagnostic port, health check, and bounded resources.
- [x] Add an empty-volume bootstrap script for isolated `memoryos_app`/`memoryos` and `keycloak`/`keycloak` role/database pairs.
- [x] Add the pinned shared Keycloak 26.7.0 service with PostgreSQL, HTTPS hostname/proxy settings, health check, and stable shared aliases.
- [x] Make API depend on healthy PostgreSQL and use the MemoryOS-owned database hostname.
- [x] Preserve only MemoryOS realm/client provisioning in this repository; add no OrgMemory identity configuration.
- [x] Update environment examples, Docker build context, architecture, persistence policy, and runtime/cutover runbooks.

## Backup and restore

- [x] Identify the exact source PostgreSQL container/database/role ownership for MemoryOS and Keycloak.
- [ ] Stop source writers in a bounded maintenance window.
- [x] Create custom-format MemoryOS and Keycloak database archives.
- [x] Copy archives outside source containers, checksum them, and validate `pg_restore --list`.
- [x] Start an empty target volume and prove database/role bootstrap.
- [x] Restore both archives with explicit target ownership and validate source/target facts in a non-authoritative rehearsal.

## Cutover

- [ ] Recreate shared Keycloak from MemoryOS Compose against target PostgreSQL.
- [ ] Preserve `auth.kl3in.tech`, both realms, network aliases, user/client IDs, and credentials.
- [ ] Update MemoryOS database secret values and recreate API against target PostgreSQL.
- [ ] Verify OrgMemory remains healthy and authenticated through its unchanged realm.
- [x] Keep source databases and archives intact for rollback.

## Verification and delivery

- [x] Run the documented static-analysis fallback because JetBrains/YAML language servers were unavailable.
- [x] Validate shell syntax, Compose interpolation, service health, database isolation, image digests, backup workflow, and rollback commands.
- [ ] Exercise MemoryOS owner/member browser flows and persistence against target infrastructure.
- [x] Run `gradlew.bat clean check --no-daemon`, `pnpm check`, browser contracts, and production Compose/container gates.
- [x] Record secret-safe migration evidence in `verification.md`.
- [ ] Review, commit, push, open the MEM-20 PR, complete the one-pass review/CI loop, and merge only the verified head.
