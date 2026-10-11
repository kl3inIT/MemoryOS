# Plan — Worker replicas (MEM-244)

## Pull request 1 — stop, replicas, scheduler, database budget

- [x] `RedisStreamWorker`: separate reader and delivery executors; `shutdown-grace` before interrupting
- [x] Coordinators leave interrupted work pending (`CancellationException`)
- [x] Compose: no Worker container name, `MEMORYOS_WORKER_REPLICAS`; `deploy.sh`: 120 s stop, containers by Compose service, `check_database_budget`
- [x] db-scheduler: hostname as scheduler name, `lock-and-fetch`
- [x] Tests: `RedisStreamWorkerShutdownTest`, `DefaultIngestionCoordinatorTest`, deployment tests (local, and on zm with `jq`)
- [ ] CI; staging deploy with the new Worker, a redeploy while a document is read
- [ ] Production: the application role's connection limit

## Pull request 2 — memory and startup

- [ ] Tika without a JVM a document, or bounded so a limit is safe
- [ ] Memory limit, explicit GC, `InitialRAMPercentage`, compact headers; measured on zm under load
- [ ] AOT cache trained at deployment

## Pull request 3 — the Worker's composition

- [ ] One `<Module>WorkerConfiguration` a module, with its entities and repositories; the Worker imports those only
- [ ] Architecture test for the Worker's dependencies on `core`
