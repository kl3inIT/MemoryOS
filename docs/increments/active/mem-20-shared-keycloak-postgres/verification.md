# MEM-20 verification

Date: 2026-08-24

## Repository topology

- Added pinned PostgreSQL `18.4-bookworm` with a persistent named volume, loopback-only diagnostic port, health check, bounded resources, and an internal MemoryOS network.
- Added empty-volume bootstrap for isolated `memoryos_app`/`memoryos` and `keycloak`/`keycloak` role/database pairs. Both roles are non-superuser, cannot create roles/databases, and have no cross-database `CONNECT` privilege.
- Added the single shared Keycloak runtime with the exact deployed 26.7.0 image digest, PostgreSQL storage, fixed HTTPS hostname/proxy settings, health check, and stable `keycloak`, `orgmemory-keycloak`, and `memoryos-keycloak` aliases.
- The Keycloak command explicitly disables embedded realm import. An empty database produced only the master realm; `memoryos` and `orgmemory` discovery both returned `404`, proving MemoryOS runtime ownership does not import either repository's realm configuration.
- Added a recurring `ops` backup profile that emits PostgreSQL custom-format archives, `pg_restore --list` files, and SHA-256 manifests.
- Added production environment template, ADR 0004, architecture/persistence updates, and a backup/restore/cutover/rollback runbook. No OrgMemory realm/client/user/scope/mapper provisioning was added.

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

## Source backup and restore rehearsal

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

The source PostgreSQL databases and running source writers remain unchanged. The target PostgreSQL service is healthy and contains only the rehearsal restore. Final maintenance-window dumps and restore will replace this rehearsal only after the repository change is reviewed and merged.

## Repository gates

- `./gradlew.bat clean check --no-daemon` passed.
- `pnpm --dir web check` passed generated-client stability, lint, formatting, TypeScript, unit tests, route stability, and production build.
- `pnpm --dir web test:e2e` passed 9/9 Chromium contracts.

## Remaining cutover gate

The reviewed-head maintenance window still must stop MemoryOS API and shared Keycloak writers, capture final archives, restore the final snapshots, recreate shared Keycloak from MemoryOS Compose, update the existing MemoryOS Infisical database keys, recreate API/web, and prove both products' authentication. Source databases and rollback archives remain intact until later explicit cleanup approval.
