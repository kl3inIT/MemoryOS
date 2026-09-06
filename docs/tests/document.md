# Document verification matrix

| Contract | Evidence |
| --- | --- |
| Successful extraction publishes one eligible Document/artifact/mapping | `SourceApiIntegrationTest` and `WorkerFileProcessingIntegrationTest` |
| Failed/stale work cannot publish current content | `PostgresSourceLifecycleTest` stale-token scenario |
| Reprocessing keeps Document ID, replaces current reference and permits changed parser/output | `ExtractionArtifactLifecycleTest` |
| Failed replacement retains old reference; cleanup only selects unreferenced artifacts | `ExtractionArtifactLifecycleTest` |
| Item removal removes final unreferenced Document and artifact | API lifecycle and Redis-stream worker integration |
| V12 retains current metadata/reference, allows legacy null artifacts, removes version/profile tables and text column | `CurrentDocumentMigrationTest`, H2 full-context migration |
