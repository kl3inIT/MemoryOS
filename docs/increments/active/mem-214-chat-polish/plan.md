# Plan — MEM-214 Chat polish

- [x] `IconButton` `tooltip` prop with its own provider
- [x] Chat icon actions use `tooltip` instead of `title`
- [x] `ChatAnswerMenu` (other model, branch) replaces the chevron and the branch icon on answers; `useChatBranch`
- [x] Temporary chat toggle with a visible label, one accessible name, `aria-pressed`
- [x] Account button shows the display name, role as fallback
- [x] Phone header: Share and Files move into the conversation menu; `FormDialog` `restoreFocusRef` returns focus to it
- [x] Unit and e2e updates; `tsc -b`, lint, i18n, format; unit 356 tests; e2e Chat, read-aloud and shell specs (85, the sharing spec updated for the phone menu)
- [ ] Browser evidence: an answer at 1440×900 and 390×844, a conversation header on a phone, the new-chat header; owner approval before merge
- [x] Move `mem-213-copy-terms` to `completed/` and reconcile the roadmap
