# MEM-45 verification: db-scheduler control plane

Verified on 2026-08-30.

## Persistence evidence

| Contract | Evidence |
| --- | --- |
| Flyway owns the scheduler DDL | `SchedulerSchemaMigrationTest.flywayCreatesTheSchedulerControlPlaneSchema` applied all seven repository migrations to real PostgreSQL 17.11 and reached V7 with `skipped="0"`, `failures="0"`, and `errors="0"`. |
| V7 matches the required PostgreSQL scheduler shape | The migration test asserts one `scheduled_tasks` table, all 12 required columns, and the three MemoryOS execution/heartbeat indexes. |
| Library schema initialization is absent | No library DDL initializer is configured. API Flyway remains the only schema owner; worker starts after API health. |

## Control-plane evidence

| Contract | Evidence |
| --- | --- |
| The first production task is real, bounded, and recurring | Worker startup registers `memoryos-redis-topology-ensure` with a fixed delay and executes it against real Redis/PostgreSQL in `ControlPlaneIntegrationTest`. |
| Recurring state persists success evidence | `registersExecutesAndRecoversTheTopologyControlTask` waits for the scheduler row's `last_success`, verifies both Redis groups, and observes repeat execution after recovery. |
| Dead ownership is recovered | The test writes a picked execution with owner `terminated-scheduler` and an expired heartbeat. Runtime logs show `DeadExecutionHandler$ReviveDeadExecution`, after which the task executes successfully. |
| Two replicas cannot run one recurring identity concurrently | `twoSchedulersNeverExecuteOneRecurringTaskConcurrently` starts two independent scheduler instances against one PostgreSQL database and asserts one execution with maximum concurrency `1`. |
| Scheduler lifecycle is bounded | Test logs show context-ready startup, scheduler start, and orderly shutdown. The application scheduler uses a 5-second test bound and a 30-second production shutdown bound. |
| Redis failure stays outside business state | The topology task throws a safe bounded failure, Redis readiness returns `503`, and no business operation transition occurs. PostgreSQL is the scheduler's sole persistence backend, so database loss provides no alternate execution path. |

`ControlPlaneIntegrationTest` ran two non-skipped tests with `failures="0"` and `errors="0"` against real PostgreSQL and Arconia-managed Redis.

## Configuration and isolation

- Worker uses `db-scheduler-spring-boot-4-starter:16.12.0` with two threads, 5-second polling, 30-second heartbeat, missed-heartbeat limit 4, context-ready startup, fetch polling, priorities disabled, and a 30-second shutdown wait.
- Scheduler composition exists only in `:worker`; `:api` owns Flyway and has no scheduler runtime.
- `scheduled_tasks` carries scheduler task identity, execution time, pick/heartbeat, result counters, version, and priority only. It carries no Tenant, operation, connector, document, content, or credential data.
- The production Boot JAR contains db-scheduler 16.12.0 and excludes development-only Arconia/Testcontainers artifacts.

## Static and repository gates

- JetBrains inspections with warnings enabled reported no findings in changed Java, Kotlin DSL, TOML, or YAML files.
- V7's only IDE warning was `No data sources are configured`; the real PostgreSQL Flyway test verifies the SQL and exact schema. No warning was suppressed in source.
- Final `./gradlew.bat clean check --no-daemon` completed successfully after the scheduler-readiness assertion was added: 23 actionable tasks, 12 executed and 11 from cache.
- `./gradlew.bat :worker:bootJar --no-daemon` completed successfully; archive inspection verified the production dependency set.

## Deliberate boundary

The scheduler owns bounded control work only. It does not create one scheduler execution per business operation and does not run extraction, indexing, cleanup, connector calls, or a generic outbox relay. MEM-43 will add bounded table-scan relay/reconciliation tasks over existing PostgreSQL operation rows.
