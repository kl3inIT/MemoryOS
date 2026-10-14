# Projects and library query indexes

Backend only, no contract change. Three reads and one write slowed down as a person's rows grew.

## Requirement and design

- **A Project's conversations.** `JdbcChatRepository.projectSessions` filters `tenant_id`, `owner_actor_id`, `project_id`, `deleted_at IS NULL` and orders by `updated_at DESC, id`; `unlinkProject` filters the same three keys. `chat_session.project_id` (V36) had no index, so both walked the owner index and filtered every conversation. V166 adds `ix_chat_session_project (tenant_id, owner_actor_id, project_id, updated_at DESC, id) WHERE project_id IS NOT NULL`, whose order is the listing's order.
- **Library name search.** `JdbcLibraryRepository` matches `filename ILIKE '%text%'` on uploads (`chat_user_file`), generated files (`chat_file_artifact`) and images (`chat_image_artifact`), and an image's `revised_prompt` the same way. V166 enables `pg_trgm` and adds a GIN `gin_trgm_ops` index on exactly those four columns, unwrapped, because the query applies `ILIKE` to the bare column. Search ranking and OpenSearch are untouched.
- **Project reorder.** `ChatProjectService.reorder` wrote one `UPDATE` per Project, up to 1000, under the owner lock. `JpaProjectRepository.place` now writes every position in one statement: `unnest(ids) WITH ORDINALITY`, position = ordinal − 1, same tenant and owner scoping, `revision` and `updated_at` untouched. Validation and the lock stay in the service.
- **File search mapping.** `UserFileSearchService.search` scanned every readable file for each hit. It now groups the readable files by document once and looks each hit up; the output and its order are unchanged.

## Migration notes

- Plain `CREATE INDEX`, as every earlier migration: Flyway runs each migration in a transaction, which `CONCURRENTLY` cannot. The tables are per-person and small enough to build in place.
- `pg_trgm` ships with the official PostgreSQL image and is a trusted extension, so the database owner `memoryos_app` (`infrastructure/postgres/bootstrap-database.sql`) may create it. A database whose Flyway role does not own it needs the extension created by an administrator first.

## Left out

- An index on `chat_user_file.content_sha256` for duplicate-upload detection: no query reads it yet, so it ships with that query ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)).
