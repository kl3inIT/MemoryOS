# Plan

- [x] Add *Hội thoại mới* to the sidebar Project menu; it navigates to the Project page and creates no session.
- [x] Let `useRefreshChatSessions` take the returned session and synchronize the cache entry and the mounted controller; use it for the Project row's move and the conversation menu's move.
- [x] Continue focus at the retained history link when removing a conversation from a Project row drops it to the page.
- [x] Unit tests for the menu item (navigation, no create request) and the existing move paths; browser scenarios for removal retention, the new-conversation item and the first-send removal.
- [x] Run the browser scenarios and the web gate; record the evidence in `verification.md`.
- [x] Consolidate into `docs/specs/chat.md`; keep this increment active until its pull request merges, then move it to `completed/`.

## Staging acceptance (2026-10-19)

The behaviour is consolidated into `docs/specs/chat.md` (Projects).
