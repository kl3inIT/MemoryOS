# Plan — Worker replicas (MEM-244)

## Pull request 1 — stop, replicas, scheduler, database budget

- [x] `RedisStreamWorker`: separate reader and delivery executors; `shutdown-grace` before interrupting
- [x] Coordinators leave interrupted work pending (`CancellationException`)
- [x] Compose: no Worker container name, `MEMORYOS_WORKER_REPLICAS`; `deploy.sh`: 120 s stop, containers by Compose service, `check_database_budget`
- [x] db-scheduler: hostname as scheduler name, `lock-and-fetch`
- [x] Tests: `RedisStreamWorkerShutdownTest`, `DefaultIngestionCoordinatorTest`, deployment tests (local, and on zm with `jq`)
- [x] CI; staging deploy with the new Worker, a stop while a document is read ([verification](verification.md))
- [x] `KILL` capability so docker-init can forward SIGTERM (#583), found by that stop
- [x] Production: the application role's connection limit (60), two replicas

## Pull request 2 — memory and startup

- [x] Tika child JVMs bounded: two at once a Worker, an initial heap of 32 MB instead of the whole 512 MB
- [x] Worker `stop_grace_period` and the `deploy.sh` stop timeout to 180 s: 100 s grace and about 9 s of shutdown leave 11 s, and db-scheduler may wait 60 s more
- [x] Memory limit (4 GiB a replica), SerialGC named, heap of half the limit starting at 5%, compact object headers
- [x] CI; staging under a burst: memory of the container, child JVMs at once, nothing failed ([verification](verification.md)); collection pauses were not measured
- [ ] AOT cache trained at deployment

## Pull request 3 — the Worker's composition

- [x] One `<Module>WorkerComponents` a module, with its entities and repositories; the Worker imports those only
- [x] `WorkerDependencyRulesTest` for the Worker's dependencies on `core`
- [x] CI (the Worker's context tests need Docker); staging deploy, a document read ([verification](verification.md))

## Pull request 4 — the relay's throughput

- [x] The relay publishes until the stream holds `publish-ahead` unread deliveries; unread is length less pending
- [x] `db-scheduler.polling-interval` 1 s, so a relay due every second runs every second
- [x] Dispatch candidates in the order they fall due
- [x] A Worker reads into free slots instead of waiting for a batch
- [x] The topology task deletes consumers of Workers that are gone
- [x] Convention in `docs/conventions.md`; numbers and the corrected statements in the ingestion spec
- [x] CI; staging: time from a row's creation to its start for a burst of documents, before and after ([verification](verification.md))
- [x] Publishing after commit is not added: a search operation is published 0.9 s after its creation on average
