# Group detail deferred save

## Requirement

Owner report 2026-09-21: every mutable control on a Group detail page must remain a browser-local draft until the page-level **Save Changes** action is pressed. Adding/removing members, changing manager status, changing direct capabilities, renaming the Group, and adding/removing Source associations must not issue a write request before that action. **Cancel** discards every unsaved draft.

## Design

`GroupDetailPage` remains the only commit boundary. Its member and Source sections expose a narrow draft handle to report dirty/pending state, commit their queued commands, and reset their local state. The header aggregates this state with the existing name/capability draft, enables Save Changes for any dirty section, prevents repeated saves, and resets all sections on Cancel.

The browser queues membership additions, removals, and manager-state changes locally. On save, it applies additions, manager changes, and removals through the existing guarded commands. Source associations retain their existing delta checks and guarded commands, but are invoked only from the page-level save. No new backend endpoint or authority rule is introduced.

Changes commit in authority-preserving order: Source associations, membership changes, then Group name/direct grants. If a later guarded command fails, prior confirmed commands remain durable because the existing browser API has separate command transactions; the failing section stays drafted and shows its existing safe error. The page remains in place rather than navigating away.

## Reuse

The implementation reuses the existing `Button`, `ConfirmDialog`, generated mutation clients, server-authorized permission projections, and `GroupSourcesSection` delta guard. A shared typed ref contract is the smallest gap between the page-level commit control and independent section-local draft state.
