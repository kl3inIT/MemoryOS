# The Worker runs as replicas and stops without losing work (MEM-244)

A review on 2026-10-11 found the Worker's claiming sound for several instances (Postgres leases taken with `FOR UPDATE SKIP LOCKED` and fenced by a delivery token, one Redis consumer group a workload, db-scheduler's heartbeat), and three defects, one of which harmed every deployment with a single Worker.

## Requirement

- **A stop does not fail work.** A Worker being stopped finishes what it started within a grace period. Work it cuts off records nothing and stays pending; another Worker reads it once its lease lapses. Before, `RedisStreamWorker.stop()` interrupted every delivery at once and waited seven seconds, `deploy.sh` stopped the Worker after 45 seconds, and an interrupted extraction became `TIMEOUT`, which is not retried: each deployment failed the documents being read for good.
- **Several Workers run from one Compose file.** The Worker had a fixed container name and `deploy.sh` recreated exactly one.
- **The database role holds every pool.** On staging the application role allowed 20 connections against a fixed API pool of 15 and a Worker pool of 10, so the Worker ran on five of its ten connections and no log said so. The limit was raised to 40 on 2026-10-11; a deployment now refuses pools the role cannot hold.

## Design

| Part | Change |
| --- | --- |
| `RedisStreamWorker` | Readers and deliveries run on separate executors. `stop()` stops reading, waits up to `memoryos.redis.shutdown-grace` (`MEMORYOS_WORKER_SHUTDOWN_GRACE`, 100 s) for the deliveries, then interrupts what is left and waits five seconds. A delivery that ends in `CancellationException` stays pending and is logged as `redis.delivery.left_for_reclaim` |
| Coordinators | An extraction or processing failure seen while the thread is interrupted throws `CancellationException` instead of recording a failure or a retry. Every extractor keeps the interrupt when it reports one, so `TIMEOUT` from an interrupt is told apart from a real deadline |
| Compose | No `container_name` for the Worker; `deploy.replicas: ${MEMORYOS_WORKER_REPLICAS:-1}`; `stop_grace_period: 120s` above the grace |
| `deploy.sh` | The Worker is stopped with a 120 s timeout and the API with 45 s. Running containers are found by their Compose service (`compose ps`, the `com.docker.compose.service` label), never by container name, both when the runtime is verified and when the previous runtime is captured for a rollback. Before the reservation, `check_database_budget` reads the application role's real `rolconnlimit` and refuses API pool + replicas × Worker pool + 10 above it |
| db-scheduler | The scheduler name defaults to the container's hostname, unique per replica; the overlays no longer pin one name for all. `polling-strategy: lock-and-fetch`, so replicas take due tasks with `SKIP LOCKED` instead of all reading the same rows |

Pools stay fixed (`minimum-idle` equals `maximum-pool-size`), as `test_database_pool_sizing.py` records: opening connections when load arrives costs latency at the worst moment. Running more Workers means lowering `MEMORYOS_WORKER_DATABASE_POOL_SIZE` or raising the role's limit; the deployment check says which.

## Measurements (staging, 2026-10-11)

Idle Worker, 1 GiB container limit, consumers off: about 9.8 s to start and 463–488 MiB. Below about 1.75 GB the JVM chooses SerialGC on its own; with SerialGC, `InitialRAMPercentage=5`, compact object headers and a JDK 25 AOT cache the Worker started in 4.4 s at about 360 MiB, while G1 with the same options needed 5.9–6.7 s and 494–583 MiB. No transaction is open while a document is extracted, so a pool of ten is not held by long work.

## Pull requests

1. This one: the stop, replicas, scheduler name and polling, the database budget.
2. Memory and startup: a memory limit per replica and an explicit GC once Tika no longer starts a 512 MB JVM a document inside the container; the AOT cache trained by a one-off container at deployment.
3. The Worker's composition: each module publishes one `<Module>WorkerConfiguration` declaring its beans, entities and repositories; the Worker imports those and scans no package of `core`, and an architecture test holds it to them. Today it combines a list of scanned packages, imported `...WorkerComponents` classes and a persistence configuration naming other modules' packages.

## Left out

- A limit shared by all replicas for PaddleOCR-VL, the embedding provider and Docling, which each Worker bounds on its own.
- Reclaiming a dead replica's Redis entries beyond the first page of `XPENDING`, removing dead consumers, comparing leases on the database clock, and the reconcile cursor of OpenSearch kept per process. Postgres rediscovery keeps work correct meanwhile.
- `service.instance.id` in telemetry, and autoscaling on queue depth.
