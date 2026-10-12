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
2. Memory: below. Startup (the AOT cache trained by a one-off container at deployment) follows as its own change.
3. The Worker's composition, below.
4. The relay's throughput, below.

## Memory

The Worker ran with no memory limit and the runtime image's options for every JVM (`InitialRAMPercentage=20`, `MaxRAMPercentage=70`), so its heap could grow to 70% of the host. Text and Markdown, and images attached in Chat, are read by Tika in a child JVM, by design ([parsers out of process](../../../../ARCHITECTURE.md)); nothing bounded how many ran, and each took 512 MB at once: the child inherits `JAVA_TOOL_OPTIONS`, and an initial heap of 20% of an unlimited container is cut down to its `-Xmx512m`.

| Part | Change |
| --- | --- |
| Tika child | `-Xms32m` and SerialGC named; at most `MEMORYOS_EXTRACTION_TIKA_MAX_PROCESSES` (2) alive in a Worker, whichever extractor starts them. A read beyond it waits its turn holding nothing (its bytes are spooled once it has a slot, and the extraction timeout starts with its child); after five minutes without a slot it fails as unavailable, which is retried |
| Worker JVM | `InitialRAMPercentage=5`, `MaxRAMPercentage=50`, `UseSerialGC`, `UseCompactObjectHeaders` in the Worker image |
| Container | `limits.memory: ${MEMORYOS_WORKER_MEMORY_LIMIT:-4g}` a replica: 2 GiB of heap, two children of up to 512 MB of heap each, the 256 MB tmpfs, and the JVM's own memory |
| Stop | `stop_grace_period` and the `deploy.sh` timeout 180 s |

The collector is named because the JVM chooses by the memory it sees: SerialGC below about 1.75 GB and G1 above, so a changed limit would change the collector unnoticed. Measured idle at 1 GiB, SerialGC with these options held about 430 MiB and G1 about 480 MiB. A Worker is not latency-bound: a collection pause delays a document, not a person, and leases are renewed every 30 s against a 120 s lease.

The JDK 25 AOT cache (10 s to 4.4 s to start when measured) is not in this change: it has to be trained by a container at deployment with exactly the options the Worker runs with, which is a change to `deploy.sh` of its own.

## The Worker's composition

