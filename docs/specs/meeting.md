# Meetings

Owner-private meetings recorded without a bot: the member's microphone and, online, the shared meeting tab stream to MemoryOS, which transcribes them and stores the finalized utterances. A recording made elsewhere can be uploaded instead. Audio is never stored, and an uploaded recording is deleted once it has been transcribed. Design and remaining phases: [MEM-92](../increments/active/meeting-notes/design.md).

## Ownership and sharing

A meeting belongs to the member who recorded it, in one Tenant. Every route requires `CHAT_WRITE` in the active Tenant, and a meeting that reaches neither the owner nor a reader answers `MEETING_NOT_FOUND` exactly like a missing one. Deleting a meeting deletes its speakers, utterances, minutes and shares.

Only the owner records, renames a speaker, writes notes, ends the meeting, reruns the minutes, ticks an action off, shares it or deletes it.

`PUT /api/meetings/{id}/shares` replaces who else may read it, naming members and Groups (`meeting_user_share`, `meeting_group_share`, the shape Agents and Document Sets use, without their permission column because a reader only reads). A person who is not an active member, or a Group that is not the Tenant's, is refused rather than dropped; the owner is never listed, since they already read it. At most 200 of each.

A reader opens the meeting while it records and after it ends: the transcript as it arrives, the speaker names, the minutes and the biên bản. They do not get the owner's private notes, which read as empty, nor the list of readers. `owned` on the meeting and on every list row says which of the two the caller is.

## Meeting

| Field | Rule |
| --- | --- |
| `title` | 1–200 characters after trimming, no control characters |
| `kind` | `ONLINE` records `MIC` (the owner) and `TAB` (everyone else); `IN_PERSON` records `MIC` only, which hears the whole room |
| `language` | `vi`, `en` or absent; sent to the provider as a strict hint and fixed for the meeting |
| `participants` | At most 50 distinct names of up to 200 characters, used to name speakers |
| `terms` | At most 100 distinct terms of up to 100 characters, sent to Soniox as `context.terms` |
| `notes` | The owner's private notes, at most 50,000 characters, replaced with the meeting `revision` (409 `MEETING_CONFLICT` when stale) |
| `status` | `RECORDING` until `end`, then `ENDED`; `TRANSCRIBING` while an uploaded recording is being read. Only a `RECORDING` meeting issues tickets (409 `MEETING_ENDED` otherwise) |
| `provider`, `diarized` | The last stream's provider and whether any stream separated speakers |

## Recording a track

1. `POST /api/meetings/{id}/tickets` with `{track}` issues a 60-second single-use ticket bound to the actor, the meeting and the track (the voice ticket store with scope `MEETING:{id}:{track}`).
2. The browser opens the same-origin WebSocket `/api/meeting-stream?meeting=&track=&offset=&ticket=`. `offset` is the recording time in milliseconds of the first sample, so a reconnect or a resume after pause continues the meeting clock. The handshake consumes the ticket and rechecks ownership and `RECORDING`.
3. The socket accepts PCM16 24 kHz mono binary frames of at most 64 KiB and one `{"type":"end"}`. It answers `ready`, `preview {track, speaker, text}` (uncommitted speech that replaces the previous preview), `utterance {id, track, speaker, startMs, endMs, text, confidence, spans}` (already stored), `finished` after an end, and `error {code}`. The server pings the socket every 20 seconds while a track is open: nobody speaking means it writes nothing for minutes, and a reverse proxy reads an idle upstream as a dead one — the staging edge cuts at 300 seconds. The browser answers the ping without the page being told. Codes: `MEETING_INVALID_AUDIO`, `MEETING_INVALID_MESSAGE`, `MEETING_TOO_LONG` (a track records at most five hours), `MEETING_IDLE` (no audio for 60 seconds; pausing closes the socket), `MEETING_PROVIDER_FAILED`, `MEETING_BUSY`, `MEETING_UNAVAILABLE`, plus the meeting codes above.
4. `POST /api/meetings/{id}/end` ends the meeting; open sockets end with their browser.

## Transcription

Each track is one provider stream over the Tenant's default speech-to-text connection (`chat.voice` `LiveTranscriptionService`). An actor has at most two streams and the API process at most 32. Audio time is recorded as `SPEECH_TO_TEXT` usage when a stream closes.

