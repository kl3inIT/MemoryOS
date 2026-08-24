# MEM-20 verification

Date: 2026-08-24

## Repository topology

- Added pinned PostgreSQL `18.4-bookworm` with a persistent named volume, loopback-only diagnostic port, health check, bounded resources, and an internal MemoryOS network.
- Added empty-volume bootstrap for isolated `memoryos_app`/`memoryos` and `keycloak`/`keycloak` role/database pairs. Both roles are non-superuser, cannot create roles/databases, and have no cross-database `CONNECT` privilege.
- Added the single shared Keycloak runtime with the exact deployed 26.7.0 image digest, PostgreSQL storage, fixed HTTPS hostname/proxy settings, health check, and stable `keycloak`, `orgmemory-keycloak`, and `memoryos-keycloak` aliases.
- The Keycloak command explicitly disables embedded realm import. An empty database produced only the master realm; `memoryos` and `orgmemory` discovery both returned `404`, proving MemoryOS runtime ownership does not import either repository's realm configuration.
- Added a recurring `ops` backup profile that emits PostgreSQL custom-format archives, `pg_restore --list` files, and SHA-256 manifests.
- Added a staging environment template, ADR 0004, architecture/persistence updates, and a backup/restore/cutover/rollback runbook. No OrgMemory realm/client/user/scope/mapper provisioning was added.

## Local infrastructure verification

- `docker compose ... config --quiet` passed with every required value supplied through an ignored validation environment.
- `sh -n` passed for the PostgreSQL bootstrap, backup, and MemoryOS Keycloak reconciliation scripts.
- An empty disposable PostgreSQL volume became healthy and created exactly `keycloak` and `memoryos` databases.
- Role facts were `keycloak:false:false:false` and `memoryos_app:false:false:false` for superuser/create-database/create-role.
- Cross-database privileges were `memoryos_app -> memoryos=true,keycloak=false` and `keycloak -> keycloak=true,memoryos=false`.
- The backup profile created both archives and restore lists and validated its manifest.
- Shared Keycloak became healthy against the empty target database. With forwarded HTTPS headers its master issuer was `https://auth.kl3in.tech/realms/master`; neither product realm was auto-imported.
- Validation containers, volumes, networks, dummy credentials, and archives were removed after proof.

JetBrains MCP and a YAML language server were unavailable in this session, so no IDE-clean claim is made. Docker Compose interpolation, real container startup, shell parsing, database queries, and repository gates provide the documented fallback evidence.

## Development and staging environments

- Infisical `dev` uses the staging MemoryOS database through the documented loopback SSH tunnel and the shared Keycloak realm while API/web processes remain local. It now selects `SPRING_PROFILES_ACTIVE=development`.
- Infisical `staging` was populated from the 15 existing `dev` application values, then given its server-internal database URL, `SPRING_PROFILES_ACTIVE=staging`, and `MEMORYOS_SESSION_COOKIE_SECURE=true`. The expected key sets are `dev=16`, `staging=16`, `prod=0`; production remains empty.
- The server now has a dedicated `memoryos-staging-server` Universal Auth identity with project `viewer` access, 15-minute access tokens, 90-day client-secret expiry, lockout, and a mode-`0600` bootstrap file. A real server-origin login injected all 16 staging values and returned `staging-bootstrap-ok` without printing values.
- Infisical rejected trusted-IP configuration as a plan-restricted feature. The accepted compensation is project-read-only access, short token TTL, expiring client secret, lockout, owner-only file storage, and documented rotation rather than a false IP-bound claim.
- The server deployment environment is now `/apps/memoryos/.env.staging`; the misleading `.env.production` file was removed. The running writer has not yet been recreated, so runtime selection remains part of the reviewed cutover.
- The API image pins Infisical CLI `0.43.125` by the official Linux archive SHA-256, requires an explicit environment, exchanges Universal Auth at startup, removes client credentials and the short-lived access token from the Java child environment, and drops to UID/GID 1654 before Java starts. The image build passed, and an image-level probe proved all 16 staging values, absent child token, and UID 1654 with `staging-bootstrap-ok`.
- `infisical run --env=dev ... :api:bootRun` selected the `development` profile, connected through a temporary staging database tunnel, started the real API, and returned `{\"status\":\"UP\"}` from `/actuator/health`; the API and tunnel were then stopped.

## Backup, restore, and cutover

