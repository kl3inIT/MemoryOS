# Audit evidence

The Tenant's audit stream records who changed sign-in, users, Groups, models, connections and Sources, when, and
from where. It is decided in [ADR 0013](../decisions/0013-server-authored-audit-evidence.md) and delivered by
[MEM-25](../increments/completed/mem-25-audit/design.md). The code is the `audit` application module, `core/src/main/java/io/memoryos/audit`.

## Module

- **Published API.** The package root: `AuditTrail`, `AuditRecord`, `AuditAction`, `AuditEventClass`, `AuditOutcome`,
  `AuditRequestContext`, `AuditLog`, `AuditRetention`, `AuditException` and the `AuditReaders` port.
- **Persistence.** `audit.persistence` holds the SQL: `JdbcAuditEventRepository` (insert, savepoint, actor profile
  lookup, retention delete) and `JdbcAuditLogQueryRepository` (the filtered, keyset-paged read).
- **No dependency on IAM.** IAM records its own changes here, so audit depends on no capability, only on the `shared`
  kernel. Records carry the Tenant and actor as `TenantId` and `ActorId`, and `AuditLog` asks who may read through
  `AuditReaders`, which IAM implements
  (`IamAuditReaders`, `AUDIT_READ`, never scoped). The actor's name and e-mail are still read from `actor_profiles`
  in the recording transaction.

## Stream

`audit_event` (V95) holds one row per event and Tenant:

- **When:** `occurred_at`.
- **What:** `action`, `event_class`, `outcome`.
- **Who:** `actor_id`, and the actor's name and e-mail as they were when the event was recorded.
- **To what:** the resource type, id and name, again as they were at the time.
- **Details:** `details`, the fields this action declares.
- **Where from:** `trace_id`, `endpoint` (`METHOD /path`), `source_ip` (resolved by the forwarded-header filter), and
  `schema_version`.

Rules on the table:

- **Append-only.** A trigger refuses every UPDATE, and refuses a DELETE unless the transaction has set
  `memoryos.audit_retention` (only the retention sweep does). A second trigger refuses TRUNCATE, which fires no row
  trigger.
- **No foreign key on `actor_id`.** An event can never be deleted, so a foreign key would stop anyone from ever
  deleting that person.

### Actions

`AuditAction` is the catalog. It is append-only, like Onyx's `AuditAction`: an action value never changes meaning.

- **Class.** Each action carries its OCSF class: `AUTHENTICATION` (3002), `ACCOUNT_CHANGE` (3001),
  `USER_ACCESS_MANAGEMENT` (3005), `GROUP_MANAGEMENT` (3006) or `API_ACTIVITY` (6003).
- **Declared details.** Each action also declares the detail fields it may carry. A call site that sets any other
  field fails, so a secret cannot reach the record by mistake.
- **Secrets.** Never stored as values. A changed key is recorded as `credentialChange: KEEP | REPLACE | REMOVE`.

| Class | Actions |
| --- | --- |
| Authentication | `auth.login`, `auth.login_failure`, `auth.logout`, `auth.jit_admit` |
| Account change | `user.invite`, `user.invite_rotate`, `user.invite_revoke`, `user.join`, `user.deactivate`, `user.reactivate` |
| User access management | `user.group_change` |
| Group management | `user_group.create`, `rename`, `delete`, `member_change`, `manager_change`, `permission_change` |
| API activity | `llm_provider.create`, `update`, `delete`; `model.create`, `update`, `delete`; `model_default.change`; `model_flow.change`; `web_connection.change`; `voice_connection.change`; `image_connection.change`; `interpreter.change`; `chat_settings.change`; `chat_guardrail.block` (MEM-195: a question stopped by a sensitive topic or blocked phrase; the rule kind, topic and session, never the question or phrase); `mcp_server.create`, `update`, `delete`; `mcp_tool.change`; `mcp_oauth_client.change`; `mcp_connection.change`; `identity_provider.create`, `update`, `delete`; `source.create`, `update`, `delete`, `access_change`, `manager_change`, `group_change`, `pause`, `resume`, `item_remove`; `credential.create`, `update`, `delete`; `chat_history.read`; `chat_history.export`; `audit.export`; `permission.denied` |

## Recording

`AuditTrail.record` is called by the service that makes the change, inside that change's transaction, after the
change is made.

- **Rollback.** An operation that rolls back leaves no event.
- **Failed writes.** The insert runs in a savepoint. If it fails, it is rolled back to the savepoint, logged at ERROR
  and counted in `memoryos.audit.write.failures`, and the operation still commits, as in Onyx.
- **SIEM line.** After recording, the event is written again as one JSON line on `memoryos.audit.<class>` for a SIEM.

`AuditTrail.recordSeparately` covers events that must survive their caller's rollback or have no transaction of
their own:

- a refusal;
- a sign-in, whether it succeeds or fails;
- a sign-out;
- a change settled in Keycloak (identity providers).

Inside a transaction, the event is written once that transaction completes. A refusal is raised while the Tenant row
is locked `FOR UPDATE`, and an insert in a second transaction would wait on that lock for its foreign key.

Coverage:

- **Recorded:** every administrative configuration change.
- **Also recorded:** an administrator reading or exporting somebody else's conversations
  ([query history](chat.md#query-history-for-administrators)); listing them is not, since the list shows no message
  body beyond the first question and answer.
- **Not recorded:** a member's own actions (their MCP connection, their Chat preferences) and work that runs often or
  by itself (a Source sync, an MCP tool refresh).
- **Drive and SharePoint Sources:** they are created and re-scoped by a request the Worker validates. The event
  records the request, and its resource is the Source id that request creates.
- **Refusals:** recorded only when a scoped manager reaches past their scope, as in Onyx: a Group manager acting on
  another Group, or a Source manager acting on another Source. A probe that decides which actions to offer
  (`SourceAccessPolicy.canManage`) records nothing.
- **Failed provider exchanges:** an OIDC exchange that fails at the provider carries no identity and is not
  recorded.

## Reading

Every read requires `AUDIT_READ`. It is an ordinary grant given through a Group, and administrator access implies it.

| Method and path | Contract |
| --- | --- |
| `GET /api/audit/events` | A page of `AuditEvent`, newest first, keyed by an opaque `(occurred_at, id)` cursor. Optional filters: `from`, `to` (at most 366 days apart), `q` (actor name or e-mail, resource name; `%` and `_` are literal), `eventClass`, `action`, `outcome`, `actorId`, `resourceType`, `resourceId`. `size` is 1–100, default 50 |
| `GET /api/audit/events/{eventId}` | One event of the Tenant |
| `GET /api/audit/catalog` | Every action value with its class |
| `GET /api/audit/export` | The filtered events as CSV, at most 50,000 rows, with a UTF-8 byte-order mark and formula prefixes neutralized. The export is itself recorded as `audit.export` with its row count, outside the read, so an export that breaks
  halfway is still recorded, with the rows it had written and the outcome `FAILURE` |

## Retention

The Worker task `memoryos-audit-retention-v1` runs hourly and deletes events older than `memoryos.audit.retention`
(default `P365D`, at least one day), in batches of 5,000.

## Viewer

Admin › Monitoring › Audit log (`/admin/audit`) has:

- a row of filters: period, category, outcome and search;
- the shared table and pager used by Users;
- a readable sentence for each action;
- a When / Who / What panel with a field-by-field before-and-after table;
- an export link that carries the filters on screen.
