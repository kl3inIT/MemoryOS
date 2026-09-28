# MEM-26 — Chat reply stream replay in Redis

Linear: [MEM-26](https://linear.app/memory-os/issue/MEM-26) "Add resumable tenant-authorized live updates". Related: [MEM-101 Deep research](../../completed/mem-101-deep-research/design.md).

## Why now

MEM-26 waits for "an approved use case [that] names its producer and consumer". This increment is that use case and nothing wider:

| | |
| --- | --- |
| Producer | A Chat turn in the API (`ChatTurnService` → `StreamBufferWriter`): answer text, reasoning, tool progress, image and Deep research events, then the committed outcome |
| Consumer | The Chat thread in the browser (`MemoryOsChatTransport.chunks()`), which renders the reply live and resumes it after a disconnect or reload |
| Owner request (2026-09-16) | "chuyển pooling sang redis như họ" — move the replay buffer to Redis as Onyx does, after a 3 min 51 s research turn on staging showed "Đang kết nối lại câu trả lời…" instead of progress |

No generic live-update adapter, event bus or second client store is added. MEM-26's scope bullet about invalidating TanStack Query does not apply: the transcript is owned by the AI SDK message state, and the committed reply is already reconciled from history.

## What Redis changes, and what it does not

- The replay of a running or recently finished reply lives in Redis, so it survives an API restart or deploy, any API process can serve it, and it no longer takes API heap (64 MiB `total-bytes` today).
- Execution still runs in the API process that accepted the turn. A process that dies loses its run exactly as today and as Onyx: the lease lapses (or startup fails the row) and the reply ends `FAILED` / `CHAT_INTERRUPTED`. MEM-101's "No survival across API restart" becomes "replay survives; execution does not".
- PostgreSQL stays the authority for run state and the lease ([ARCHITECTURE](../../../../ARCHITECTURE.md), "PostgreSQL as business authority"). No Redis processing fence is added.

## Reference behavior (Onyx `160f9b143`)

Paths: `backend/onyx/server/query_and_chat/stream_buffer.py`, `server/query_and_chat/chat_backend.py` (`resume_stream`), `chat/chat_processing_checker.py`, `chat/process_message.py` (`_drain_to_completion`, fence refresh), `configs/chat_configs.py`, `web/src/hooks/useChatSessionController.ts` (`resumeInFlightRun`).

| Concern | Onyx |
| --- | --- |
| Buffer | Shared cache (Redis, or Postgres on lite). Sequence-numbered zlib chunks plus a meta key `{chunk_count, done, truncated}`; writer flushes at 32 KiB or each idle tick |
| Retention | `CHAT_STREAM_BUFFER_TTL_S` 3600 s refreshed on every write; `DONE_TTL_S` 600 s after done |
| Size | `MAX_BYTES` 16 MiB compressed; over it the buffer is marked `truncated` and stops appending |
| Cache failure | Writer never propagates the error; "run continues non-resumable" (marked truncated) |
| Liveness | Fence key `chatprocessing_fence_{session}` = run ID, TTL 30 min, refreshed every 60 s by the writer |
| Resume endpoint | `GET /chat-session/{id}/resume-stream?cursor=`; any pod. Replays from the cursor, then polls the buffer every `CHAT_RESUME_POLL_INTERVAL_S` 0.2 s, heartbeat every `CHAT_HEARTBEAT_INTERVAL_S` 15 s ("below smallest proxy idle timeout (ALB 60s)"). Ends on done, on gap/expiry, or when the fence lapses (after a final drain) |
| Browser | On session load with a `current_run`, resumes from cursor 0 and refetches the session at the end. On gap or missing buffer it refetches the session. No history polling loop |

## Design

### Storage

- One Redis Stream per reply: key `memoryos:chat:stream:{assistantMessageId}`.
- Entry ID `0-{sequence}` is written explicitly (`XADD key 0-n`). The SSE cursor `assistantMessageId:sequence` maps directly to a stream ID, `XADD` refuses a duplicate or older ID, and readers detect a gap when the first entry after the cursor is not `0-(after+1)`.
- Fields: `type` and `data` (the JSON of today's `StreamBufferWriter.Event` payload). The SSE wire contract, event names, ids and OpenAPI schemas do not change.
- `PEXPIRE` 60 min (`ttl`) with every write; 10 min (`done-ttl`) once the outcome is written. Session deletion `DEL`s the key.
- Size: the writer, the only writer of a run, counts payload bytes. Past `run-bytes` (16 MiB) it writes one `truncated` entry and stops appending, as Onyx. A reader that reaches it gets `reset` `BUFFER_GAP`.
- A Redis write failure never fails the turn. Unwritten events stay queued in the writer, bounded by `run-bytes`, and are retried on the next flush tick, so a short outage keeps the sequence contiguous. Past the bound the run is marked truncated locally and stops writing, as Onyx "non-resumable".

Departures from Onyx, each for a reason:

| Departure | Reason |
| --- | --- |
| Redis Stream entries instead of `chunk:n` string keys plus a meta key | One key per reply; `XRANGE` from a stream ID replaces meta bookkeeping; the outcome entry is the done marker, so `done` cannot disagree with the content |
| No zlib compression | Payloads are small JSON chunks (≤ 16 KiB text); `run-bytes` still bounds each reply. Revisit if Redis memory shows pressure |
| Liveness from the PostgreSQL row, not a Redis fence | The lease already exists and is authoritative; a fence would be a second source of truth |
| Server-side coalescing stays at 16 KiB / 25 ms | Existing contract; Onyx's 32 KiB/idle-tick flush is a tuning value, not a behavior |

### Writer

`StreamBufferWriter` keeps its public API (`open`, `append`, `reasoning`, `research`, `tool`, `image`, `finish`, `flush`, `discard`) and its in-process coalescing under its monitor. Completed events move to a per-reply outbound queue; no Redis call runs under the monitor. The existing 25 ms maintenance flush drains each queue with `XADD`s and one `PEXPIRE` as plain commands on the shared connection (a pipeline would take a dedicated connection on every tick). `finish` publishes the outcome only after the terminal transaction commits (unchanged), writes it at once and sets the done TTL. A failed write keeps the queue; the writer reads the stream's last ID and drops entries Redis already holds, so a timed-out write that was applied is not duplicated. The local entry stays for `done-ttl` so late model callbacks are ignored, then leaves the process.

The process-wide `total-bytes`, `max-streams`, reader lag (`reader-bytes`) and slow-reader reset exist to protect the heap and go away with it. Readers no longer hold writer memory, so a slow reader cannot starve the writer.

### Reader and SSE endpoint

`ChatEventStream` keeps its shape: a bounded virtual-thread reader, prefetch 1, reader closed on cancel/error/completion, 15 s heartbeat comment, `connection-timeout`.

Each `read()`:

1. `XRANGE key (0-after +` with a count bound (`read-bytes`), repeated without sleeping while entries arrive, as Onyx drains the backlog before sleeping.
2. First entry ≠ `0-(after+1)`, or a `truncated` entry → `reset` `BUFFER_GAP`.
3. Outcome entry → done.
4. Nothing new → sleep `poll-interval` (200 ms, Onyx `CHAT_RESUME_POLL_INTERVAL_S`) and retry until the heartbeat interval passes.
5. On the first empty read and at each heartbeat after it, the reader re-checks the reply through `ChatTurnService`: the actor must still be authorized for it and the row must still be `RUNNING`. A reply that is no longer `RUNNING` gets a final drain of up to 2 s (the outcome is written after the terminal commit, on the next flush tick); if no outcome entry appears it ends with `reset` (`BUFFER_MISSING` when the key is absent, otherwise `BUFFER_GAP`), and the browser reads the committed reply from history. This is Onyx's "fence lapsed → final drain → end", driven by the lease row. It covers a dead writer, a lapsed or startup-failed run, an expired or evicted buffer, and a Redis outage longer than the writer queue.

A reader that cannot reach Redis ends at once with `reset` `BUFFER_MISSING` instead of failing the subscription, so the browser reads history and polls while the reply is RUNNING. This matters on a deploy that recreates only the API: a missing ACL user or Redis outage degrades Chat to history polling, not to endless reconnects.

Polling instead of `XREAD BLOCK`: Spring Data Redis shares one native Lettuce connection, and a blocking read on it stalls every other Redis command in the process. Polling follows the reference and needs no connection per reader. Cost: 64 readers × 5 reads/s at most.

`BUFFER_EXPIRED` stays in the contract but is no longer emitted separately: an expired key cannot be told from a missing one.

### Browser

`chunks()` changes its recovery rule:

- Keep reconnecting from the last applied cursor while the server answers. A connection that ends without an outcome, including the 65 s client cap, is reconnected with a short backoff. The "three silent connections → poll history" rule is removed: the server now ends every live stream with an outcome or a `reset`.
- Network or 5xx failures back off with bounded exponential delay (0.5 s doubling to 10 s) and keep the `recovering` state; `navigator.onLine === false` waits for the `online` event instead of retrying.
- `reset` or a sequence gap → read the reply from history once. Terminal → apply it. Still `RUNNING` (a real gap in a live reply) → poll history every 2 s, as today; this is now the only polling path.
- 401/403/404 keep today's behavior (identity refresh, transcript hidden).
- Reload mid-run is unchanged: history marks the reply `RUNNING`, the transport replays from cursor 0.

### Startup reconciliation

`failOrphanedRuns()` (owner decision 2026-09-15) stays: the deployment is still one API replica. Redis replay is what makes removing it possible later; a second replica needs the lease-only path and is out of scope.

### Deployment

- `api`: `spring-boot-starter-data-redis` in `core` (the capability that owns the buffer), `spring.data.redis` from `MEMORYOS_REDIS_*` as in the worker, TLS through the `memoryos-redis` SSL bundle on staging. The Redis health indicator is disabled for the API: Redis holds only replay, so an outage degrades resume to history and must not mark the API down. The launcher already reads `MEMORYOS_REDIS_PASSWORD_FILE` and `MEMORYOS_REDIS_TLS_CA_FILE`.
- Redis ACL: a new `memoryos-api` user limited to `~memoryos:chat:stream:*` and `+ping +hello +info +client|setname +client|setinfo +xadd +xrange +xrevrange +pexpire +del +exists`, no Pub/Sub channels. The worker user is unchanged.
- Staging secret `redis_api_password` (`/apps/memoryos/secrets/redis/api-password.txt`, created by `provision-staging-secrets.sh`), compose secret, env example, and `depends_on: redis` for the API. The host `.env.staging` needs `MEMORYOS_REDIS_API_PASSWORD_FILE` before the first deploy, and the running Redis needs the ACL user (`ACL SETUSER`, or a restart that regenerates the ACL file).
- Staging Redis has no `maxmemory`, so it runs `noeviction` and is shared with the ingestion queue. Worst case for Chat is `concurrency` (8) × 16 MiB live plus completed replies for 10 min. This is recorded, not changed here; setting `maxmemory` with `volatile-ttl` (only Chat keys carry TTLs) is an operator decision.
- Local development: the API owns one Arconia Redis dev service on fixed host port 56379. The worker does not include the Redis Dev Service module and connects to that API-owned instance, matching the existing API-first/worker-second lifecycle. Arconia 0.30 Redis does not implement cross-application Dev Service discovery, so configuring both processes as owners would race for the fixed port. Integration tests use isolated Redis Testcontainers. There is no in-memory fallback or toggle ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)).

