# MEM-43/MEM-44/MEM-51 implementation plan: Redis operation execution cutover

## Contract and persistence

- [x] Reconcile the three Linear work packages, current architecture, local Onyx reference, and clean-cutover decision.
- [x] Add Flyway-owned dispatch evidence and bounded relay indexes to index and cleanup attempts.
- [x] Add concrete ingestion dispatch contracts and JDBC persistence without a generic outbox or event bus.
- [x] Replace batch claim APIs with identifier-scoped token-fenced claim, renewal, retry, and reclaim evidence.

## MEM-43 relay

- [x] Add separate bounded db-scheduler relay tasks for ingestion and cleanup.
- [x] Publish identifier-only records to the fixed versioned Redis streams.
- [x] Persist conditional dispatch evidence, bounded transport backoff, and rediscovery after lost transport state.
- [x] Preserve inactive-Tenant indexing cancellation and inactive-safe cleanup dispatch.
- [x] Bound dispatch by concrete stream and pending-entry pressure.

## MEM-44 consumers

- [x] Add fixed ingestion and cleanup consumer-group loops with stable worker identity.
- [x] Process one authoritative PostgreSQL operation per delivery with token-fenced lease renewal.
- [x] ACK only after durable completion or authoritative obsolete-delivery proof.
- [x] Reclaim pending entries only when Redis idle and PostgreSQL lease-expiry evidence agree.
- [x] Bound unexpected retries and terminal-fail exhausted poison operations with safe codes.
- [x] Stop new reads during graceful shutdown and leave unfinished work reclaimable.

## MEM-51 cutover

- [x] Delete direct PostgreSQL polling, batch claims, poll-delay configuration, and poller-only tests.
- [x] Keep one Redis execution path with no mode flag, dual dispatcher, or compatibility adapter.
- [x] Reset development transport/operation state during rollout instead of shipping migration choreography.

## Verification and durable records

- [x] Add real PostgreSQL relay concurrency, duplicate, retry, eligibility, and loss-repair tests.
- [x] Add real Redis delivery, redelivery, reclaim, pressure, isolation, and topology-loss tests.
- [x] Exercise API command through relay, stream consumer, durable finalization, and acknowledgement.
- [x] Inspect every changed Java, SQL, YAML, properties, XML, and Kotlin DSL file with warnings enabled.
- [x] Run focused tests, `clean check`, production image builds, and an actual worker runtime smoke test.
- [x] Consolidate architecture, ingestion specification, verification matrix, configuration, runbook, and roadmap facts.
- [ ] Merge one reviewed head and record exact-SHA CI and runtime evidence on MEM-43, MEM-44, and MEM-51.