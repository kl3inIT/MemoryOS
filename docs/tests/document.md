# Document verification matrix

| Contract | Evidence |
| --- | --- |
| Successful extraction publishes one eligible Document/version/mapping | `SourceApiIntegrationTest` and `WorkerFileProcessingIntegrationTest` |
| Failed/stale work cannot publish current content | `PostgresSourceConcurrencyTest` stale-token scenario |
| Item removal removes final unreferenced Document/version | API lifecycle and scheduled worker integration |
| Normalized text and SHA constraints execute in V5 | H2 full-context migration plus PostgreSQL concurrency suite |
