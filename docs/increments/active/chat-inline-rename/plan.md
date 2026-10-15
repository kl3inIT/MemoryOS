# Plan

- [x] Replace the rename buttons with Enter and blur saving in `ChatSessionRenameForm`; add the duplicate-write guard, IME guard, `readOnly` pending state and focus rule.
- [x] Remove the unused icon imports and catalog entries.
- [x] Unit-test the row (`chat-session-row.test.tsx`) and update the existing workspace e2e rename step to press Enter.
- [x] Add the e2e scenario "saves a conversation name on blur without action buttons or stealing focus".
- [x] Run the Playwright scenarios and the web gate (see `verification.md`).
- [ ] Consolidate the behavior into `docs/specs/chat.md`, then move this increment to `completed/` after its pull request merges.
