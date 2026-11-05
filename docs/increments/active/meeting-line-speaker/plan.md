# Plan: say who spoke one line

- [x] Migration: `meeting_utterance.diarized_speaker`.
- [x] Core: `MeetingService.setLineSpeaker`, repository move and manual voice.
- [x] API: `PUT /api/meetings/{id}/utterances/{utteranceId}/speaker`; OpenAPI and the web client regenerated.
- [x] Web: the picker on a line's speaker, with the two scopes.
- [x] Tests: API integration for the move, the cross-track name and the refusals; web unit and e2e; screenshots.
- [x] The panel lists each person once and drops a voice with no line left (found in the screenshot review).
- [x] Spec and test matrix.
- [ ] The owner approves the screenshots; merge; deploy to staging.

## Log

- 2026-11-05: opened after the staging meeting of 2026-11-04 merged two people into one voice; the owner chose
  Nojoin's picker with "this line" as the default.
