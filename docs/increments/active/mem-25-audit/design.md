# MEM-25 — Server-authored audit evidence and viewer

## Problem

Nobody can answer "who changed this, and when" about MemoryOS itself. An administrator can grant a Group access to the
Tenant's contracts, appoint a Source manager, relabel a provider from Internal to External, deactivate a colleague or
replace a provider's API key, and the system keeps only the resulting state. The invitation table records who invited
and who revoked; everything else records nothing.

Two increments are waiting on this. MEM-125 lets an administrator read other people's questions, and MEM-123 lets one
cap another's spending. Both are authority over colleagues, and neither should ship without leaving a trace.

## Reference

**Onyx**, read on 2026-09-21 (`backend/onyx/utils/audit.py`, `docs/AUDIT_LOGGING.md`):

- One JSON line per event on `onyx.audit.<ocsf_class>`, for export to a SIEM. Fields: `audit_schema_version` ("1.0"),
  `ts`, `action`, `ocsf_class`, `outcome` (success / failure / denied), `tenant_id`, `actor`
  (`user_id`, `email`, `api_key_id`, `auth_type`), `resource_type`, `resource_id`, `request_id`, `endpoint`,
  `source_ip`, `extra`.
- Five OCSF classes: authentication (3002), account_change (3001), user_access_management (3005),
  group_management (3006), api_activity (6003). An action with no class fails module import, so the catalog cannot
  drift.
- Action values are an append-only contract. Roughly 35 actions: `auth.login`, `auth.login_failure`,
  `auth.impersonate`, `user.create|delete|deactivate|reactivate`, `user.role_change`, `user.group_change`,
  `user_group.create|rename|delete|permission_change|manager_change`, `llm_provider.create|update|delete`,
  `connector.*`, `cc_pair.*`, `credential.*`, `api_key.*`, `settings.security_settings_change`,
  `permission.denied`.
- Most emitters record success only, after the change. Failure is used by sign-in and impersonation. Denials come only
  from the scoped-manager gates (`backend/onyx/auth/scoped_permissions.py:39`), not from ordinary route 403s.
- Before and after values are rare: `user.role_change` carries `previous_is_admin`, `user_group.rename` carries the
  old and new name, and `settings.security_settings_change` carries a `{field: {old, new}}` diff.
- Emission is wrapped in `try/except: return` and never raises into the caller. High-volume actions can deduplicate
  through Redis; no call site currently does.
- Secrets are never logged: an API key is recorded by id. This is a rule for callers, not something the code enforces.
- No table, no read API, no screen. The documentation lists them as planned follow-ups.