## MEM-26 acceptance mapped to checks

| MEM-26 criterion | Check |
| --- | --- |
| Reconnect neither loses the recoverable window nor applies duplicates | Stream IDs equal sequences; reader resumes with `XRANGE (0-after`; transport ignores `sequence <= applied` and falls back on a gap. Tests: buffer resume across a writer flush, duplicate `XADD` refused, transport resume past repeated disconnects |
| Events from another tenant cannot be subscribed to, received or applied | Subscribe authorizes the actor for the reply (existing `authorizeReply`), and every heartbeat re-authorizes; keys are per reply ID, never listed. Tests: foreign actor 404 on subscribe; membership revoked mid-stream ends the stream |
| Out-of-order and duplicate delivery is deterministic and tested | Gap → `reset BUFFER_GAP`; duplicate → skipped. Buffer and transport tests |
| Snapshot/refetch reconciliation restores canonical state | `reset` → one history read; committed reply replaces live parts (existing `finish(committed…)`). Test: reset after partial replay applies history |
| Offline, expired session, denied subscription, server restart have observable UI states | Offline → `recovering` until `online`; 401 → identity refresh; 403/404 → transcript hidden; restart → stream reconnects, reply ends `FAILED` / `CHAT_INTERRUPTED` from the lease row. Transport tests plus a staging check that restarts the API during a turn |
| No live abstraction without a named producer and consumer | Only `StreamBufferWriter` (Chat turn) and `chunks()` (Chat thread) use the stream; no generic adapter |

## Not in scope

Several API replicas and removal of startup orphan reconciliation; resuming execution after a restart; compression; Redis `maxmemory` policy; other live-update consumers (ingestion status, invitations, audit); MEM-130 catalog fixes.
