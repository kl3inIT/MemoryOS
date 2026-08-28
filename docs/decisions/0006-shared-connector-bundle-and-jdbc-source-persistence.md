# ADR 0006: Shared connector bundle and JDBC-first source persistence

## Status

Accepted

## Context

MEM-35 introduces the first provider adapter, persistence-backed worker, and Connector/Document/Ingestion capabilities. FILE requires Apache Tika in worker but not API. Future Google Drive will require different SDK/OAuth weight in both deployables. Source processing also requires explicit row locks, leases, conditional transitions, multi-join projections, and bulk invalidation.

## Decision

Provider implementations live in one `:connector` Gradle integration bundle under independent `io.memoryos.provider.<provider>` folders. MEM-35 creates only `provider.file`. Core owns public provider ports and never depends on the bundle. Worker selects the bundle at runtime; API excludes it in MEM-35. MEM-9 later adds Google Drive to the same bundle and accepts bundle-wide API dependency/CVE/image cost.

Source and ingestion persistence remains JDBC-first. `DefaultSourceManagementService` owns authorization, validation, orchestration, transition decisions, and `@Transactional` boundaries. Concrete repositories under `connector.persistence` own SQL, row mapping, locks, claims, bulk updates, and projections. Repositories follow consistency/use-case boundaries and have no single-implementation interfaces. MEM-35 adds no JPA, Querydsl, or jOOQ and does not migrate existing JDBC repositories for style.

## Consequences

- The fourth Gradle module isolates provider SDKs from core without process/container/protocol complexity.
- Worker carries Tika; API remains parser-free until another provider needs the shared bundle.
- Provider folders can move into separate modules if measured SDK conflicts, image/CVE cost, independent rollout, or deployable selection pressure appears.
- Explicit JDBC keeps concurrency semantics visible and testable; application code remains SQL-free.
- JPA remains permitted when entity lifecycle or relationships reduce real complexity. MEM-36 Groups is the next concrete evaluation point.
