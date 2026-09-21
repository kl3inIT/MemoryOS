# Source manager owns Group attachment

Owner report 2026-09-15: on Group detail a Group manager removed a Source chip, pressed **Save associations** and got only "Không hoàn tất được thay đổi nhóm. Hãy thử lại." Both Sources were restricted, so the block came from the Source being associated with a second Group the actor does not manage.

Owner decision 2026-09-16, after reviewing the alternatives: attachment authority follows the Source's manager, not the set of Groups it happens to carry.

Owner decision 2026-09-21: on a Group with two managers, the one who did not create a Source could detach it and then could not attach it again. Detaching now follows the Source's manager too, and another manager of the Group has no authority over that Source ([ADR 0013](../../decisions/0013-only-the-source-manager-detaches-sources.md)).

Owner report 2026-09-21: an Actor with scoped `SOURCES_READ` from managing one ordinary Group opened another Group and the browser still mounted its Source-association section. `GET /api/groups/{groupId}/sources` correctly rejected the out-of-scope Group, but the UI rendered a misleading “could not load” error.

## Cause

Scoped Source authority required a non-public Source whose associated ordinary Groups are **all** managed by the actor. A Source shared with another manager's Group therefore had `permissions.edit = false` for both managers, and only global authority could touch it. Group detail still rendered the remove control, then failed client-side before any request, and the generic retry copy hid the reason.

## Accepted model

One Source records a **manager**: the group manager who created it, or the one an administrator appoints. See [ADR 0011](../../decisions/0011-source-manager-group-attachment-authority.md).

| Operation | Recorded manager (still an active Group manager) | Manager of an associated Group | Global `SOURCES_MANAGE` |
| --- | --- | --- | --- |
| Rename, upload, reindex, Drive schedule/pause/sync, Drive roots | yes, while the Source is not public | no | yes |
| Attach or detach the Groups they manage (`POST /api/sources/{id}/groups`) | yes; Groups they do not manage must stay exactly as they are | no | yes, any ordinary Group, including none |
| Detach the Source from one Group (`POST /api/groups/{groupId}/sources/{sourceId}/remove`) | yes, from a Group they manage, while the Source is not public | no (changed 2026-09-21, ADR 0013) | yes |
| Appoint or clear the manager (`POST /api/sources/{id}/manager`) | no | no | `SYSTEM_ADMIN` only |
| Change PUBLIC/RESTRICTED, delete the Source, remove items | no | no | yes (plus the existing groupless-creator carve-out) |

Consequences of the model:

- **Creation no longer requires a Group.** Scoped creation stays RESTRICTED and records its creator as the manager; it may start with no association. Global creation records no manager, so an administrator-created Source stays administrator-only until one is appointed.
- **Catalog visibility follows the manager too**, so a manager still sees a Source after it moves to another manager's Group. Reading its *documents* is unchanged: restricted content needs membership in an associated Group, and neither administration nor the manager role bypasses that.
- **A restricted Source with no Group reaches nobody.** Group detail and the Source's Groups section say so rather than letting it look indexed and usable.
- **Losing the manager role withdraws authority** without touching associations: `ACTIVE_MANAGER` still requires an active standard member who manages at least one ordinary Group. Administrators then appoint someone else.
- **Sources created before V47** carry no creator, so they have no manager and stay administrator-only until appointed.

## Implementation

- `V63__source_manager_group_authority.sql` adds `connector_credential_pairs.manager_actor_id`, backfills it from `created_by_actor_id` and bumps `authorization_version`.
- `SourceScopeSql.WRITE` becomes "active Group manager, not public, and the recorded manager"; `READ` gains the recorded manager for catalog rows only.
- `DefaultSourceManagementService.replaceSourceGroups` authorizes the **delta**: every added and every removed Group must be managed by the actor, so another manager's association survives the replacement untouched.
- `removeGroupSource` requires a Group the actor manages and then locks the Source through `lockAuthorized`, the same scoped write rule as `replaceSourceGroups`; `removableFromGroup` filters by that rule unless the caller has global access. Group detail shows the other Sources locked and names their responsible manager.
- `assignSourceManager` takes the exclusive IAM lock, rejects a candidate who manages no ordinary Group (`SOURCE_MANAGER_NOT_ELIGIBLE`) and returns the refreshed summary.
- `SourceSummary` carries `managerActorId` and `managerName`, so Source detail names the manager without reading the user directory. Administrators open the member list only to change it. Eligibility is not guessed in the browser — the server rejects an ineligible candidate.
- `GroupSourcesSection` mounts the association read only for global `SOURCES_READ` or when the target Group's `manageSources` projection authorizes that target. A scoped Source capability from another Group never issues an out-of-scope request or renders a false load failure.

## Not in this increment

- **Google Drive credentials still belong to the actor who authorized them.** Appointing a new manager does not move the Drive grant, so a Source whose original manager left keeps syncing under the old grant until someone reconnects. Pausing the sync on a manager change is a separate change.
- Item removal and Source deletion keep their current global-only rule.
- No administrator report of Sources whose manager lost their role; the Source detail page is the only place this is visible.