**Product references** (Mobbin, 2026-09-21): [1Password Activity Log](https://mobbin.com/screens/a5632ec1-f072-43e2-8f2b-ad047df4ca04),
[Okta System Log](https://mobbin.com/screens/23e31bef-763c-499b-b527-125ea00ff46e),
[Vanta Event log](https://mobbin.com/screens/41659ab7-5710-4abf-91ca-c6bf482c9b0b),
[PlanetScale Audit log](https://mobbin.com/screens/350460dd-4eba-4197-9f8e-f18d0e96cfef),
[Dropbox Security activity](https://mobbin.com/screens/99f74382-4244-40e8-bc02-a1c8664b381a),
[Railway event detail](https://mobbin.com/screens/3668d9f6-d6bb-4fdf-a4a8-97087947c044),
[Employment Hero before/after](https://mobbin.com/screens/209d9645-497c-40f7-8b75-6b4fd44475dd),
[Reddit Mod Log](https://mobbin.com/screens/6e059c32-b17e-486f-a27b-c3cae3a8d775).
They agree on: one row per event with time, actor (name and e-mail), a readable sentence for the action beside its
machine code, the target and the source IP; one row of filters (period, actor, action, target, outcome); a detail
panel organized as When / Who / What, with a before-and-after table for a changed setting; an export of exactly what
the filters select; and a line under the title stating how long the log is kept and who may read it.

## Decision

Recorded in [ADR 0013](../../decisions/0013-server-authored-audit-evidence.md), which supersedes ADR 0003:
an append-only table per Tenant, written in the operation's transaction, never failing the operation; successes
always, denials only for a scoped manager outside their scope; `AUDIT_READ`; no secrets; 365-day retention.

Three consequences worth stating here:

- **Audit belongs to `iam`**, which already owns authority and is the one module every other module may depend on.
  No new capability module, and no dependency inversion: `chat`, `connector` and `mcp` record events through an `iam`
  service they already reach.
- **`details` is declared per action**, unlike Onyx's free-form `extra`. Each action names its fields in code, so a
  reader knows what a row can contain and a secret cannot be added by accident at a call site.
- **The catalog is append-only and enforced at startup**, as in Onyx: an action without a class fails the context.

## Catalog (v1)

| Class | Actions |
| --- | --- |
| Authentication | `auth.login`, `auth.login_failure` (not admitted, invitation refused, provider failure), `auth.logout`, `auth.jit_admit` |
| Account change | `user.invite`, `user.invite_rotate`, `user.invite_revoke`, `user.join`, `user.deactivate`, `user.reactivate` |
| User access management | `user.group_change` (a person's Groups replaced) |
| Group management | `user_group.create`, `user_group.rename`, `user_group.delete`, `user_group.member_change`, `user_group.manager_change`, `user_group.permission_change` |
| API activity | `llm_provider.create|update|delete` (with the data boundary before and after, and whether the key changed), `model.create|update|delete`, `model_default.change`, `model_flow.change`, `web_connection.change`, `voice_connection.change`, `image_connection.change`, `interpreter.change`, `mcp_server.create|update|delete`, `mcp_oauth_client.change`, `identity_provider.create|update|delete`, `source.create|delete`, `source.access_change`, `source.manager_change`, `source.group_change`, `source.pause|resume`, `credential.create|delete` (Google Drive, SharePoint), `audit.export`, `permission.denied` |

`auth.logout` has no Onyx equivalent; MemoryOS records it because a session ending at Keycloak is part of the same
story as the session starting. MEM-123 and MEM-125 add their own actions to this catalog.

## Scope

- `V92__audit_event.sql`: the table, its indexes for the viewer's filters, and the trigger that makes it append-only.
- `iam`: the event record, the action catalog with its classes, the writer, the startup check, and the reader with
  cursor pagination; `AUDIT_READ` in `IamCapability`, implied by `SYSTEM_ADMIN`.
- Call sites across `iam`, `connector`, `chat` and `mcp`, at the service boundary, never in a controller.
- `DefaultIamAuthorization`: a denial event where a scoped manager exceeds their scope.
- API: `GET /api/audit/events` (filters, cursor), `GET /api/audit/events/{id}`, `GET /api/audit/export` (CSV).
- Worker: one recurring retention task.
- Web: Monitoring › Audit log, built from the existing `SettingsLayout`, `PageHeader`, `EmptyState` and table
  composites.

## Out of scope

- Shipping events to a SIEM. The JSON line makes that a log-shipper configuration, as in Onyx.
- Recording reads of ordinary resources; only the audit export is recorded as a read.
- Tamper evidence beyond append-only (hash chaining, signing).
- Onyx's `auth.impersonate`, `api_key.*` and `user.role_change`: MemoryOS has no impersonation, no API keys and no
  role-change endpoint yet.
- Ending a deactivated member's open sessions. Found while surveying this increment and filed separately; a
  deactivated member is refused per request, but their session rows are not deleted.

## Verification

- Rolling back an administrative transaction leaves no event.
- A writer failure leaves the operation committed, the ERROR logged and the metric raised.
- Every action in the catalog has a class, checked at startup; a fixture asserts the catalog has not lost an action.
- A scoped manager outside their scope produces one denial event; a member without the capability produces none.
- No event carries a secret: a test drives the provider and credential paths with recognizable secret values and
  asserts they appear in no column of the table.
- Events stay inside their Tenant; a member without `AUDIT_READ` is refused on read and export.
- Cursor pagination is stable while new events arrive.
- The retention job deletes only expired rows, and the append-only trigger refuses every other UPDATE and DELETE.
- Screens reviewed at 1280 light and dark and at 390, with realistic Vietnamese fixtures.
