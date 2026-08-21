# Production-first persistence

## Policy

Data with a lifecycle beyond one process, or data that must survive restart/deploy, uses deployable PostgreSQL, versioned migrations, database-enforced constraints, transactions, backup, and recovery in the same increment.

In-memory or H2 databases are allowed only for isolated tests or explicitly disposable experiments. They do not replace the production persistence path to reduce feature scope.

A temporary runtime profile, command, or endpoint is not an acceptable substitute for a missing product write flow. See [ADR 0002](../decisions/0002-no-speculative-operational-surfaces.md).

## Schema ownership

- Capability-owned migrations live with the owning capability's resources. The current identity migration is `core/src/main/resources/db/migration/V1__create_identity_tables.sql`.
- Applied Flyway migrations are immutable against any retained database. An explicitly approved early-project baseline reset may replace them only together with recreating every MemoryOS database that recorded those checksums; never create checksum drift against a live schema history.
- Uniqueness, referential integrity, and deletion behavior belong in database constraints when the database is the final concurrency authority.

## Early-project schema evolution

Until MemoryOS holds external durable user data or a release milestone explicitly closes this policy:

- Optimize for the clean target schema, not backward-compatible rollout machinery. Do not add expand/contract phases, dual reads or writes, shadow columns, compatibility views, backfill frameworks, or deprecated aliases solely to preserve disposable development data.
- A genuinely additive capability uses the next small migration. MEM-12 adding an invitation table does not justify rebuilding unrelated identity or membership tables.
- If an existing shape blocks the clean model, prefer one approved destructive reset over permanent compatibility code: create and verify a backup, recreate the MemoryOS database or affected schema, run Flyway from the selected baseline, rerun the real bootstrap, and reinsert only the minimal data still needed.
- Data preservation is not an acceptance gate during this stage. Backup exists for rollback and evidence, not to force a complex in-place transformation.
- A baseline squash/reset is a coordinated repository-and-database operation. Never edit historical migration checksums while retaining a database that has applied them.
- Revisit this policy before onboarding external users or declaring durable customer data. From that point, migration and recovery plans must preserve committed data.

## Operations

- Shared PostgreSQL deployment: `/apps/postgres/docker-compose.yml`.
- MemoryOS database/user: `memoryos` / `memoryos_app`.
- Runtime password location: `/apps/memoryos/secrets/postgres-password`. Never record the value.
- The database port remains loopback-only. Use an SSH tunnel for local operation; do not publish the port.
- Before a destructive migration or approved rebind, create and verify a database backup.
- After restore, run API startup so Flyway validates schema history, inspect actor/binding counts, and execute the real OIDC smoke flow.

## Identity binding recovery

Treat exact `(issuer, subject)` as the external identity key. Never recover or rebind by email, username, realm display name, or domain. On conflict, inspect the current `actor_id`, verify ownership, preserve a backup, perform any approved change in one transaction, and then verify `/api/identity/me`.
