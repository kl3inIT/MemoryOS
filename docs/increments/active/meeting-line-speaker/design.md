# Meeting: say who spoke one line

**Status:** in progress.

## Problem

Live diarization merges two people into one voice when they sit together on one device, and online the microphone
track is not diarized: it is taken to be the owner. On staging on 2026-11-04, a colleague beside the owner presented
through his own laptop, and voice 2 of the tab carried both him and another colleague. Every line the owner's
microphone heard from him was shown as the owner. Today a name belongs to a whole voice (`meeting_speaker(track,
label)`), so neither mistake can be corrected, and the minutes give his work to the wrong person.

## References

| Product | How | Where |
| --- | --- | --- |
| Nojoin | Clicking a line's speaker opens a picker: the voices of the recording, known people, "Create local «name»". Scope "This utterance" (default) or "Whole transcript". The line is moved to the chosen speaker and locked, and a new name becomes a new speaker of its own. | `.tmp/Nojoin/frontend/src/components/SpeakerAssignmentPopover.tsx:246-261`, `backend/models/pipeline.py:76-80`, `backend/utils/canonical_pipeline/core.py:1240-1424, 1939-1950` |
| anarlog | The same picker with an "Apply to all" checkbox; a selection of lines can also be reassigned together. | `.tmp/anarlog/apps/desktop/src/session/components/note-input/transcript/renderer/speaker-assign.tsx:577-587`, `renderer/selection-menu.tsx` |
| opennotetaker | One click moves the line to the next speaker; no picker. | `.tmp/opennotetaker/apps/web/src/views/note.ts:91-147` |
| callmd | Not supported. | — |

## Decision

Follow Nojoin. A line's speaker on the page opens a picker: the meeting's voices and "Thêm «name»", with two scopes,
**Câu này** (default) and **Mọi câu của người này**. The second is the existing rename of the whole voice.

**This line** moves the utterance to another voice of its own track:

- a voice of the same track: the line takes its label;
- a named voice of the other track: the line takes the voice of its own track that already carries that name, or a
  new voice of its own track with that name. A voice keeps one track, because each track is its own stream, the
  minutes point at `(track, label)` and nothing downstream has to learn otherwise. An unnamed voice of the other track
  is not offered: there is no name to carry over;
- a new name: the voice of its own track with that name, or a new one.

A new voice is labelled `M1`, `M2`… within its track. Providers label voices with numbers, so a manual label never
meets one a stream adds later. The utterance keeps the label the provider gave in `diarized_speaker`, written the first
time the line is moved and never again, so a move can always be traced back to what was heard.

Renaming a voice still names every line it holds, including lines moved to it. A voice whose lines all moved stays
listed until the meeting ends, as before. The minutes, the exports and the corrections name the line by its voice, so
they follow the move once the minutes are written again.

Not now: selecting several lines (anarlog's selection menu), "from this line on" (Nojoin's backend-only scope), and
teaching voiceprints, which MemoryOS does not keep.

## Contract

`PUT /api/meetings/{id}/utterances/{utteranceId}/speaker` with `{speaker: {track, label}}` or `{name}`, exactly one,
owner only. It answers `{utterance, speaker}`: the line as it now reads and the voice it now belongs to, which the page
adds to its list when it is new. 404 for a line or voice the meeting does not have; 400 for both or neither, a name of
more than 200 characters, or an unnamed voice of the other track.
