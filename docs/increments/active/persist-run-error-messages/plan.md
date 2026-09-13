# Persist run error messages — implementation plan

- [ ] Create Linear issue for this increment.
- [ ] Flyway migration: add `error_message`/`error_detail` to `source_run_errors`, `index_attempts`, `source_sync_attempts`; update `source_run_index_transition` trigger to copy the new fields.
- [ ] Extend `WorkLeases` with `safeErrorMessage`/`safeErrorDetail` sanitizers.
- [ ] Update `ConnectorIndexingPort` and `ConnectorSyncPort` signatures; migrate all implementations and callers.
- [ ] Update `DefaultIngestionCoordinator`, `DefaultConnectorSyncService`, `UserFileIngestionCoordinator` to pass exception messages/details.
- [ ] Update `SourceRunError` record, `JdbcSourceRunHistoryRepository` projection, `SourceRunErrorResponse`, `openapi.yml`.
- [ ] Regenerate `web/src/lib/hey-api` types.
- [ ] Update `source-run-history.tsx` error rows to render `errorMessage` and expandable `errorDetail`.
- [ ] Update `google-drive-acl-panel.tsx` failure display.
- [ ] Update tests: `PostgresSourceRunHistoryTest`, `DefaultIngestionCoordinatorTest`, `SourceApiIntegrationTest`, `source-history-presentation.test.tsx`.
- [ ] Run `clean check`; verify real local instance shows actual error messages.
