# 0012 — Chat reply replay in Redis

Status: Accepted, implementation started 2026-09-17. Supersedes the in-memory replay buffer described for Chat in [ARCHITECTURE](../../ARCHITECTURE.md) ("A bounded in-memory buffer supports live SSE and short replay") and MEM-101's stream replay accounting decision.

## Context

Chat replay lived in API heap. A Deep research turn runs for minutes; on staging (2026-09-16) the browser showed "Đang kết nối lại câu trả lời…" for most of a 3 min 51 s turn because its recovery gave up after silent connections and polled history, which shows no progress. The owner asked to move the buffer to Redis as Onyx `160f9b143` does (`stream_buffer.py`, `resume_stream`). MEM-26 had waited for a named producer and consumer; this is that use case. The increment is [MEM-26](../increments/completed/mem-26-chat-stream-redis/design.md).

## Decision

- Each reply's events live in one Redis Stream `memoryos:chat:stream:{assistantMessageId}` with entry IDs equal to sequences, a TTL refreshed by each write (60 min) and 10 min after the outcome, and a `truncated` marker past 16 MiB, as Onyx.
- Only the API process running a turn writes its reply, from the flush tick, outside the chunking monitor; any API process reads it. A Redis failure never fails a turn.
- Readers poll Redis (200 ms) rather than blocking. Liveness and authorization come from PostgreSQL: at each heartbeat a reader re-authorizes and checks that the reply is RUNNING, and a reply that ended without an outcome entry resets the browser to history. No Redis processing fence is added; PostgreSQL remains the run authority.
- The browser resumes from its cursor until the outcome and polls history only after a reset or a sequence gap.
- The API gets its own ACL user limited to Chat replay keys. There is no in-memory fallback.

## Consequences

The API now depends on Redis for live replay; staging Redis is shared with the ingestion queue and has no `maxmemory`. Replay survives API restarts and deploys and does not use heap, but execution still dies with its process, and startup orphan reconciliation still assumes one API replica. Several replicas remain a separate decision.
