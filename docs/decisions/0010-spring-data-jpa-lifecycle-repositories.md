# 0010 — Spring Data JPA for lifecycle repositories

Status: Accepted, implementation started 2026-09-11.

## Context

The user approved JPA for Chat Persona, Project, sharing and feedback, then requested Spring Data repositories where they reduce persistence code. The existing concrete EntityManager repository convention is a repository choice, not a requirement of JPA or PostgreSQL locking.

## Decision

Use Spring Data `JpaRepository` for these Chat entity lifecycles and the provider/model/default configuration lifecycle. Keep authorization, validation and multi-repository transactions in application services, scoped queries and ORM mechanics in capability persistence. Entity relationships never cross capability boundaries. Queries and locks can use `@Query` and `@Lock`; a genuine need for EntityManager refresh or another unsupported operation can use a custom fragment.

Keep JDBC for Chat tree/command reservations, idempotent database initialization, authority projections, and worker/document/object-storage state machines that already use conditional SQL, generation fencing or leases. Do not translate working SQL into native `@Query` methods solely to rename its abstraction.

Spring Data lifecycle writes and JDBC mechanics share one DataSource and JpaTransactionManager. Flyway owns DDL, Hibernate validates, and open-in-view and permission caches remain disabled. Assigned UUID entities must have correct new-state detection; use a nullable version where optimistic versioning already exists and retain database revision semantics.

The user assigned IAM Users/Groups migration to Nhật, documented in MEM-55 and MEM-36. This PR does not change IAM lifecycle repositories. ADR 0007's closed IAM capability, authority semantics and mixed JPA/JDBC transaction model remain in force; this decision changes the default repository implementation guideline only.

## Consequences

Spring Data supplies routine CRUD and typed declarative query implementations. Custom code remains where it enforces a real persistence contract. Regression evidence must cover scoped reads, optimistic conflicts, delete/default behavior, mixed transaction rollback and runtime API composition; compilation alone does not establish migration acceptance.
