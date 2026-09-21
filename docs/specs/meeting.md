# Meetings

Owner-private meetings recorded without a bot: the member's microphone and, online, the shared meeting tab stream to MemoryOS, which transcribes them and stores the finalized utterances. Audio is never stored. Design and remaining phases: [MEM-92](../increments/active/meeting-notes/design.md).

## Ownership and authorization

A meeting belongs to one actor in one Tenant. Every read, edit, ticket and socket requires `CHAT_WRITE` in the active Tenant and ownership; another member's meeting answers `MEETING_NOT_FOUND` exactly like a missing one. Deleting a meeting deletes its speakers and utterances.

## Meeting

| Field | Rule |
| --- | --- |
| `title` | 1–200 characters after trimming, no control characters |
| `kind` | `ONLINE` records `MIC` (the owner) and `TAB` (everyone else); `IN_PERSON` records `MIC` only, which hears the whole room |
| `language` | `vi`, `en` or absent; sent to the provider as a strict hint and fixed for the meeting |
| `participants` | At most 50 distinct names of up to 200 characters, used to name speakers |
| `terms` | At most 100 distinct terms of up to 100 characters, sent to Soniox as `context.terms` |
| `notes` | The owner's private notes, at most 50,000 characters, replaced with the meeting `revision` (409 `MEETING_CONFLICT` when stale) |
| `status` | `RECORDING` until `end`, then `ENDED`; an ended meeting issues no tickets (409 `MEETING_ENDED`) |
| `provider`, `diarized` | The last stream's provider and whether any stream separated speakers |

## Recording a track

1. `POST /api/meetings/{id}/tickets` with `{track}` issues a 60-second single-use ticket bound to the actor, the meeting and the track (the voice ticket store with scope `MEETING:{id}:{track}`).
2. The browser opens the same-origin WebSocket `/api/meeting-stream?meeting=&track=&offset=&ticket=`. `offset` is the recording time in milliseconds of the first sample, so a reconnect or a resume after pause continues the meeting clock. The handshake consumes the ticket and rechecks ownership and `RECORDING`.
3. The socket accepts PCM16 24 kHz mono binary frames of at most 64 KiB and one `{"type":"end"}`. It answers `ready`, `preview {track, speaker, text}` (uncommitted speech that replaces the previous preview), `utterance {id, track, speaker, startMs, endMs, text, confidence}` (already stored), `finished` after an end, and `error {code}`. Codes: `MEETING_INVALID_AUDIO`, `MEETING_INVALID_MESSAGE`, `MEETING_TOO_LONG` (a track records at most five hours), `MEETING_IDLE` (no audio for 60 seconds; pausing closes the socket), `MEETING_PROVIDER_FAILED`, `MEETING_BUSY`, `MEETING_UNAVAILABLE`, plus the meeting codes above.
4. `POST /api/meetings/{id}/end` ends the meeting; open sockets end with their browser.

## Transcription

Each track is one provider stream over the Tenant's default speech-to-text connection (`chat.voice` `LiveTranscriptionService`). An actor has at most two streams and the API process at most 32. Audio time is recorded as `SPEECH_TO_TEXT` usage when a stream closes.

- **Soniox** streams realtime with endpoint detection, the language hint and the meeting's terms. Online, `MIC` is not diarized (it is the owner); `TAB` and in-person `MIC` are. Final tokens form an utterance that ends at a speaker change or an `<end>` endpoint. A keepalive is sent after five seconds without audio. When the provider stream fails, it reconnects after 2/5/10/20/30 seconds, replays the last five seconds of audio and shifts the new stream's times by the audio sent before the replay; after the last retry the socket reports `MEETING_PROVIDER_FAILED`.
- **Other providers** have no live protocol: audio is cut into utterances after 800 ms of silence or at 30 seconds, silence never reaches the provider, and each utterance is transcribed through the provider's REST adapter with speaker `1`.

Utterances are stored as they are committed, with their speaker row created on first use. Naming a speaker (`PUT /api/meetings/{id}/speakers/{track}/{label}`) applies to every utterance of that speaker; a blank name restores the automatic label.

## Minutes

Ending a meeting that has a transcript queues its minutes; a meeting nobody spoke in has none. `POST /api/meetings/{id}/minutes` queues them again after the transcript, the speaker names or the owner's notes changed, and refuses a meeting still recording or without a transcript.

`minutes.status` is `NONE`, `PENDING`, `RUNNING`, `READY` or `FAILED`. A `FAILED` meeting carries a code, never provider text, and keeps its transcript.

The job runs in the API process, where the model catalog lives, on a fixed delay (`memoryos.meeting.minutes-interval`, five seconds). It takes the oldest waiting meeting with `FOR UPDATE SKIP LOCKED`, leases it for ten minutes and raises its attempt count, so several API replicas never summarize the same meeting and a meeting that fails three times stops being retried. The model call happens outside any transaction; only the claim and the result are transactional, and a lease that lapsed mid-run loses the write to the replica that took the meeting over.

One call to the Tenant's model for `MEETING_MINUTES`, no tools and no conversation, produces a structured `summary`, a `kind`, `decisions` and `actions`. The transcript reaches the model as numbered lines (`[n] hh:mm:ss Speaker: text`) under the title, the participants, the meeting time and the owner's notes; speakers appear under the names the owner gave them. Past 120,000 characters both ends are kept and the model is told part of the meeting is missing. The prompt forbids inventing a decision, an action, an owner or a date, requires an explicit assignment before work becomes an action, refuses to assign work to a group, and treats the transcript as untrusted data.

Each item cites the line it rests on; that line number becomes the utterance id, so the owner can jump from an item to what was said. An item without text is dropped, an owner or due date is at most 100 characters and text or a quote at most 2,000. The call's tokens are recorded as `MEETING_MINUTES` usage against the owner's Tenant.

`PUT /api/meetings/{id}/minutes/{itemId}` ticks an action off; an item that is not the owner's answers `MEETING_NOT_FOUND`.
