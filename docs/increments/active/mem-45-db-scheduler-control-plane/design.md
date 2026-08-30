# MEM-45 design: db-scheduler control plane

## Outcome

MemoryOS adds db-scheduler as the PostgreSQL-backed, cluster-safe owner of short recurring control tasks. Redis Streams remains execution transport and PostgreSQL capability operations remain business authority.

MEM-45 converts the real Redis stream/group topology reconciliation introduced by MEM-42 into the first persistent recurring control task. It does not add business dispatch, create one scheduler row per operation, or run extraction/indexing in scheduler threads.

## Dependency and schema

The worker uses `com.github.kagkarlsson:db-scheduler-spring-boot-4-starter:16.12.0`, the Spring Boot 4.x starter. MemoryOS Flyway owns the matching PostgreSQL `scheduled_tasks` table through V7. Library schema creation is disabled in every environment.

The scheduler table is control-plane state only:

```text
scheduled_tasks
  -> recurring control task identity
  -> next execution time
  -> picked owner and heartbeat
  -> last success/failure and consecutive failures
  -> optimistic version
```

It never stores Tenant, operation, document, connector, credential, content, or execution lifecycle authority.

## Composition and task ownership

Only `:worker` composes db-scheduler. `:api` remains Flyway owner and has no scheduler runtime. `:core` owns no scheduler types.

The first real recurring task is:

```text
memoryos-redis-topology-ensure
  -> ensure ingestion stream/group
  -> ensure cleanup stream/group
  -> return quickly
```

The task is idempotent. Multiple worker replicas register the same task identity; the shared PostgreSQL row gives one scheduler instance ownership. Redis outage fails the task safely and library retry/rescheduling persists failure evidence without creating a domain failure.

MEM-43 later registers bounded operation-dispatch and reconciliation tasks. Those tasks scan operation tables; they do not create per-operation scheduler executions. MEM-43 relay, MEM-44 consumers, and MEM-51 epoch-fenced drain/removal are separate Linear work packages but one repository increment and production PR; none is independently shippable without unused delivery, dual execution, or an incomplete cutover.

## Runtime policy

```text
table                       scheduled_tasks
threads                     2
polling interval            5s
heartbeat interval          30s
missed heartbeat limit      4
startup                     after application context ready
shutdown max wait           30s
priority                    disabled
```

Scheduler identity comes from `MEMORYOS_SCHEDULER_NAME` when provided; otherwise the library's host identity is used. Replica identities must be unique. Metrics and health use bounded task names only.

## Failure contract

- A process crash leaves picked task evidence and heartbeat in PostgreSQL.
- Another scheduler recovers an execution after the configured missed-heartbeat bound.
- Recurring registration is idempotent across restart and rolling deployment.
- Redis unavailable produces scheduler failure evidence and worker readiness remains down; no operation state changes.
- PostgreSQL unavailable prevents scheduling and fails closed.
- Scheduler saturation cannot consume business execution capacity because the pool is separate and small.
- Shutdown waits a bounded interval and does not invent success for interrupted tasks.

## Security and tenancy

Global topology reconciliation carries no Tenant context. Future tenant-owned control scans load explicit durable `tenant_id` per operation. Scheduler task data never contains entities, credentials, files, extracted content, or secret material.

## Exclusions

- one scheduler execution per document/chunk/operation;
- long-running extraction, embedding, indexing, cleanup, or connector calls;
- generic outbox relay in MEM-45;
- library-managed DDL;
- Quartz, Celery, JobRunr, or Redis locks duplicating scheduler ownership;
- Arconia or ambient TenantContext in workers.

## Verification

- Flyway V7 runs on PostgreSQL and produces the exact required schema/indexes;
- two scheduler instances sharing one PostgreSQL database register one recurring identity and do not concurrently execute it;
- killed/stale ownership becomes runnable under heartbeat recovery;
- the real Redis topology task records success and remains idempotent;
- Redis and PostgreSQL outages expose distinct bounded failures;
- production worker startup and shutdown exercise the scheduler lifecycle;
- JetBrains inspection, focused compile/tests, architecture checks, and the repository gate pass.
