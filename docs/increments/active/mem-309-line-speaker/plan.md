# Plan: MEM-309, say who spoke one line

- [x] Migration: `meeting_utterance.diarized_speaker`.
- [x] Core: `MeetingService.setLineSpeaker`, repository move and manual voice.
- [x] API: `PUT /api/meetings/{id}/utterances/{utteranceId}/speaker`; OpenAPI and the web client regenerated.
- [x] Web: the picker on a line's speaker, with the two scopes.
- [x] Tests: API integration for the move, the cross-track name and the refusals; web unit and e2e; screenshots.
- [x] The panel lists each person once and drops a voice with no line left (found in the screenshot review).
- [x] Spec and test matrix.
- [x] The owner approved the screenshots; merged in #891 (`1b3fe546`); deployed to staging, where the endpoint refused an empty body (400) and an unknown line (404) and changed nothing.
- [ ] The owner corrects the 2026-11-04 meeting with it; then move this increment to `completed/`.

## Log

- 2026-11-05: opened after the staging meeting of 2026-11-04 merged two people into one voice; the owner chose
  Nojoin's picker with "this line" as the default.
