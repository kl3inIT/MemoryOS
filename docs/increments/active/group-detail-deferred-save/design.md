# Group detail deferred save

## Requirement

Owner report 2026-09-21: every mutable control on a Group detail page must remain a browser-local draft until the page-level **Save Changes** action is pressed. Adding/removing members, changing manager status, changing direct capabilities, renaming the Group, and adding/removing Source associations must not issue a write request before that action. **Cancel** discards every unsaved draft.

## Design

`GroupDetailPage` remains the only commit boundary. It owns the member and Source drafts through the page-level hooks `useGroupMembersDraft` and `useGroupSourcesDraft`, which store only the person's changes over the server data; the sections render from them. The header aggregates this state with the existing name/capability draft, enables Save Changes for any dirty section, prevents repeated saves, and resets all sections on Cancel.

The browser queues membership additions, removals, and manager-state changes locally. On save, it applies additions, manager changes, and removals through the existing guarded commands. Source associations retain their existing delta checks and guarded commands, but are invoked only from the page-level save. No new backend endpoint or authority rule is introduced.

Changes commit in authority-preserving order: Source associations, membership changes, then Group name/direct grants. If a later guarded command fails, prior confirmed commands remain durable because the existing browser API has separate command transactions; the failing section stays drafted and shows its existing safe error. The page remains in place rather than navigating away.

## Reuse

The implementation reuses the existing `Button`, `ConfirmDialog`, generated mutation clients, server-authorized permission projections, and `GroupSourcesSection` delta guard. Drafts live at the page, so no imperative handle is needed between the commit control and the sections (phase 5, 2026-09-26).
