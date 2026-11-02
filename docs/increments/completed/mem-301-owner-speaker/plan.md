# MEM-301: an action's owner points at a speaker of the meeting — plan

Issue: [MEM-301](https://linear.app/memory-os/issue/MEM-301). See the [design](design.md).

## Steps

- [x] Migration: `meeting_minutes_item.owner_track`, `owner_label`, referencing `meeting_speaker`; a linked owner has no text and only an action has one; stored owners that are exactly one voice's name are linked.
- [x] `Meeting.MinutesItem` carries the linked voice; the repository reads and writes it.
- [x] `MinutesChecks` keeps an owner that is a speaker of the transcript; `MeetingMinutesService` links it.
- [x] `MeetingService.editItem` and `addItem` take a voice or a text, never both; an unknown voice is refused.
- [x] The biên bản and the Markdown name a linked owner by the voice's current name.
- [x] API: `ownerSpeaker` on the item and both requests; `openapi.yml` and the web client regenerated.
- [x] Page: a linked owner shows as its voice and can be named there; the edit form offers the voices.
- [x] Prompt: the owner is who does the work.
- [x] Tests: `TranscriptSummarizerTest` (a speaker label of the transcript is kept), the minutes service or repository round trip, `MeetingService` edit with a voice, the biên bản and Markdown after a rename, the page.
- [x] Measurement: 23 staging meetings through 9Router, owners judged for who does the work before and after the prompt change.
- [x] Docs: `docs/specs/meeting.md`, `docs/tests/meeting.md`, roadmap.
- [x] Staging: rerun three meetings, name a voice, check the page, the biên bản and the Markdown.
- [x] Markdown already in a library: taking the minutes into a conversation again replaces a file that no longer reads as the minutes do (found on staging after #873: naming a voice changed the page and the biên bản, but the published Markdown kept "Người nói 1").

## Verification

- Targeted core and API tests on zm; `clean check` in CI.
- `pnpm --dir web check` scope: typecheck, lint, the meeting tests.
- Screenshots of the minutes with linked owners, before and after naming a voice.

## Measurement

The 23 staging transcripts were sent to `cx/gpt-6-luna` on 9Router with the old and the new owner rule, the owners passed through the checks, and each action was judged by the same model against the six lines before and three after its cited line: is the owner the one who will do the work, the one who asked, someone else, or is the owner missing where the lines make the doer clear.

| | Old rule | New rule |
|---|---|---|
| Actions | 82 | 74 |
| Owner given | 46 | 53 |
| Owner is the doer | 23 | 28 |
| Owner is the one who asked | 7 | 7 |
| Owner is someone else | 11 | 13 |
| No owner although the lines name the doer | 35 | 20 |
| Owner is a voice of the meeting | 28 | 42 |

The judge itself is noisy (it marks "Nam" as someone else while naming Nam the doer, and an action with no owner as someone else), so the counts are a direction, not a score: the new rule gives an owner to more actions and roughly as many wrong ones. About half the owners given are judged right, which is why the owner stays editable and a linked voice can be renamed or replaced from the item. The V197 backfill, run as a select on staging, links 19 of the 55 stored owners; the rest are names or groups and stay text.

## Status

- 2026-11-02: opened after the owner chose to keep owners and link them to voices; implemented, tested on zm (core 36, API minutes and OpenAPI, web 53) and measured.

Staging after #873 (2 November 2026): V197 ran and linked the 19 owners the dry run found, with no row breaking its rule. Rerunning three meetings gave 19 of 20 actions in the two technical meetings a voice as owner, where none had an owner before; the third, with named people, kept text owners. Naming Người nói 1 "Anh Khanh" renamed all four of its actions in the page data and in the biên bản; the Markdown already in the library still read "Người nói 1", which the step above fixes.

After #876 (2 November 2026): taking the same minutes into a conversation after naming the voice replaced the library file, and the new one reads "Anh Khanh" four times and "Người nói 1" none. The voice was unnamed again and the test file deleted.