The current source is PostgreSQL 18.4 in `zeromail-postgres`. Before any writer cutover, online consistent snapshots were captured for rehearsal and rollback preparation:

```text
source memoryos owner=memoryos_app size=8795839
source keycloak owner=keycloak size=15627967
backup directory=/apps/memoryos/backups/mem20-20260824T070114Z
sha256 manifest=source-databases.sha256 verified
```

Both archives were copied off the source container and host, inspected with PostgreSQL 18 `pg_restore --list`, and verified by the server's SHA-256 manifest. The harness-provided Windows `sha256sum --check` behaved inconsistently for one archive even though direct SHA-256 and Python `hashlib` matched; server GNU `sha256sum --check` passed all four archive/list files.

A non-authoritative restore rehearsal into the new server-side `memoryos-postgres` succeeded with `--no-owner --no-privileges --role ... --single-transaction`. `--no-privileges` was required to discard the legacy `memoryos_pgweb` grant rather than recreate an unrelated role.

Source and rehearsal-target facts matched exactly:

```text
MemoryOS: actors=1, bindings=1, organizations=1, workspaces=1,
          organization_memberships=1, workspace_memberships=1,
          invitations=0, tables=11, flyway_version=3
Keycloak: realms=3, memoryos_realms=1, orgmemory_realms=1,
          memoryos_users=1, orgmemory_users=35,
          memoryos_clients=9, orgmemory_clients=35
Target owners: memoryos=memoryos_app, keycloak=keycloak
Target cross-database CONNECT: denied in both directions
```

The rehearsal was replaced only after PR 25 merged. Source PostgreSQL remained the rollback authority until both writers were stopped and final archives were captured.

## Repository gates

- `./gradlew.bat clean check --no-daemon` passed.
- `pnpm --dir web check` passed generated-client stability, lint, formatting, TypeScript, unit tests, route stability, and production build.
- `pnpm --dir web test:e2e` passed 9/9 Chromium contracts.

## Final cutover evidence

- Reviewed head `7f623aed2db29cc9658fdbfcec7b026fd8b5e1ff` merged as `9579c743ec98246b4082869863760230f8381d3e`; exact merge-SHA CI run `32706606801` passed.
- Final stopped-writer archives live under `/apps/memoryos/backups/mem20-final-20260824T083623Z`, passed restore-list and SHA-256 checks, and were copied off-host before restore. The source databases remain intact.
- The rehearsal target volume was discarded. Final source and target facts matched exactly: MemoryOS `actors=1`, `bindings=1`, `invitations=0`, Organization/Workspace/membership counts all `1`, Flyway `3`; Keycloak `realms=3`, MemoryOS users/clients `1/9`, OrgMemory users/clients `35/35`.
- Target database owners are `memoryos_app` and `keycloak`; both cross-database `CONNECT` checks are false.
- Shared Keycloak became healthy from MemoryOS Compose. Public master, `memoryos`, and `orgmemory` discovery documents retain the exact HTTPS issuers.
- The shared `postgres` alias was ambiguous while the retained ZeroMail source database remained on `shared-infra`; the reviewed follow-up uses the unique `memoryos-postgres` alias for Keycloak and Infisical staging.
- The API needs only `DAC_OVERRIDE`, `SETGID`, and `SETUID` during bootstrap to read the mode-`0600` Compose secret and drop privileges. Runtime process evidence showed the Infisical parent as root and Java as UID 1654; credentials and the short-lived token were absent from Java.
- Owner SSO completed through Keycloak impersonation without resetting the owner's password and resolved the original Actor. A temporary verified Keycloak user accepted a real invitation, resolved a second Actor, received Organization/Workspace membership, and was denied the owner-only invitation API with `403`.
- After an API restart, the invited member's JDBC-backed browser session resolved the same Actor. Database evidence showed the accepted invitation and both memberships before cleanup; the temporary user, Actor, binding, memberships, invitation, and sessions were then removed, restoring the `1/1/0/1/1/0` baseline.
- OrgMemory API, web, MCP, and docs containers remained healthy. Its public web health, `orgmemory-web` authorization endpoint, issuer, and MCP `401` challenge contract passed after the shared Keycloak move.
- The recurring backup profile completed after cutover and verified `memoryos-20260824T090840Z.dump`, `keycloak-20260824T090840Z.dump`, their restore lists, and manifest `20260824T090840Z.sha256`.
