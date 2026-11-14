# MEM-301: an action's owner points at a speaker of the meeting

## Problem

Most voices in a meeting are never named: on staging, 3 of 30 meetings had a named speaker. The minutes model then writes an action's owner as the voice's label, "Người nói 2". [MEM-300](../../completed/mem-300-minutes-checks/design.md) cleared those owners as "not one person", but the label is a person — one the page has not named yet. Read line by line, about 7 of the 10 label owners of the staging "IT" meeting named the voice that took the work on, 2 were unclear and 1 was likely the voice that asked for it. Clearing them left that meeting, and most others, with no owner on any action, so the biên bản lost its *do … thực hiện*.

## References

Checked on 2026-11-02 from vendor documentation and source code:

- **Fireflies** assigns action items by speaker label. An upload gets "Speaker 1, 2"; the owner renames the speaker and regenerates the notes (guide.fireflies.ai).
- **Nojoin** (open source, `.tmp/Nojoin`) keeps the label in its notes and carries a speaker rename into them (`test_speaker_rename_propagates_into_generated_notes`); its model may suggest a name per label with quoted evidence.
- **Teams** and **Granola** tie the owner to an identity: the signed-in participant, or the calendar attendee matched to a diarized voice.
- **Otter** and **Zoom** leave an owner unset when unknown and let the user assign one; every product lets the user reassign.
- **opennotetaker** (`.tmp/opennotetaker/crates/note-summary/src/prompt.rs`) takes an owner only where the transcript names one or the speaker commits themselves; otherwise null.
- No product clears an owner that names a meeting voice; tl;dv is the one that assigns nobody.

## Decision

1. **A voice of the meeting is an owner.** `MinutesChecks` keeps an owner that is exactly a speaker name of the transcript the model read (a label, a name the owner gave, or *Chủ cuộc họp*). A speaker label that is not a voice of this meeting, a group and several people are still cleared.
2. **Stored as a link, shown by its current name.** `meeting_minutes_item` gains `owner_track` and `owner_label`, referencing `meeting_speaker`. When the model's owner is one voice's name, the item stores the link and no text. The name is read when the item is shown, as the transcript reads it, so naming a voice once names every action it owns on the page, in the biên bản and in the Markdown, with no rerun. An owner said only in the words ("anh Nam sẽ cấp") stays text.
3. **The owner picks or types.** Editing or adding an action offers the meeting's voices by the names the page shows; picking one stores the link, typing anything else stores text. The API takes `ownerSpeaker` (`track`, `label`) or `owner`, never both.
4. **The page shows a linked owner as its voice.** The voice's badge and name; while the voice is unnamed, the owner can name it from there with the same control as the speakers panel.
5. **The prompt names who does the work.** The owner is the one person who will do the work, not the one who asked for it: their name, or the speaker name at the start of their lines when they take it on themselves.
6. **Stored minutes follow.** A migration links every stored action whose owner text is exactly one voice's name in its meeting, as `SpeakerNames` would name it.

## Choices made

- **A link, not a rewrite of stored text.** Nojoin rewrites its notes by string match on rename, and needs a word-boundary guard for it; a link has no text to rewrite and cannot touch a word that merely contains the name.
- **The model still writes names, the service turns them into links.** The model reads speaker names, not track and label; matching its answer against the names it was shown is exact and needs no new output field.
- **The edit form sends a link explicitly.** Matching typed text to a voice on the server would turn a typed "Người nói 2" into a link without the owner choosing it.
- **No suggested names from a model in this increment.** Nojoin's evidence-backed suggestions are the next lever for unnamed voices; MemoryOS already offers the name a voice gave itself.

## Not in scope

- A model suggesting names for unnamed voices; voiceprints across meetings.
- Owners as workspace members or synced tasks.
- The rest of the prompt evaluation (missed actions, reversed decisions).
