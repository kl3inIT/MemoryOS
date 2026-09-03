# MEM-43/MEM-44/MEM-51 design: Redis operation execution cutover

## Outcome

MemoryOS replaces direct PostgreSQL polling with one production execution path:

```text
PostgreSQL operation
  -> db-scheduler relay
  -> Redis Stream consumer group
  -> token-fenced PostgreSQL processing
  -> durable completion
  -> XACK
```

MEM-43, MEM-44, and MEM-51 are work packages in this one increment, branch, pull request, and cutover. None is independently shippable. The final runtime contains no disabled consumer, execution-mode switch, dual dispatcher, old batch poller, or compatibility path.

## Authority and delivery

PostgreSQL remains authoritative for Tenant ownership, operation eligibility, claim tokens, leases, retry counts, terminal state, dispatch evidence, and recovery. Redis is a rebuildable execution transport. API transactions create `IndexAttempt` or `CleanupAttempt` rows and never call Redis.

The relay publishes identifier-only records:

```text
tenant_id
operation_kind
operation_id
delivery_id
```

No source, document, file, credential, extracted content, exception, or serialized entity enters Redis. A worker reloads the authoritative operation before any side effect.

## Relay contract

Two concrete cluster-safe db-scheduler tasks relay ingestion and cleanup. Each task atomically claims a bounded PostgreSQL dispatch batch, publishes to its fixed versioned stream, then conditionally records Redis message evidence. Publish success followed by evidence-write failure may duplicate delivery but cannot lose work.

Redis transport failures defer dispatch with bounded backoff and never convert an accepted business operation into a fabricated processing failure. Nonterminal operations without a valid processing lease become dispatchable again after a bounded rediscovery interval, rebuilding work after Redis restart, trim, or complete loss.

Inactive-Tenant indexing is cancelled before dispatch. Cleanup remains dispatchable after Tenant deactivation.

## Consumer contract

The worker owns two fixed consumer loops and groups: ingestion and cleanup. Each loop uses bounded reads and concurrency, stable per-instance consumer identity, and manual acknowledgement.

A delivery is acknowledged only after its operation is durably terminal, or after authoritative PostgreSQL state proves the delivery missing, terminal, superseded, duplicate, or currently protected by another live lease. Completion followed by ACK failure is benign: redelivery observes terminal state and ACKs the obsolete message.

Processing claims use a fresh token and lease for one operation identifier. Long-running work renews the lease through token-fenced updates. A stale worker cannot renew, retry, supersede, fail, or complete. Redis pending entries are reclaimed only when Redis idle evidence and PostgreSQL lease expiry agree.

Retry exhaustion is durable PostgreSQL state. Redis delivery count is diagnostic only. Safe typed extraction failures terminate immediately; unexpected failures retry with bounded backoff and then terminate with a safe code.

## Clean cutover

The repository cutover deletes the scheduled PostgreSQL polling loop, batch-claim APIs, poll-delay configuration, and poller-only tests and documentation in the same change that activates Redis consumers. Development environments may reset current operation and Redis transport state; no migration compatibility code is shipped.

## Scope discipline

This increment implements only concrete ingestion and cleanup relays and consumers. It adds no generic task framework, generic outbox table, event bus, workflow engine, provider-specific stream, Tenant-specific stream, dynamic queue registry, or payload serialization framework.

## Verification boundary

Real PostgreSQL and Redis tests must prove concurrent relay exclusion, duplicate publication safety, Redis loss repair, transport backoff, bounded pressure, delivery/redelivery, ACK-after-commit, stale-token rejection, lease renewal, dual-evidence reclaim, poison-operation termination, workload isolation, graceful shutdown, and the complete command-to-terminal-operation runtime path.