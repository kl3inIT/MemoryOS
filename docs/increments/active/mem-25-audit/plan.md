# Implementation plan

One pull request on `kl3inIT/mem-25-audit`, one commit per step.

- [x] 1. Decision: [ADR 0013](../../decisions/0013-server-authored-audit-evidence.md) supersedes ADR 0003; mark ADR 0003
      superseded and reconcile the roadmap entry.
- [x] 2. Foundation: `V94__audit_event.sql` (table, filter indexes, append-only trigger with a retention guard);
      `AuditAction` with its OCSF class, `AuditRecord`, and the declared `details` per action.
- [x] 3. Writer: one `iam` service that writes in the caller's transaction, swallows its own failure into an ERROR log
      and a counter, and re-emits the event as one JSON line on `memoryos.audit.<class>`. Actor, Tenant, trace id,
      endpoint and client IP come from the request context.
- [x] 4. IAM call sites: sign-in success and failure, logout, JIT admission, invitation issue, rotate, revoke and
      accept, member deactivate and reactivate, a person's Groups replaced, Group create, rename, delete, members,
      managers and capabilities. Denial events at the Group scope check for a manager outside their scope.
- [x] 5. Configuration and Source call sites: providers (data boundary before and after, key changed as a fact),
      models, defaults and flows, Web, voice and image connections, interpreter, MCP servers and OAuth clients,
      identity providers; Source create, delete, access, manager, Groups, pause and resume; Google Drive and
      SharePoint credentials.
- [x] 6. Read: `AUDIT_READ` (implied by `SYSTEM_ADMIN`), the reader with cursor pagination and filters, three
      endpoints, `audit.export` recorded on export, OpenAPI regenerated, `pnpm generate:api`.
- [x] 7. Web: Monitoring › Audit log — one filter row (period, actor, action, outcome), a table of time, actor,
      action sentence with its code, resource, outcome and IP, a When / Who / What detail panel with a before-and-after
      table, CSV export of the current filters, and a line stating retention and who may read it. Vietnamese copy;
      action sentences translated, codes left as data.
- [x] 8. Worker: the retention task (365 days by default, configurable).
- [ ] 9. Tests: transaction coupling and writer failure, catalog completeness, denial scope, the no-secret sweep,
      Tenant isolation and authorization, cursor stability, retention and the append-only trigger, vitest for the
      screen; screenshots at 1280 light/dark and 390.
- [ ] 10. Docs: a new `docs/specs/audit.md` and `docs/tests/audit.md`, ARCHITECTURE and the roadmap; `pnpm check` and
      `./gradlew clean check`.
- [ ] 11. Follow-up issue: ending a deactivated member's open sessions.
