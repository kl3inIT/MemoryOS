# Plan

- [x] Inspect startup ACL, live deployment mounts and Redis documentation.
- [x] Broaden staging inspection: 29 allowed and 15 denied ACL DRYRUN cases passed; real EXISTS/SCAN/XINFO/XPENDING succeeded.
- [x] Applied live with ACL SETUSER and ACL SAVE without Redis/worker restart; synchronized the existing mounted startup script. Prior script retained at /apps/memoryos/start-staging-redis.before-inspection.sh.
- [x] Updated runbook and prepared runtime handoff. Redis Insight browser refresh remains user verification; no authenticated UI session was exercised by the agent.

Before change, the same verifier failed on EXISTS. After change, ingestion group pending=0 and lag=0. Verification performs no data writes: mutation checks use ACL DRYRUN only. Runtime/startup policy is already applied on staging.

2026-09-06 pre-commit verification: reran all 29 allowed and 15 denied ACL DRYRUN cases plus actual inspector reads against the existing staging Redis container. All passed; ingestion pending=0 and lag=0. Shell syntax checks passed in a Redis Alpine container after normalizing Windows checkout line endings. No Redis/worker restart, data mutation or browser verification was performed in this pass. User requested direct main commit/push, separate from the Google Drive planning documents.
