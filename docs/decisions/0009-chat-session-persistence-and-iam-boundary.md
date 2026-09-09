# ADR 0009: Chat session persistence and IAM boundary

## Status

Accepted; implementation began in MEM-11 Phase 2.1 on 2026-09-09 after approval of the Onyx baseline and phase plan.

## Decision

Chat is a closed capability under `io.memoryos.chat`, depending on public IAM contracts. It owns Persona, ChatSession and the ChatMessage tree in PostgreSQL. API exposes private session creation/list/detail/history; model execution, send/Stop/SSE and the Chat UI are not delivered by this first slice.

Preserve Onyx terminology and parent/latest-child branch semantics. For the single-model path, a reserved assistant message identifies its execution (`runId = assistantMessageId`). Do not introduce a ChatRun table, durable effective-config snapshot, event journal or dedicated worker. The accepted [design](../increments/active/mem-11-production-chat/design.md) owns the broader runtime decisions.

Concrete JDBC persistence owns row mapping, foreign keys, root/branch integrity, session serialization, command uniqueness and conditional terminal writes. Application operations own authorization and transactions. A partial unique index enforces at most one RUNNING assistant message per session. This is a database consistency boundary for concurrent sends, not a queue.

IAM adds `TenantAccessResolver.lockActiveMembership` for writes that need active membership without an administrative capability. It acquires the existing shared Tenant authorization lock and revalidates membership; it neither grants Chat ownership nor exposes IAM persistence. Chat separately scopes every actor-facing session lookup by Tenant and owner. The deployment still permits exactly one Tenant.

## Consequences

Session operations and their authorization can be exercised before model execution is connected. Internal reservation/finalization is verified against PostgreSQL but is not exposed as a send endpoint that would leave orphan RUNNING rows. Native model execution will own those operations in Phase 2.2.

Chat reuses the IAM locking order used by membership revocation. Session writes serialize only the relevant session after the shared IAM check. Lock contention and query bounds are explicit costs; tests cover same-command races, terminal races and revocation ordering. Shared conversation permissions and Persona editing remain in their planned phase.
