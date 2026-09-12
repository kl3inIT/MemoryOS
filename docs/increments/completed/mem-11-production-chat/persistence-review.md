# Persistence review — 2026-09-11

User steering: prefer Spring Data JPA where appropriate; leave Users/Groups migration to Nhật. Keep one substantive MEM-11 PR and the existing checkout.

| Area | Decision | Inspected reason |
| --- | --- | --- |
| Chat Persona, Project, sharing, feedback | Spring Data JpaRepository | CRUD, owner/Tenant queries and row locks fit standard repository methods; remove the new EntityManager wrapper |
| Chat provider/model/default configuration | Migrate lifecycle to Spring Data JPA | Configuration CRUD and revisions fit entity lifecycle; keep authority projections, insert-on-conflict initialization and bulk reference updates explicit |
| Chat session/message tree and command receipts | JDBC | Recursive branch/context reads, one-running-reply reservation, command idempotency and conditional terminal updates |
| IAM Users/Groups and related identity/membership/invitation repositories | Nhật's scope | Note posted to MEM-55 and MEM-36; preserve existing runtime here |
| Object storage | JDBC | Conditional staged/active/delete transitions, verification/adoption leases and object-write cleanup claims |
| Document/chunks/artifacts | JDBC | Generation-fenced activation/replacement, chunk batches and cleanup claims with SKIP LOCKED |
| Ingestion dispatch/search work | JDBC | Lease/claim tokens, fenced completion, conditional upsert and bounded worker claims |
| Connector/source persistence | JDBC | Shared source/credential authority, operation receipts, sync/selection checkpoints, bulk invalidation and worker leases; source queries are read projections |

Sources: `chat/persistence/ModelCatalogRepository` (previously `JdbcModelCatalogRepository`), `JdbcChatRepository`; `objectstorage/persistence/JdbcStoredObjectRepository`, `JdbcObjectUploadRepository`, `JdbcObjectWriteRepository`; `document/persistence/JdbcDocumentRepository`, `JdbcDocumentChunkRepository`, `JdbcExtractionArtifactRepository`; `ingestion/persistence/JdbcOperationDispatchRepository`, `JdbcSearchWorkRepository`; Connector repositories including Google credential/selection and source lifecycle/query repositories. Paths are relative to `core/src/main/java/io/memoryos/` and are renamed where the implementation changes.

Implementation checks: Hibernate/Flyway mapping validation, API repository scanning, scoped queries and arbitrary offset pagination, assigned UUID/new-state handling, revision conflicts, model default and provider deletion, rollback across ORM and JDBC, then the changed Chat runtime/UI contracts. IAM's existing regression suite must continue to pass without migrating its code.
