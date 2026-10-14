# Plan — Projects and library query indexes

- [x] V166: `ix_chat_session_project`, `pg_trgm` and the four trigram indexes of library name search
- [x] Project reorder in one `UPDATE … FROM unnest(…) WITH ORDINALITY`; the reorder test asserts one statement
- [x] `UserFileSearchService.search` looks hits up by document; `UserFileSearchServiceTest` covers shared documents, order and a file revoked mid-search
- [ ] `:memoryos-core:test` for `ChatPersistenceIntegrationTest` green in one run, and `clean check` in CI
- [ ] Confirm on staging that V166 applies under `memoryos_app` and that name search uses the trigram indexes (`EXPLAIN`)
- [ ] Move to `completed/` once merged

## Verification — 2026-10-14

- `UserFileSearchServiceTest`: 1 of 1.
- `ChatPersistenceIntegrationTest` on a database Flyway migrated to V166: 40 of 43, including both Project reorder tests. The three failures were connection errors (pool timeout, connection refused) as the local Docker engine stopped; a rerun could not start a container.
