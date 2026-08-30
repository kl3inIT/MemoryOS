# MEM-42 design: Redis execution runtime

## Outcome

MemoryOS adds the real Redis Streams runtime boundary required by later operation relay and worker slices without changing current PostgreSQL operation ownership or the direct polling execution path.

```text
current API transaction -> PostgreSQL operation authority
future db-scheduler relay -> Redis identifier-only delivery
future Redis worker -> PostgreSQL fenced claim/completion
```

MEM-42 provisions only the transport foundation. It does not publish business work, consume work, add dispatch columns, add db-scheduler, or cut over the current worker.

## Accepted architecture

The independent architecture review approved the following invariants before implementation:

- PostgreSQL `IndexAttempt`, `CleanupAttempt`, and future `ProjectionOperation` rows are durable dispatch intent and recovery authority;
- Redis Streams is transient at-least-once execution transport, not Tenant, authorization, lifecycle, or recovery authority;
- no generic one-to-one outbox table is added;
- API commands never call Redis and remain able to commit accepted operations while Redis is unavailable;
- workers carry explicit durable `TenantId`; Arconia Multitenancy remains API-only;
- MEM-43 later relays operation identifiers, MEM-44 later owns consumer groups/PEL recovery/lease renewal, and MEM-51 clean-cuts the direct poller.

## Composition boundary

Only `:worker` receives Spring Data Redis because it will host db-scheduler relay and Redis consumers. `:api`, `:core`, and `:connector` do not depend on Redis.

```text
:core       PostgreSQL capability authority; no Redis imports
:connector  provider implementations; no Redis imports
:api        accepted commands and queries; no Redis dependency
:worker     Redis connection, topology and future relay/consumers
```

Arconia Redis Dev Services is `testAndDevelopmentOnly` in `:worker`. The production runtime classpath and image contain neither Arconia Dev Services nor Testcontainers. Arconia PostgreSQL Dev Services is intentionally not added: the API and worker processes require the same authority database, and PostgreSQL Dev Services cannot provide shared-service discovery across them. Local development retains one externally managed datasource; PostgreSQL integration tests retain isolated explicit containers.

## Runtime configuration

Spring Boot owns the connection factory and health contributor. Production configuration is explicit and secret-backed:

```text
MEMORYOS_REDIS_HOST
MEMORYOS_REDIS_PORT
MEMORYOS_REDIS_USERNAME
MEMORYOS_REDIS_PASSWORD
MEMORYOS_REDIS_SSL_ENABLED
MEMORYOS_REDIS_CONNECT_TIMEOUT
MEMORYOS_REDIS_COMMAND_TIMEOUT
```

There is no implicit localhost/default credential fallback in staging or production. Arconia supplies an isolated Redis container only in development and tests when an explicit connection is absent.

API health remains independent of Redis. Worker readiness reports Redis unavailable while PostgreSQL operation state remains untouched.

## Stream topology

MEM-42 reserves only implemented workload names:

| Workload | Stream | Consumer group |
| --- | --- | --- |
| Ingestion | `memoryos:work:ingestion` | `memoryos-ingestion-workers` |
| Cleanup | `memoryos:work:cleanup` | `memoryos-cleanup-workers` |

Names are deployment-stable and contain no Tenant, connector, filename, content, credential, or secret text. Consumer identity is supplied by worker instance configuration in MEM-44; it is not part of the stream key.

A worker composition component idempotently ensures both streams and groups with MKSTREAM semantics. `BUSYGROUP` is success. Redis connection/authentication failure is bounded, logged without secrets, and leaves application readiness down without fabricating domain failure.

## Smoke contract

The MEM-42 integration smoke uses identifier-only synthetic records against real Redis:

```text
ensure stream/group
-> XADD synthetic operation reference
-> XREADGROUP into PEL
-> XACK
-> verify pending count returns to zero
```

The synthetic path proves connection, serialization, group creation, delivery, pending evidence, and acknowledgement. It is test infrastructure, not a production business dispatcher or consumer.

## Security and operations

- TLS and authentication failures surface as execution-readiness failures.
- Credentials never appear in logs, health details, stream fields, metrics, or committed configuration.
- Spring Session stays on JDBC.
- Redis repositories/domain mirrors are forbidden.
- Redis persistence/backup is not accepted as recovery authority; later reconciliation rebuilds dispatch from PostgreSQL.
- Stream/group naming uses bounded fixed labels.

## Exclusions

- direct API `XADD`;
- generic outbox or domain-event bus;
- business relay and dispatch evidence;
- production Redis provisioning through Arconia;
- Redis-backed HTTP sessions or domain repositories;
- consumer business processing, reclaim, poison handling, backpressure, or lease renewal;
- compatibility flags or changes to the current polling worker.

## Verification

- inspect every changed Java, Kotlin DSL, and YAML file with JetBrains warnings enabled;
- compile affected Gradle modules;
- prove the worker production runtime classpath excludes Arconia Dev Services and Testcontainers;
- run real-Redis topology/delivery/PEL/ACK integration coverage;
- start the actual worker with Redis available and observe readiness `UP`;
- start it with Redis unavailable and observe bounded readiness failure without PostgreSQL state mutation;
- run architecture tests proving only `:worker` depends on Redis.