- **Soniox** streams realtime with endpoint detection, the language hint and the meeting's terms. Online, `MIC` is not diarized (it is the owner); `TAB` and in-person `MIC` are. Final tokens form an utterance that ends at a speaker change or an `<end>` endpoint. A keepalive is sent after five seconds without audio. When the provider stream fails, it reconnects after 2/5/10/20/30 seconds, replays the last five seconds of audio and shifts the new stream's times by the audio sent before the replay; after the last retry the socket reports `MEETING_PROVIDER_FAILED`.
- **Other providers** have no live protocol: audio is cut into utterances after 800 ms of silence or at 30 seconds, silence never reaches the provider, and each utterance is transcribed through the provider's REST adapter with speaker `1`.

An utterance also carries `spans`: the stretches the provider was least sure of, as half-open character offsets into the stored text with the lowest confidence among the tokens each covers. A token below 0.6 — Soniox's own review threshold — is marked, neighbouring marked tokens become one stretch, and the threshold is applied once, when the utterance is built, so stored stretches reflect the threshold in force at transcription time. Only Soniox reports a confidence per token, live and for an uploaded recording; every other provider stores an empty list and nothing is marked. The transcript highlights those stretches and shows the percentage on hover.

## Correcting what was misheard

The owner asks a model what was probably said at each marked stretch: `POST /api/meetings/{id}/corrections` answers the run and its proposals, and changes nothing. Only the owner reaches any of this — a reader of a shared meeting gets 404 — and the call is billed to the owner's Tenant as `MEETING_CORRECTION`, a model flow an administrator selects like any other. One pass at a time per meeting; a second press while one is running answers 409.

Neighbouring marks that read as one phrase are asked about together: joined when at most 5 characters and 8 words apart with no `.`, `!` or `?` between them. Each stretch is sent with 100 characters of context either side, the two lines before and after with the one being judged marked, the speaker, the meeting's terms, and the same words where they appear clearly elsewhere in the transcript. A line the owner has rewritten is never sent again.

A proposal carries the model's reason and three scores — is this what was said, does it fit the sentences around it, does it leave the meaning alone. Naturalness is deliberately not asked for: people speak untidily, and tidying that is a change to what was said.

`POST .../corrections/{id}/accept` puts the words in, either the model's or the owner's own; `/keep` declines and leaves the record showing what was offered; `/accept-all` takes everything a run still has undecided, applying later stretches of a line first so the earlier offsets still hold; `/revert` puts back what the line said before. A stretch whose line moved since the run answers 409 rather than writing over words it never read.

`meeting_utterance.text` is always what the reader sees. Every change to it is a row in `meeting_utterance_event` with its `before`, its `after`, who made it and which run it belonged to, so the words the provider first wrote stay recoverable. `edit_source` says who last changed a line (`MODEL`, `HUMAN`, or nothing at all) and is what locks a line the owner rewrote. Accepting shifts the line's remaining marks: one covering the replaced words is dropped, the rest move by the difference in length.

Utterances are stored as they are committed, with their speaker row created on first use. Naming a speaker (`PUT /api/meetings/{id}/speakers/{track}/{label}`) applies to every utterance of that speaker; a blank name restores the automatic label.

## Uploading a recording

A meeting that has recorded nothing can be made from a recording taken elsewhere — a phone, a dictaphone, a desktop meeting client.

1. `GET /api/meetings/transcribers` lists the Tenant's speech-to-text connections that transcribe a whole file, its own first, each with its model, whether it separates speakers and the largest file it accepts. Membership is enough to read it; it carries no endpoint or credential.
2. `POST /api/meetings/{id}/recording` reserves storage for a declared filename, media type, size and SHA-256, and optionally the provider to use. The meeting moves to `TRANSCRIBING` and its `audio.status` to `WAITING`. The browser then PUTs the bytes straight to object storage with the returned presigned authorization, as a library upload does; the bytes never pass through the API.
3. `POST /api/meetings/{id}/recording/finalize` verifies the stored bytes against what was declared, adopts the object and queues the transcription.

Accepted containers are MP3, M4A, WAV, WebM, OGG, FLAC and MP4, at most 500 MB and no more than the chosen provider accepts. A meeting that already has a transcript, or that is no longer `RECORDING`, refuses a recording, and a meeting being transcribed refuses `end`. A Tenant with no connection that reads a file is refused at the reservation, before anything is uploaded, and the browser says so instead of offering the upload. Uploading asks for the same consent the live dialog does.

The job runs in the API beside the minutes, on the same scheduler, with a one-hour lease and three attempts. The file is handed to the provider in the container it arrived in: MemoryOS decodes no audio. **Soniox** transcribes up to five hours with `enable_speaker_diarization`, and its tokens are grouped into utterances at a speaker change, an 800 ms pause or 400 characters, as the live adapter groups them. An **OpenAI-compatible** connection answers `verbose_json` segments for a single speaker and caps the file at 25 MB. ElevenLabs and Azure transcribe dictation only and are not listed for a recording.

