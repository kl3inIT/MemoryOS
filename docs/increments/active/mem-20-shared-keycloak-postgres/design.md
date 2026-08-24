# MEM-20 design: MemoryOS-owned PostgreSQL and shared Keycloak runtime

## Outcome

MemoryOS owns a portable production deployment containing one PostgreSQL 18 instance, the single Keycloak runtime shared by MemoryOS and OrgMemory, the MemoryOS API, and the MemoryOS web application. MemoryOS application data leaves the ZeroMail PostgreSQL container; Keycloak data leaves the OrgMemory PostgreSQL deployment. Existing public issuers, Keycloak realm/user/client identifiers, MemoryOS Actor bindings, and OrgMemory authentication remain unchanged.

Infisical remains on its current infrastructure. MEM-20 may update existing MemoryOS runtime secret values at cutover, but does not migrate or redesign the secret-management service; MEM-17 owns that work.

## Runtime topology

The MemoryOS Compose project owns:

- `memoryos-postgres`: PostgreSQL 18.4 with one persistent volume;
- `shared-keycloak`: Keycloak 26.7.0 using the `keycloak` database;
- `memoryos-api`: using the `memoryos` database;
- `memoryos-web`: same-origin browser gateway.

`memoryos-postgres` contains isolated logical databases and roles:

```text
memoryos database <- memoryos_app role
keycloak database <- keycloak role
```

Roles have no cross-database ownership or grants. PostgreSQL binds only to server loopback for diagnostics and SSH tunneling. The services communicate on the MemoryOS-owned internal network; Keycloak additionally joins `shared-infra` and `proxy-network` with stable `orgmemory-keycloak` and `memoryos-keycloak` aliases so existing consumers and the public reverse proxy do not change.

## Shared Keycloak ownership boundary

MemoryOS owns the shared Keycloak container lifecycle: image, PostgreSQL connection, hostname/proxy settings, health check, resource limits, networks, and deployment procedure. The runtime database continues to contain both `memoryos` and `orgmemory` realms.

MemoryOS repository owns provisioning only for the `memoryos` realm and MemoryOS clients, mappers, owner policy, and SMTP policy. It must not contain, import, reconcile, or delete OrgMemory realm/client/user/scope/mapper configuration. OrgMemory repository remains the source of truth for the `orgmemory` realm. Starting the shared runtime and provisioning either realm are separate operations.

The public hostname remains `https://auth.kl3in.tech`, preserving exact issuer strings and `(issuer, subject)` identity bindings. No issuer migration is introduced.

## PostgreSQL bootstrap

The official PostgreSQL 18.4 Bookworm image is pinned by digest. On an empty volume, a checked-in initialization script creates the application and Keycloak roles/databases from deployment-provided passwords. It is an infrastructure lifecycle script, not an application runtime mode.

The PostgreSQL superuser password, MemoryOS role password, and Keycloak role password remain external managed values. The init script sends password values through `psql` variables/stdin-compatible execution and never prints them. Existing non-empty volumes are never silently reinitialized.

## Migration and rollback

Cutover is backup-first and reversible:

1. Build and validate the new Compose topology without switching writers.
2. Stop MemoryOS API writes and shared Keycloak writes during the final dump window.
3. Create custom-format `pg_dump` archives for `memoryos` and `keycloak` from the source PostgreSQL container.
4. Copy archives off the source container, compute SHA-256, inspect `pg_restore --list`, and retain source databases unchanged.
5. Start the new PostgreSQL instance, restore into pre-created owner databases with explicit ownership handling, and validate object/table/Flyway/realm counts.
6. Recreate shared Keycloak from MemoryOS Compose against the restored database while preserving the public hostname and network aliases.
7. Update the MemoryOS database URL/password in the existing secret delivery path and recreate MemoryOS API.
8. Verify both public realms, MemoryOS owner/member authentication, OrgMemory health/authentication, and MemoryOS persistence.

Source databases and rollback archives remain intact until a later explicit destructive-cleanup approval. MEM-20 cuts runtime dependencies but does not drop retained source data in the same cutover.

## Failure behavior

- New PostgreSQL health failure: no writer cutover; source services remain authoritative.
- Restore mismatch: discard the new volume, correct the restore, and retry; never patch retained source data.
- Keycloak failure after cutover: point shared Keycloak back to the source database and recreate the prior production service.
- MemoryOS bootstrap mismatch: restore the source API database connection; never bypass aggregate verification.
- OrgMemory authentication regression: rollback Keycloak immediately because the shared identity runtime serves both products.

## Verification

Verification requires:

- Compose interpolation and image digest checks;
- clean empty-volume bootstrap of both roles/databases;
- custom-format dump checksums and restore-list validation;
- source/target schema, Flyway, realm, user, client, and membership counts;
- public HTTPS discovery for master, `memoryos`, and `orgmemory` realms;
- MemoryOS owner login, existing-member login, invitation/member persistence, and API health;
- OrgMemory API/web health plus an OrgMemory OIDC login contract;
- exact rollback artifacts and commands recorded without secret values;
- repository `clean check`, frontend checks, and production Compose validation.

## Exclusions

No Infisical migration, second Keycloak container, OrgMemory realm provisioning in MemoryOS, issuer/subject rewrite, application endpoint, temporary Spring profile, or source-database deletion is introduced.
