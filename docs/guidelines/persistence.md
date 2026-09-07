# Production-first persistence

## Policy

Data with a lifecycle beyond one process, or data that must survive restart/deploy, uses deployable PostgreSQL, versioned migrations, database-enforced constraints, transactions, backup, and recovery in the same increment.

In-memory or H2 databases are allowed only for isolated tests or explicitly disposable experiments. They do not replace the production persistence path to reduce feature scope.

A temporary runtime profile, command, or endpoint is not an acceptable substitute for a missing product write flow. See [ADR 0002](../decisions/0002-no-speculative-operational-surfaces.md).

## Schema ownership

- Capability-owned migrations live with the owning capability's resources. Core migrations are under `core/src/main/resources/db/migration/`; V1 establishes initial identity tables, while later IAM migrations add profiles, Account Type, authorization revision, Groups and Source associations.
- Applied Flyway migrations are immutable against any retained database. An explicitly approved early-project baseline reset may replace them only together with recreating every MemoryOS database that recorded those checksums; never create checksum drift against a live schema history.
- Uniqueness, referential integrity, and deletion behavior belong in database constraints when the database is the final concurrency authority.

## Implementation boundaries

- Application services own authorization, input validation, orchestration, transaction boundaries, domain transition decisions, and typed failure mapping. They do not inject `JdbcClient`, contain SQL, map rows, or implement lock/claim mechanics.
- Capability persistence lives in concrete `@Repository` classes under the owning capability's `persistence` package. Group repositories by aggregate, use case, projection, or consistency boundary; do not create one repository per table mechanically.
- Do not add repository interfaces for a single internal JDBC implementation. Inject concrete repositories inside the same closed capability. Introduce a port only when a second implementation, a cross-module consumer, or a testable non-database contract provides concrete value.
- Keep `@Transactional` on the application operation when one command coordinates several repositories. Repository methods participate in that transaction and own SQL, row mapping, locks, conditional writes, claims, bulk updates, and database-specific mechanics.
- Source and ingestion persistence is JDBC-first because it requires explicit row locks, worker leases, conditional transitions, bulk invalidation, PostgreSQL-specific constraints, and multi-join projections. Read projections use a dedicated query repository rather than inflating write repositories.
- The accepted [unified IAM decision](../decisions/0007-unified-jpa-iam-and-group-authorization.md) uses JPA for Actor, binding, profile, Tenant, membership, invitation, bootstrap and Group lifecycle within one closed `iam` capability. Entities and ORM relationships stay internal; do not introduce parallel entity/domain/DTO copies or cross-capability entity navigation. This is the implemented MEM-36 choice, not a pending Groups-only ORM evaluation.
- Keep bounded IAM projections and explicit authorization locks in concrete JDBC repositories. JPA lifecycle writes and JDBC mechanics share the same DataSource and transaction manager. Flyway owns DDL, Hibernate validates it, and open-in-view/ORM permission caches are disabled. Source, Document, Object Storage and Ingestion persistence remain JDBC-first.
- Defer Querydsl or jOOQ until measured dynamic-query or SQL type-safety pressure justifies the dependency and migration cost. Do not add Spring Data repository interfaces merely because JPA is in use; concrete capability repositories remain the default.

## Early-project schema evolution

Until MemoryOS holds external durable user data or a release milestone explicitly closes this policy:

- Optimize for the clean target schema, not backward-compatible rollout machinery. Do not add expand/contract phases, dual reads or writes, shadow columns, compatibility views, backfill frameworks, or deprecated aliases solely to preserve disposable development data.
- A genuinely additive capability uses the next small migration. MEM-12 adding an invitation table does not justify rebuilding unrelated identity or membership tables.
- If an existing shape blocks the clean model, prefer one approved destructive reset over permanent compatibility code: create and verify a backup, recreate the MemoryOS database or affected schema, run Flyway from the selected baseline, rerun the real bootstrap, and reinsert only the minimal data still needed.
- Data preservation is not an acceptance gate during this stage. Backup exists for rollback and evidence, not to force a complex in-place transformation.
- A baseline squash/reset is a coordinated repository-and-database operation. Never edit historical migration checksums while retaining a database that has applied them.
- Revisit this policy before onboarding external users or declaring durable customer data. From that point, migration and recovery plans must preserve committed data.

## Operations

- MemoryOS-owned PostgreSQL deployment: `infrastructure/deployment/compose.base.yaml` plus the selected environment overlay.
- Isolated application database/user: `memoryos` / `memoryos_app`.
- Isolated shared-Keycloak database/user: `keycloak` / `keycloak`; neither role has cross-database `CONNECT`.
- Runtime passwords remain managed values outside Git. Never record them in files, commands, logs, Linear, or verification evidence.
- PostgreSQL binds only to server loopback for diagnostics. Use an SSH tunnel; do not publish it.
- Before migration, rebind, or destructive cleanup, create custom-format archives, restore lists, SHA-256 manifests, and an off-host copy. Source databases remain intact until explicit cleanup approval.
- After restore, run API startup so Flyway validates schema history, inspect actor/binding/Tenant/invitation counts, verify both Keycloak realms, and exercise real OIDC flows for MemoryOS and OrgMemory. See the [shared runtime migration runbook](../runbooks/shared-runtime-migration.md).

## Identity binding recovery

Treat exact `(issuer, subject)` as the external identity key. Never recover or rebind by email, username, realm display name, or domain. On conflict, inspect the current `actor_id`, verify ownership, preserve a backup, perform any approved change in one transaction, and then verify `/api/identity/me`.