The segments become `MIC` utterances, the meeting ends, and its minutes are queued as for a live meeting. The recorded length the provider reported is added as `SPEECH_TO_TEXT` usage — not the file's size, because compressed bytes say nothing about seconds.

**The audio is deleted** when the transcript is stored, when the attempts run out, and when the meeting is deleted.

## Minutes

Ending a meeting that has a transcript queues its minutes; a meeting nobody spoke in has none. `POST /api/meetings/{id}/minutes` queues them again after the transcript, the speaker names or the owner's notes changed, and refuses a meeting still recording or without a transcript.

`minutes.status` is `NONE`, `PENDING`, `RUNNING`, `READY` or `FAILED`. A `FAILED` meeting carries a code, never provider text, and keeps its transcript.

The job runs in the API process, where the model catalog lives, on a fixed delay (`memoryos.meeting.minutes-interval`, five seconds). It takes the oldest waiting meeting with `FOR UPDATE SKIP LOCKED`, leases it for ten minutes and raises its attempt count, so several API replicas never summarize the same meeting and a meeting that fails three times stops being retried. The model call happens outside any transaction; only the claim and the result are transactional, and a lease that lapsed mid-run loses the write to the replica that took the meeting over.

One call to the Tenant's model for `MEETING_MINUTES`, no tools and no conversation, produces a structured `summary`, a `kind`, `decisions` and `actions`. The transcript reaches the model as numbered lines (`[n] hh:mm:ss Speaker: text`) under the title, the participants, the meeting time and the owner's notes; speakers appear under the names the owner gave them. Past 120,000 characters both ends are kept and the model is told part of the meeting is missing. The prompt forbids inventing a decision, an action, an owner or a date, requires an explicit assignment before work becomes an action, refuses to assign work to a group, and treats the transcript as untrusted data.

Each item cites the line it rests on; that line number becomes the utterance id, so the owner can jump from an item to what was said. An item without text is dropped, an owner or due date is at most 100 characters and text or a quote at most 2,000. The call's tokens are recorded as `MEETING_MINUTES` usage against the owner's Tenant.

`PUT /api/meetings/{id}/minutes/{itemId}` ticks an action off; an item that is not the owner's answers `MEETING_NOT_FOUND`.

## Taking the minutes into Chat

`POST /api/meetings/{id}/library` writes the minutes into the caller's file library as Markdown and answers the file, so a conversation can use them. It needs `READY` minutes, and asking twice returns the file already made (V92's live-copy index, with `MEETING` added to `copied_from_source` in V112). Rewriting the minutes deletes that file so the next call publishes what was rewritten; a file a Project or an Agent still holds is left alone.

The file carries the summary, the decisions and the work with their owners, deadlines and the sentence each rests on. **It never carries the transcript.** A transcript is what people actually said, and the Delaware Chancery court in *ATG Capital v. Lane* read one back against the minutes it contradicted; counsel's advice since is that the approved minutes are the record while the transcript is working material kept close. The file says where the full wording lives.

*Open in Chat* on the meeting page publishes the file and opens a new conversation with `?attach={fileId}`; the chat page waits for the file to be extracted, puts it in the composer and drops the parameter.

## Biên bản

`POST /api/meetings/{id}/minutes/export` returns the minutes as a Vietnamese *biên bản* in Word format, built with Apache POI. It is deterministic: the same meeting and the same heading always produce the same document, and no model runs.

The layout follows Nghị định 30/2020/NĐ-CP mẫu 1.9 — the two-column letterhead (cơ quan and số on the left, quốc hiệu and tiêu ngữ on the right), *BIÊN BẢN*, *Về việc*, the opening time and place, *I. Thành phần tham dự*, *II. Nội dung cuộc họp* (the summary), *III. Kết luận cuộc họp* (the decisions), *IV. Nhiệm vụ được giao* (the actions, each read back as “<task> do <owner> thực hiện, thời hạn <due>.”) and the thư ký and chủ tọa signature block — in Times New Roman at 13 points, 14 for the title.

The decree binds state bodies; a company follows it by convention. So the heading the transcript cannot know — cơ quan, số, về việc, địa điểm, the opening and closing times, chủ trì, thư ký and their roles — travels with the request and is never stored. The browser pre-fills what the meeting already knows, and a field left empty prints as an ellipsis to write on. The minutes must be `READY`.
