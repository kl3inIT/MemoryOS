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

- [ ] Tika without a JVM a document, or bounded so a limit is safe
- [ ] Worker `stop_grace_period` and the `deploy.sh` stop timeout to 180 s: 100 s grace and about 9 s of shutdown leave 11 s, and db-scheduler may wait 60 s more
- [ ] Memory limit, explicit GC, `InitialRAMPercentage`, compact headers; measured on zm under load
- [ ] AOT cache trained at deployment

## Pull request 3 — the Worker's composition

- [x] One `<Module>WorkerComponents` a module, with its entities and repositories; the Worker imports those only
- [x] `WorkerDependencyRulesTest` for the Worker's dependencies on `core`
- [ ] CI (the Worker's context tests need Docker); staging deploy, a document read
