# ADR 0004: MemoryOS owns the shared Keycloak runtime

- Status: Accepted
- Date: 2026-08-24
- Decision owner: MemoryOS

## Context

MemoryOS currently stores its application database in the PostgreSQL container also used by ZeroMail and consumes the Keycloak runtime deployed from OrgMemory infrastructure. The shared Keycloak database contains both `orgmemory` and `memoryos` realms. This saves memory by running one identity provider, but the deployment lifecycle is split across unrelated repositories and PostgreSQL ownership, so MemoryOS cannot reproduce or move its runtime coherently.

The server has limited RAM. Running separate Keycloak containers for MemoryOS and OrgMemory would duplicate JVM and cache overhead without a current isolation requirement. Conversely, copying OrgMemory realm/client provisioning into MemoryOS would create competing sources of truth.

## Decision

MemoryOS repository owns one PostgreSQL 18 runtime containing isolated `memoryos` and `keycloak` databases and owns the lifecycle of the single Keycloak container shared by MemoryOS and OrgMemory.

MemoryOS owns only `memoryos` realm/client provisioning. OrgMemory repository remains the sole owner of `orgmemory` realm/client/user/scope/mapper provisioning. The shared database may contain both realms, but neither repository provisions the other's realm.

The shared Keycloak public hostname remains `https://auth.kl3in.tech`. Existing issuers, user subjects, clients, credentials, and MemoryOS exact `(issuer, subject)` bindings are preserved through database migration rather than reconstructed by email or username.

Infisical remains on its existing infrastructure. Its migration and durable machine-identity integration are separate work under MEM-17.

## Consequences

- MemoryOS Compose owns PostgreSQL, shared Keycloak, API, and web runtime topology.
- One Keycloak JVM continues serving both products, preserving server memory.
- A Keycloak outage affects both products; rollback and verification must treat OrgMemory authentication as a merge/cutover gate.
- MemoryOS can move the shared runtime to another server with database backups and managed bootstrap secrets, but moving it also moves the runtime data of both realms.
- MemoryOS must not contain OrgMemory realm/client configuration.
- OrgMemory must target the public issuer or shared runtime without owning the container lifecycle.
- PostgreSQL roles/databases are isolated even though they share one server process.
- Source databases remain available until explicit post-cutover cleanup approval.
