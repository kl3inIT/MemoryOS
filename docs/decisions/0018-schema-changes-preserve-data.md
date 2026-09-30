# ADR 0018: Schema changes preserve data

## Status

Accepted 2026-09-30. Supersedes the "Early-project schema evolution" policy in [persistence](../guidelines/persistence.md#early-project-schema-evolution).

## Context

Until now MemoryOS allowed a destructive reset when an existing schema shape blocked the clean model: back up, recreate the database or schema, run Flyway from a baseline, bootstrap again and reinsert only minimal data. Data preservation was not an acceptance gate. The policy itself said to revisit it before holding durable customer data.

Production (`app.vadan.app`) now serves the Tasco Tenant with real documents, Chat history, meetings and library files, promoted through the manual production workflow ([MEM-171](../increments/completed/mem-171-production-deployment/design.md)).

## Decision

- Every schema change preserves committed data. A database, schema or table is never reset, recreated or squashed to reach a cleaner model.
- Migrations are forward-only and append-only; an applied migration is never edited and its checksum never changes.
- A change that alters an existing shape carries its own data transformation in the migration (or a bounded, idempotent backfill run by the application), and its effect on existing rows is stated in the change: what is kept, what is converted and what is lost on purpose. Dropping data that users created needs an explicit owner decision recorded in the increment.
- A migration that makes the previous release unable to run on the new schema is called out in the pull request and the release notes, because rollback is then a database restore (`deploy.sh` stops and asks for operator recovery).
- Backups before Flyway stay mandatory; a restore is a recovery path, not a way to reshape the schema.

## Consequences

- Clean-model refactors that change stored shapes cost a real migration, sometimes in more than one release (add, backfill, switch readers, then remove).
- Reviewers check each migration for its effect on existing rows and on rollback.
- The early-project allowances (no expand/contract, destructive reset, data preservation not a gate) no longer apply.