The Worker composed `core` three ways at once: fifteen hand-picked packages in `scanBasePackages` (five of them IAM's internal `persistence` packages), five imported `...WorkerComponents` classes, and a persistence configuration of its own that listed the entity and repository packages of `iam` and `ai`. It also built the delivery coordinator from `ingestion.application` classes. A module that gained a bean the Worker needed had to be edited in two or three places in the Worker, and a miss showed only when the context failed to start (CI of #576).

Now each module the Worker runs publishes one `<Module>WorkerComponents` class and the Worker imports the list, as Apache DolphinScheduler's servers import one configuration a module:

| Module | `WorkerComponents` |
| --- | --- |
| `audit`, `connector`, `document`, `objectstorage`, `usage` | `@ComponentScan` of the module: the Worker runs it whole, so a new bean needs no change |
| `ingestion` | The module whole, plus the lease scheduler and the one `IngestionCoordinator` a delivery is handed to, which the Worker used to assemble |
| `retrieval` | `embedding`, `opensearch` and `settings`; search for a person stays with the API |
| `iam` | Its persistence packages, entities and repositories, the authorization decision, Group scopes and audit readers |
| `ai` (`ai.ocr`, a named interface) | The reader each Tenant chose, the provider adapters a model reader needs, the catalog's entities and repositories |
| `chat`, `library`, `mcp` | Chosen beans, unchanged |

Decisions:

- **The name stays `WorkerComponents`**, not `WorkerConfiguration` as first planned: five such classes existed and [ADR 0015](../../../decisions/0015-capability-module-map.md) records the convention. The class is deliberately not a component, so the API, which scans all of `io.memoryos`, registers each bean once.
- **A module run whole is scanned whole.** Choosing beans inside a module is what broke; `ai`, `chat`, `library`, `mcp`, `iam` and `retrieval` stay narrow, as they were: the Worker runs a part of each, and the rest of `ai` needs Embabel's agent platform (`ModelCalls`), which only the API starts.
- **A module names its own entities and repositories** with `@EntityScan` and `@EnableJpaRepositories`, so `core` takes `spring-boot-persistence`. The API still lists every module's JPA packages in its own configuration; moving that to the modules is a separate change.
- **`SearchProjectionMaintenance` moved to `ingestion`'s root**: the Worker schedules its reconcile and rebuild, so it is part of what the module publishes.
- **`WorkerDependencyRulesTest`** holds the Worker to it: no dependency on a module's `application` or `persistence` package, no scan beyond its own package, no entity or repository package list, and no import from `core` but a `WorkerComponents` class. A role switch on each bean, as ThingsBoard does with `@TbCoreComponent`, was not chosen: the API and the Worker are already two applications, and a forgotten annotation would fail silently.

## The relay's throughput

Checking pull request 3 on staging showed search work published slowly: a document read in 5 s was searchable only after 73 s on average and 150 s at worst, with the Workers idle, every stream empty and no long transaction. The publication times were 2 rows every 5 s exactly.

| Cause | Evidence |
| --- | --- |
| One relay run published one batch (`batch-size`: 2 for search, 1 for uploaded files, 8 for ingestion) | `RedisOperationRelay.relay` had no loop |
| The relays are due every second but ran once in 5 s | Seven tasks share the 1 s interval; a db-scheduler poll picks `3 × threads` = 6 and polls again only after `polling-interval` (5 s) |
| A recurring task runs on one node at a time | A second Worker added no relay |
| Re-enqueued rows went first | Candidates were ordered by `created_at`; the search upserts for access, ACL and metadata changes reset a row and keep its creation time, so 56 rows from weeks before queued ahead of new work after a restart (56 ÷ 0.4 a second ≈ 140 s) |
| A reader waited for its whole batch | One slow delivery left the batch's other slots idle |

So the cluster-wide ceiling was 24 search operations and 12 uploaded files a minute, whatever the Workers could do.

Decisions (the convention is in [conventions](../../../conventions.md#change-design), the numbers in the [ingestion spec](../../../specs/ingestion.md#dispatch-and-throughput)):

- **Keep PostgreSQL as the authority and Redis Streams as the delivery channel.** Onyx works the same way (a periodic pass over PostgreSQL sends Celery tasks to Redis, up to 8,192 a pass). Workers that take work straight from PostgreSQL with `SKIP LOCKED` (Solid Queue, Oban, River) would remove the relay, and the lease is already in PostgreSQL; it is a rewrite of a path that delivers correctly and reverses a recorded choice, so it stays an open question, to reopen only if the Redis side keeps costing operations work.
- **The relay publishes to a depth, not a batch.** A run publishes until the stream holds the workload's `publish-ahead` of unread deliveries. Unread is the stream's length less its pending entries, because a delivered entry stays in the stream until it is acknowledged and deleted, and work in progress must not hold back new work. The depth is bounded by the rediscovery delay: a delivery unread for 2 minutes is published again.
- **The poll is every second**, so a relay runs when it is due. Lowering the poll alone would only have raised the ceiling to one batch a second.
- **Due order.** Candidates are ordered by `next_dispatch_at`, then creation; the dispatch indexes already hold `next_dispatch_at`.
- **A Worker reads into free slots.** `batch-size` is the deliveries a Worker runs at once for a workload; the reader asks Redis for as many as are free.
- **Gone consumers are deleted** by the topology task when they hold nothing pending and have not read for an hour. Staging held more than 230.
- **Not done: publishing right after commit**, as Onyx does for a file uploaded in Chat. With a relay that runs every second it would save under a second, and the API would need write access to the execution streams and the relay's code moved out of the Worker. To be measured on staging; done only if the numbers ask for it.

## Left as it is

- **A read cut off by a stop still counts in `processing_attempts`**, so it spends one retry of a later `CONNECTION_FAILED`. Not counting it would let a document that kills the Worker be read without end; the count is the only bound on that.
- **A failed OCR connection test answers `CHAT_PROVIDER_UNAVAILABLE`.** It is the code every provider connection of the `ai` module shares (voice, web search, System One); each feature words it for itself.

## Left out

- A limit shared by all replicas for PaddleOCR-VL, the embedding provider and Docling, which each Worker bounds on its own.
- Reclaiming a dead replica's Redis entries beyond the first page of `XPENDING`, comparing leases on the database clock, and the reconcile cursor of OpenSearch kept per process. Postgres rediscovery keeps work correct meanwhile.
- `service.instance.id` in telemetry, and autoscaling on queue depth.
