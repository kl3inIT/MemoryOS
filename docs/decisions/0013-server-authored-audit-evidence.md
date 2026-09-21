# 0013 — Server-authored audit evidence

Status: Accepted, implementation started 2026-09-21. Supersedes [ADR 0003](0003-defer-audit-until-evidence-consumer.md).
The increment is [MEM-25](../increments/completed/mem-25-audit/design.md).

## Context

ADR 0003 removed an audit capability that had no reader, no retention policy and one real runtime path. It set six
conditions for bringing audit back: the authority changes that need evidence, actor and operator attribution, whether
successes and rejections are both recorded, transaction and rollback coupling, retention, access, redaction and export,
and the workflow that reads the evidence. Those conditions are now met.

The consumers are named and near. Tasco administrators change who may read which Source, who manages a Group, which
model provider a question leaves through and which people hold a seat; nothing records who made those changes. MEM-125
will let an administrator read other people's questions, and MEM-123 will let one cap another's spending; both are
authority over colleagues that must itself be reviewable, and both are blocked on there being somewhere to record it.
MemoryOS is also the system of record for the Tenant's own documents, so "who granted this access, and when" is a
question its operators will be asked.

Onyx (read 2026-09-21, `backend/onyx/utils/audit.py`, `docs/AUDIT_LOGGING.md`) emits one JSON line per event on the
`onyx.audit.*` loggers, shaped toward OCSF, for export to a SIEM. It has no table, no read API and no screen; its own
documentation calls an `audit_event` table and read API "planned follow-ups". Tasco has no SIEM, and MEM-25 requires a
viewer, so MemoryOS stores events and shows them, and keeps the JSON line so a SIEM can be added later without
re-instrumenting.

## Decision

1. **One append-only `audit_event` table per Tenant**, with the OCSF-shaped fields Onyx emits (action, class, outcome,
   actor, resource, request correlation, source IP, schema version) plus the display names of the actor and the
   resource captured at write time, so a renamed or deleted resource stays readable. `details` is a declared,
   per-action set of fields, not free-form JSON. A trigger rejects UPDATE and DELETE; only the retention job deletes,
   through a guard the trigger recognizes.
2. **The event is written in the operation's own transaction**, so a rolled-back change leaves no event behind.
   Operations that reach Keycloak or a provider around several short transactions record the transaction that settles
   the change.
3. **A failure to write the event never fails the operation**, as in Onyx. The write is attempted in the operation's
   transaction; if it throws, the operation proceeds, the failure is logged at ERROR and counted in a metric, and the
   event is re-emitted to the JSON logger so the evidence survives outside the database. This favours availability of
   administration over completeness of evidence; the alternative, failing the administrator's action when only its
   record failed, was rejected.
4. **Successes are always recorded. Denials are recorded only where a scoped manager exceeded their scope**, as Onyx
   does: a Group manager acting on another Group, or a Source manager on another Source. Ordinary 403s from people who
   never held the capability are not evidence of anything and would drown the stream. Failed sign-ins are recorded.
5. **Reading the audit stream needs its own capability, `AUDIT_READ`**, granted through a Group and implied by
   `SYSTEM_ADMIN`. Exporting the stream is itself an audited action.
6. **Events never carry secrets**: no API keys, tokens, credential values or document text. A changed secret is
   recorded as the fact that it changed. The actor's e-mail is recorded, because an investigation needs it.
7. **Retention is 365 days by default**, configurable, swept by one Worker task.

## Consequences

Every administrative service now depends on the audit writer, and a new administrative surface is expected to record
its own events; the action catalog is append-only, as in Onyx, so a recorded action never changes meaning. Because a
write failure is swallowed, the stream is evidence of what was recorded, not proof that nothing else happened: gaps are
detectable only through the ERROR log and the failure metric, and an operator investigating a gap must consult both.
`core` gains no new module — audit belongs to `iam`, which owns authority — and the JSON line keeps a SIEM one log
shipper away.
