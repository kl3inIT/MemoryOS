# Connector provider authority verification

## Automated evidence — 2026-09-18

- `./gradlew.bat :core:compileJava` — passed.
- `./gradlew.bat :core:compileTestJava` — passed.
- `./gradlew.bat :core:test --tests io.memoryos.connector.application.PostgresSharePointSyncTest --tests io.memoryos.connector.application.PostgresGoogleDriveSyncTest --tests io.memoryos.connector.application.PostgresSourceRunHistoryTest --tests io.memoryos.connector.application.DefaultProviderAuthorityServiceTest` — passed against real PostgreSQL Testcontainers where applicable.
- `./gradlew.bat clean check` — passed in 6m25s across `core`, `connector`, `worker` and `api`.

The SharePoint regression adopts a provider snapshot, proves replay eligibility, claims it through the common ingestion dispatch query, publishes a Document through the shared index repository, and then proves a changed SharePoint credential revision is no longer replayable. The run-history regression proves terminal supersession settles the Source to `NOT_STARTED` rather than leaving it `INDEXING`.

No live Microsoft Tenant acceptance is claimed here; that remains owned by MEM-126.
