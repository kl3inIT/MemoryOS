# Member group read view

## Decision

Every active tenant member can open **Groups** and see only groups in which they hold a direct membership. They can inspect the group name, member list, and—on ordinary groups—the associated source summaries. No member-level mutation capability is added.

Existing global `GROUPS_READ` retains tenant-wide visibility. Existing ordinary-group managers retain their managed-group visibility. The projection additionally admits direct memberships; it never admits another group.

`listGroupSources` admits a direct group member to summaries associated with that same group. It does not admit the source detail route, source catalogue, item lists, or mutations. Source summaries remain read-only and are not links unless the user already has source-read authority.

The Groups administration entry is visible to every authenticated active member. The existing group UI reuses its permission map: read-only members get no create, rename, member, grant, source-association, delete, Save, or Cancel controls.

## Risks

The protected Basic group may be visible to its members and therefore exposes its membership list. This follows the requested direct-group membership rule; no cross-group membership is exposed.
