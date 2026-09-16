# Implementation plan

Branch `kl3inIT/chat-stream-redis`. Design: [design.md](design.md).

- [x] Read Onyx `160f9b143` stream buffer, resume endpoint, processing fence and browser resume; read the MemoryOS buffer, SSE endpoint, lease lifecycle, browser transport and staging Redis setup (2026-09-17).
- [x] Redis buffer in `core` (2026-09-17): `StreamBufferWriter` keeps its API, writes `0-{sequence}` entries through an outbound queue drained by the flush tick, refresh/done TTL, `truncated` past `run-bytes`, bounded retry on Redis failure, `discard` deletes. Remove heap-only limits (`total-bytes`, `max-streams`, `reader-bytes`) and add `poll-interval`.
- [x] Reader: `XRANGE` drain, gap and truncation reset, 200 ms poll, heartbeat re-authorization and RUNNING check with a final drain, as Onyx's fence lapse.
- [x] `ChatTurnService.subscribe` supplies the per-heartbeat check; startup orphan reconciliation unchanged.
- [x] API configuration: `spring.data.redis` from `MEMORYOS_REDIS_*`, staging SSL bundle, Redis health indicator off for the API, Arconia Redis dev services shared with the worker, Redis Testcontainer in tests.
- [x] Browser transport: reconnect from the cursor until outcome or reset, bounded backoff, offline wait, history read on reset, polling only for a gap in a live reply.
- [x] Tests: buffer against real Redis (resume, gap, duplicate, truncation, TTL, paused-Redis retry, discard, liveness and revoked reader); `ChatEventStreamTest` on Redis; `ChatSessionApiIntegrationTest` SSE through Redis, foreign actor, deleted replay resets; transport tests for the new recovery rules. (2026-09-17: `StreamBufferWriterTest` 12, `ChatTurnServiceTest` 9, `ChatEventStreamTest` 8, `ChatSessionApiIntegrationTest` 49 incl. the new test, `chat-transport.test.ts` 40, `tsc -b` pass locally.)
- [x] Deployment: `memoryos-api` ACL user, `redis_api_password` secret in compose and `staging.env.example`, API Redis env and `depends_on`; runbook note.
- [ ] Staging host (manual, before the deploy that needs it): create `/apps/memoryos/secrets/redis/api-password.txt` (mode 0600, as `provision-staging-secrets.sh`), add `MEMORYOS_REDIS_API_PASSWORD_FILE` to `/apps/memoryos/.env.staging`, and add the `memoryos-api` user to the running Redis with `ACL SETUSER` (the next Redis start regenerates it from the script). Without the env line the deploy fails at `compose config`.
- [x] ADR 0012 for Chat replay in Redis; consolidate `docs/specs/chat.md` (Streaming and reconnect, Browser integration), `docs/tests/chat.md`, `ARCHITECTURE.md` (Chat runtime, state table), MEM-101 design (restart line, stream replay departure, not-in-scope), `AGENTS.md`, roadmap.
- [ ] `clean check` (CI is the gate if the host runs out of memory), push, PR, CI green, merge.
- [ ] Staging acceptance: a Deep research turn streams progress to the end without "Đang kết nối lại"; reload mid-run replays from Redis; restart the API mid-turn and observe the reply end `CHAT_INTERRUPTED` without a hanging stream; Redis keys expire 10 min after completion. Then run MEM-101's reload-mid-run check from its [staging acceptance](../mem-101-deep-research/staging-acceptance.md).
- [x] Linear: MEM-26 In Progress, related to MEM-101, comment with the increment link and criteria mapping.
