# MEM-42 implementation plan: Redis execution runtime

## Contract and architecture

- [x] Obtain an independent reviewer verdict on the operation-authority, Redis Streams, lease, recovery, and cutover architecture.
- [x] Reconcile the canonical Linear architecture documents and MEM-40–51 issue scopes before code.
- [x] Close MEM-41 as superseded by delivered MEM-24 and preserve explicit durable TenantId in workers.
- [x] Record operation rows as durable dispatch intent and reject a generic one-to-one outbox.
- [x] Add MEM-42 to the repository roadmap and active-increment map without describing Redis as current runtime truth.
- [x] Keep Redis composition in `:worker`; add no Redis dependency/import to `:api`, `:core`, or `:connector`.

## Dependencies and configuration

- [x] Add Spring Data Redis and Arconia Redis Dev Services aliases through the existing version catalog/BOM.
- [x] Add Spring Data Redis to worker runtime and Arconia Redis Dev Services as `testAndDevelopmentOnly`.
- [x] Map explicit production host/port/username/password/TLS/connect-timeout/command-timeout settings without a production localhost fallback.
- [x] Define validated fixed stream/group properties for ingestion and cleanup.
- [x] Update deployment environment examples without committing credentials.

## Redis topology

- [x] Add a worker composition component that idempotently creates ingestion/cleanup streams and consumer groups with MKSTREAM semantics.
- [x] Treat BUSYGROUP as successful convergence.
- [x] Keep topology failure bounded and safe so Redis unavailability affects worker readiness, not PostgreSQL operation lifecycle.
- [x] Add no business publisher, listener, relay, reclaim loop, or operation dispatch state in MEM-42.

## Verification

- [x] Prove a real Redis connection through Arconia Dev Services in development/test mode.
- [x] Prove idempotent stream/group creation.
- [x] Prove synthetic identifier-only XADD → XREADGROUP → PEL → XACK and zero pending count.
- [x] Prove worker readiness distinguishes Redis unavailable from PostgreSQL authority without leaking credentials.
- [x] Prove production runtime classpath/image excludes Arconia Dev Services and Testcontainers.
- [x] Inspect every changed Java, Kotlin DSL, YAML, properties, and XML file with JetBrains warnings enabled.
- [x] Compile affected modules and run focused Redis integration/architecture tests.
- [x] Start and exercise the actual worker runtime with Redis available and unavailable.
- [x] Run the repository gate once after focused verification.

## Consolidation

- [x] Update `ARCHITECTURE.md`, runtime runbook, applicable specs/test matrices, and roadmap with only verified runtime facts.
- [x] Move completed active increments and reconcile delivered roadmap entries in this substantive PR after verification.
- [x] Record verification evidence and keep MEM-42 active until its PR merges.
