# Shared PostgreSQL and Keycloak runtime migration

This runbook moves the MemoryOS application database and the shared Keycloak database into the MemoryOS-owned PostgreSQL service from [MEM-20](../increments/active/mem-20-shared-keycloak-postgres/design.md). It does not provision the OrgMemory realm and does not migrate Infisical.

## Preconditions

- Use the exact reviewed MemoryOS commit and its `infrastructure/deployment/compose.production.yaml`.
- Load staging values from managed storage into a mode-`0600` environment file outside Git.
- Keep source PostgreSQL, source databases, and rollback archives unchanged until post-cutover approval.
- Confirm at least twice the combined on-disk size of the `memoryos` and `keycloak` databases is free for dumps, restore, and rollback.
- Schedule a maintenance window: stopping shared Keycloak interrupts authentication for both MemoryOS and OrgMemory.

Required deployment values are listed in [`staging.env.example`](../../infrastructure/deployment/staging.env.example). Keycloak and MemoryOS application database passwords are distinct.

## Validate the target topology

```text
docker compose \
  --env-file /apps/memoryos/.env.staging \
  -f infrastructure/deployment/compose.production.yaml \
  config --quiet
```

On a disposable empty volume, start PostgreSQL and confirm the bootstrap creates two isolated databases:

```text
docker compose \
  --env-file /apps/memoryos/.env.staging \
  -f infrastructure/deployment/compose.production.yaml \
  up -d --wait postgres

docker exec memoryos-postgres psql \
  --username memoryos_platform \
  --dbname postgres \
  --tuples-only --no-align \
  --command "SELECT datname FROM pg_database WHERE datname IN ('keycloak', 'memoryos') ORDER BY datname;"
```

Do not use a disposable validation volume for production restore.

## Capture rollback archives

Resolve the current source PostgreSQL container and its administrative user before the maintenance window. Never place a password in the command line; execute `pg_dump` inside the source container through its local administrative socket.

Stop MemoryOS API before the final application dump and stop shared Keycloak before the final identity dump. OrgMemory API/web may remain up but authentication is unavailable until Keycloak returns.

```text
docker stop memoryos-api
docker stop orgmemory-keycloak-1

docker exec <source-postgres-container> pg_dump \
  --username <source-admin-user> \
  --format=custom \
  --file /tmp/memoryos.dump \
  memoryos

docker exec <source-postgres-container> pg_dump \
  --username <source-admin-user> \
  --format=custom \
  --file /tmp/keycloak.dump \
  keycloak

docker cp <source-postgres-container>:/tmp/memoryos.dump /apps/memoryos/backups/source-memoryos.dump
docker cp <source-postgres-container>:/tmp/keycloak.dump /apps/memoryos/backups/source-keycloak.dump
sha256sum /apps/memoryos/backups/source-memoryos.dump /apps/memoryos/backups/source-keycloak.dump \
  > /apps/memoryos/backups/source-databases.sha256
pg_restore --list /apps/memoryos/backups/source-memoryos.dump \
  > /apps/memoryos/backups/source-memoryos.dump.list
pg_restore --list /apps/memoryos/backups/source-keycloak.dump \
  > /apps/memoryos/backups/source-keycloak.dump.list
sha256sum --check /apps/memoryos/backups/source-databases.sha256
```

Copy the archives and checksum manifest off the source host before restore. Record only file names, sizes, hashes, PostgreSQL versions, and object counts; never record secret values.

## Restore into MemoryOS PostgreSQL

Start only the target PostgreSQL service against the final production volume:

```text
docker compose \
  --env-file /apps/memoryos/.env.staging \
  -f infrastructure/deployment/compose.production.yaml \
  up -d --wait postgres
```

The empty-volume bootstrap creates the target roles/databases. Copy the archives into the target container and restore each database under its final owner:

```text
docker cp /apps/memoryos/backups/source-memoryos.dump memoryos-postgres:/tmp/memoryos.dump
docker cp /apps/memoryos/backups/source-keycloak.dump memoryos-postgres:/tmp/keycloak.dump

docker exec memoryos-postgres pg_restore \
  --username memoryos_platform \
  --dbname memoryos \
  --role memoryos_app \
  --no-owner \
  --no-privileges \
  --exit-on-error \
  --single-transaction \
  /tmp/memoryos.dump

docker exec memoryos-postgres pg_restore \
  --username memoryos_platform \
  --dbname keycloak \
  --role keycloak \
  --no-owner \
  --no-privileges \
  --exit-on-error \
  --single-transaction \
  /tmp/keycloak.dump

docker exec memoryos-postgres rm -f /tmp/memoryos.dump /tmp/keycloak.dump
```

Compare source and target facts before starting writers:

- PostgreSQL major/minor version;
- schema/table counts;
- MemoryOS Flyway history and Actor/binding/Organization/invitation counts;
- Keycloak realm, user, client, credential, and session counts;
- database owners and cross-database `CONNECT` privileges.

Any mismatch stops the cutover. Discard the target volume and retry; do not patch source data.

## Cut over shared Keycloak

The MemoryOS Compose service owns the container lifecycle but does not provision the OrgMemory realm. The restored database already contains both realms.

```text
docker compose \
  --env-file /apps/memoryos/.env.staging \
  -f infrastructure/deployment/compose.production.yaml \
  up -d --wait shared-keycloak
```

Verify:

```text
https://auth.kl3in.tech/realms/master/.well-known/openid-configuration
https://auth.kl3in.tech/realms/memoryos/.well-known/openid-configuration
https://auth.kl3in.tech/realms/orgmemory/.well-known/openid-configuration
```

All issuers must remain HTTPS and unchanged. Verify the requested master administrator login through a secret-safe operator channel. Do not run OrgMemory realm provisioning from MemoryOS.

## Cut over MemoryOS API and web

Update the existing MemoryOS secret delivery path so these keys target the new PostgreSQL service:

```text
MEMORYOS_DATABASE_URL=jdbc:postgresql://postgres:5432/memoryos
MEMORYOS_DATABASE_USERNAME=memoryos_app
MEMORYOS_DATABASE_PASSWORD=<managed target password>
```

Then start the complete stack:

```text
docker compose \
  --env-file /apps/memoryos/.env.staging \
  -f infrastructure/deployment/compose.production.yaml \
  up -d --wait
```

Verify MemoryOS API health, owner login, existing-member login, invitation persistence, exact identity resolution, and restart replay. Verify OrgMemory API/web health and one real OrgMemory login before ending maintenance.

## Backup after cutover

The recurring backup profile creates custom-format archives, restore lists, and a checksum manifest:

```text
docker compose \
  --profile ops \
  --env-file /apps/memoryos/.env.staging \
  -f infrastructure/deployment/compose.production.yaml \
  run --rm postgres-backup
```

Copy the resulting `/apps/memoryos/backups` artifacts off-host.

## Rollback

Rollback if shared Keycloak, either public realm, OrgMemory authentication, MemoryOS bootstrap, or target persistence verification fails:

1. Stop MemoryOS API/web and the MemoryOS-owned shared Keycloak container.
2. Recreate the prior Keycloak service against the retained source Keycloak database.
3. Restore the previous MemoryOS database URL/password in the existing secret delivery path.
4. Recreate the previous MemoryOS API container.
5. Verify both public realms and both applications.
6. Preserve the failed target volume and logs for diagnosis; do not drop source databases.

Dropping the old MemoryOS or Keycloak database/role requires a later explicit destructive-cleanup approval after retained backup restore has been exercised.
