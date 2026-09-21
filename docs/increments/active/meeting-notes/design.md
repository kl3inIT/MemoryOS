# MEM-92 — Meeting notes: bot-less meeting capture, live transcript and minutes

Linear: [MEM-92](https://linear.app/memory-os/issue/MEM-92) (this increment; phase 3 keeps its original cross-meeting tasks scope), related to [MEM-91](../mem-91-chat-voice/design.md) (voice providers). Meeting-platform transcript connectors are [MEM-169](https://linear.app/memory-os/issue/MEM-169). Status: designed, not started. Interactive mock: [mock.html](mock.html) (also published at <https://claude.ai/artifact/2hZMDJbwYYpVZJUVg6Ltio>). The mock shows intent, not a specification: implementation follows MemoryOS components and may change layout and flow where the real product works better; record such changes here.

## Problem

Most commitments, decisions and blockers at Tasco are spoken in meetings and never written down. MemoryOS can search and answer over documents, but a meeting only becomes knowledge if someone types minutes by hand. Glean ships this as *Meeting notes*: no bot joins the call, the user's own device captures microphone and meeting audio, a transcript and a summary with decisions and action items appear when the meeting ends, and the notes are private and searchable. MemoryOS needs the same, web-first, in Vietnamese, self-hosted.

## Domain story

1. A **user** opens **Cuộc họp** and starts a **meeting**: title, participants, online or in-person, language, domain terms, and confirms that participants were told.
2. Online: the browser asks the user to share the meeting tab *with its audio*; the **microphone** and the **tab** become two **audio tracks**. In person: the microphone only (phone or laptop).
3. The browser streams both tracks to MemoryOS; MemoryOS relays them to the **speech provider**, and each finalized **utterance** (speaker, start/end ms, text, confidence) is stored as it arrives. Audio is never stored.
4. During the meeting the user sees the live transcript, writes **private notes**, and may ask questions about the meeting so far ("catch me up").
5. The user stops. A **minutes job** produces the **summary**, **decisions** and **action items**, each linked to the utterance it came from.
6. The user names speakers, corrects the transcript, edits the summary, and asks Chat about the meeting later.

Failure paths: the tab share has no audio or is stopped (warn within ~20 s, offer to re-share, keep recording the mic); the connection drops (reconnect with a new ticket to the same meeting, buffer audio meanwhile); the tab closes (confirm; already-stored utterances survive; the meeting can be resumed); the provider fails (switch meeting to `INTERRUPTED`, keep what was stored); the minutes job fails (retry with lease and attempts, then `FAILED` with the transcript intact).

## Flow and where each step comes from

The flow is a synthesis: Glean's product flow is the skeleton; each step takes its detail from the source that handles it best, and fixes the weaknesses the community reports.

| Step | Behaviour | Taken from |
| --- | --- | --- |
| Start | Explicit start, never automatic; consent confirmation and a copyable notice | Glean (start and consent guidance), Skype notice on Mobbin, the Granola lawsuit |
| Setup | Title, participants, online or in person, language locked for the session, domain terms | Fireflies (language locked), ghiam-pro (participants from the meeting schedule, custom terms), Soniox `context` |
| Capture | Microphone plus shared tab, two separate tracks; mic-only on unsupported browsers and phones | Glean (mic + system audio, no bot), Nojoin and OpenNoteTaker (browser capture and support matrix), community complaints about speaker labels |
| Health | Per-track level meters; warn when the tab is silent or sharing stops; recording indicator | Community findings on silent failures, Nojoin floating badge |
| Live | Transcript with speaker and low-confidence marks; private notes beside it | Glean (notes beside transcript), ghiam-pro (token grouping, low-confidence ranges), Granola and Amie (private notes) |
| Interruptions | Reconnect with buffered audio, resume after a closed tab, stop confirmation | Nojoin (resumable recording), Fireflies (stop and save), ghiam-pro (what loses audio) |
| After | Summary, decisions, action items linked to utterances; gaps stated honestly | Glean (outputs), Grain (timestamps and assignee), Granola (honest poor-audio note), ghiam-pro (task and minutes prompts) |
| Naming | Speaker chip opens a people search; applies to every utterance of that speaker | Amie on Mobbin, Nojoin (suggestions and provenance) |
| Keep | Owner-private, in the file library, readable by Chat; later in Search | Glean (private, first-class artifact), MemoryOS file library |
| Govern | Tenant switch, retention tied to chat retention, usage by audio seconds | Glean (admin rollout, retention, usage billing), MemoryOS AI usage |

## Glossary

| Term | Meaning |
| --- | --- |
| Meeting | One meeting owned by one actor in one Tenant. Owns utterances, speakers, notes and minutes. Its transcript source is `LIVE`, `UPLOAD`, or later a platform connector (MEM-169). |
| Audio track | `MIC` (the owner's voice online, the whole room in person) or `TAB` (everyone else in an online call). |
| Utterance | One finalized transcript segment: track, speaker, `start_ms`, `end_ms`, text, confidence, low-confidence character ranges. |
| Meeting speaker | A diarized voice within a meeting; online, the `MIC` track is the owner. A name is assigned by the user, with provenance (`USER`, `SUGGESTED`). |
| Minutes | The generated summary, decisions and action items of a meeting, with their source utterances. |
| Private notes | Free text the owner writes during or after the meeting; input to the minutes. |

## References and what is learned from each

Learning means logic, flow, prompts and UX. No source's code is ported; MemoryOS code is written in its own patterns.

| Source | What we learn |
| --- | --- |
| [Glean Meeting notes](https://docs.glean.com/user-guide/assistant/meeting-notes/), [consent guidance](https://docs.glean.com/user-guide/assistant/meeting-notes/transcription-and-consent), [blog](https://www.glean.com/blog/glean-meeting-notes) | Product flow: explicit start, no bot, live notes beside the transcript, *Catch me up* over the last two minutes answered from the transcript only, notes private by default, raw audio never stored, Tenant switch (all / admins / off), transcript retention not longer than chat retention, usage billed by audio time. Their gaps we close: no language choice, weak non-English, no self-hosting, no mobile. |
| `.tmp/ghiam-pro` (the owner's Vietnamese meeting recorder) | Token-to-utterance grouping by speaker and low-confidence ranges; the Vietnamese transcript-correction logic (span building with context, span types, priority, accept tiers, document-wide consistency, glossary, guards against rewriting outside the span); prompts for Vietnamese administrative minutes (*biên bản*: thành phần, nội dung, biểu quyết, kết luận, nhiệm vụ), explicit-assignment task extraction with a source quote, meeting-type classification; mistakes to avoid (stored audio, chunk loss, JSONB transcript blob, averaged confidence, missing ownership checks). |
| [Nojoin](https://github.com/Valtora/Nojoin) (AGPL-3.0; reuse accepted by the owner) | Browser capture flow and unsupported-browser notice ([`docs/CAPTURE.md`](https://github.com/Valtora/Nojoin/blob/main/docs/CAPTURE.md)); paused/resumable recording; speaker-name suggestions with assignment provenance; notes templates and transcript chat. |
| [OpenNoteTaker](https://github.com/opennotetaker/opennotetaker) (Apache-2.0) | Tab + microphone capture UX, macOS whole-system-audio gap, echo caveats, mixed-language handling. |
| [silent-notetaker](https://github.com/MikeSchirtzinger/silent-notetaker) (MIT) | Live extraction buckets: decisions, action items, key points, open questions. |
| [Anarlog](https://github.com/fastrepl/anarlog) (MIT, ex-Hyprnote) | Soniox realtime protocol handling; later desktop capture (Core Audio tap, WASAPI loopback), echo cancellation and meeting detection. |
| [Soniox docs](https://soniox.com/docs/api-reference/stt/websocket-api), [diarization](https://soniox.com/docs/stt/concepts/speaker-diarization), [security](https://soniox.com/docs/security-and-privacy), [pricing](https://soniox.com/pricing) | Realtime WebSocket, sessions up to 300 minutes, diarization, `context` for domain terms, no retention of realtime audio, no training on customer data. |
| Mobbin, 2026-09-21 | Recording bar inside the meeting page ([Lightfield](https://mobbin.com/screens/41a4a338-1606-4667-859e-94782b4eb885)); language chosen before start and locked, stop confirmation ([Fireflies flow](https://mobbin.com/flows/2e0c4e46-3edd-4f2e-9477-b472092dcfee), [stop](https://mobbin.com/screens/8c289a95-1e9a-4340-9439-3102c794ded9)); speaker chip opens a people search ([Amie](https://mobbin.com/screens/0a4f60ca-2103-4f99-b796-d584637f07cb)); outcomes and action items with timestamps and assignee ([Grain](https://mobbin.com/screens/6bfcb4d4-ecae-4210-a8b2-42a2305c0a73)); meeting-scoped chat with suggested questions ([Otter](https://mobbin.com/screens/56ea7686-8741-49d5-8f5e-ef4b6e7de71e), [Granola](https://mobbin.com/screens/e8a43616-3671-44bf-9a46-c805e98fc9aa)); honest poor-audio summary ([Granola](https://mobbin.com/screens/d4d02001-91ed-4f70-b998-4c7b17f9e811)); recording notice ([Skype](https://mobbin.com/screens/7c5ce60b-3346-4e8b-8c2c-590b91725584)); list grouped by date with status ([Fireflies](https://mobbin.com/screens/f84c0ac7-8e73-4302-b3af-26563fb47220), [Grain](https://mobbin.com/screens/12251095-9692-4035-b44a-81bda273c740)). Anti-pattern: Granola's "anyone with the link" sharing ([screen](https://mobbin.com/screens/9413e56c-366a-4913-b196-d7ee6c45907a)). |
| Community findings | Speaker attribution is the top complaint about bot-less tools ([HN Hyprnote launch](https://news.ycombinator.com/item?id=44725306), [Meetily #230](https://github.com/Zackriya-Solutions/meetily/issues/230)); private defaults and consent matter ([Granola public-by-default](https://www.techbuzz.ai/articles/granola-s-private-ai-notes-are-public-by-default), [*Chamberlain v. Granola*](https://www.computerworld.com/article/4206255/granola-lawsuit-raises-concerns-over-ai-note-taking-app-privacy.html)); silent capture failures must be surfaced ([browser capture write-up](https://dev.to/flo152121063061/i-tried-to-capture-system-audio-in-the-browser-heres-what-i-learned-1f99)). |
| Vietnamese ASR evidence | No independent Vietnamese benchmark of Soniox exists. Independent numbers: Azure 11.8 and Google USM 13.0 average WER, Whisper large-v3 16.4 ([VietASR](https://arxiv.org/html/2505.21527v1)); conversational speech is far harder than read speech and the Central accent is hardest ([Qualcomm, EACL 2026](https://aclanthology.org/2026.findings-eacl.345.pdf)); Vietnamese–English code-switching fails on terms ([ViMedCSS](https://arxiv.org/pdf/2602.12911)). |

## Decisions

1. **A new capability `io.memoryos.meeting` and a top-level menu `Cuộc họp` (`/meetings`, `/meetings/$meetingId`).** It depends on `iam`, `chat` (voice connections, model resolution), `document` and `usage`. `ARCHITECTURE.md`, the capability table and the module list in `AGENTS.md` change in the same pull request that adds the module.
2. **Web first; desktop later on the same protocol.** Chrome/Edge desktop capture the tab (and whole-system audio on Windows and on macOS 14.2+ with Chrome 141+); other browsers and phones use the microphone only, and say so before starting.
3. **Two tracks, not a mix, for online meetings.** `MIC` is labelled as the owner; `TAB` is diarized into other speakers named from the meeting's participants. This addresses the most common complaint about bot-less tools at roughly twice the speech cost. In person there is one `MIC` track that hears everyone in the room, so it is diarized and no speaker is assumed to be the owner.
3a. **One provider stream per track.** Soniox has no native multi-channel mode, so an online meeting opens one stream for `MIC` and one for `TAB`, each with its own diarization, exactly as Anarlog does (`owhisper-client` `build_dual`, `supports_native_multichannel() = false`). Speaker ids are per stream, 1-based strings; online, the `MIC` stream's speakers all map to the owner.
3b. **Reconnect keeps time continuous.** The meeting handler keeps at most 5 s of PCM per track, reopens the provider stream after a failure with 2/5/10/20/30 s backoff, replays the retained audio, and shifts the new stream's `start_ms`/`end_ms` by `elapsed − replayed` (Anarlog `session/supervisor.rs`). ghiam-pro restarts timestamps on every reconnect; that is the bug this avoids. A stream with speech but no transcript progress for 30 s is reconnected (Anarlog stall watchdog).
4. **Audio is relayed through MemoryOS.** The key never reaches the browser and usage is measured server-side in seconds per track. A meeting has its own WebSocket handler: the dictation path's 10-minute, 25 MiB and hold-all-audio-in-memory design does not fit meetings. The meeting handler holds no audio beyond a short reconnect buffer.
5. **Utterances are persisted as they finalize.** `meeting_utterance` rows keep milliseconds, track, speaker and confidence. A dropped connection loses seconds, not minutes; a closed tab can be resumed.
6. **Meetings use the Tenant's existing voice connections through one meeting-transcription adapter, plus Soniox.** The meeting stream talks to a `MeetingTranscriber` port with one adapter per `VoiceProvider`: the existing `OPENAI`, `OPENAI_COMPATIBLE`, `ELEVENLABS` and `AZURE` connections, and a new `SONIOX` provider (STT only, realtime, diarization, `context` terms) added to `/admin/voice`. The administrator chooses which connection records meetings; no separate benchmark gates the work. Each adapter declares whether it diarizes: without diarization the `TAB` track is one speaker ("Người khác") while `MIC` stays the owner, so the two-track split still separates the owner from everyone else.
7. **Minutes are a worker job.** A db-scheduler job with `PENDING/RUNNING/READY/FAILED`, lease and attempts, like the usage report job. New `ModelFlow.MEETING_MINUTES` and `AiUsageFlow` values; model resolution without a chat session; structured output. Every generated item references its source utterances; a gap in the transcript is stated, never filled in.
8. **Private by default; searchable in two steps.** Phase 1: visible only to the owner in `/meetings` and usable from a Chat conversation opened on the meeting. Phase 2: an owner filter in retrieval so meeting notes appear in the owner's Search and `search_knowledge` (today user files are excluded at `OpenSearchIndexService`). No public links, no automatic sharing, no model training.
9. **Consent is part of the flow.** Start requires the confirmation; a copyable notice is offered; the Tenant administrator enables the feature for everyone, administrators only, or no one; transcript retention follows the chat retention policy.
10. **No audio is kept.** Live audio is never stored. An uploaded recording (decision 13) is held only until its transcription finishes or fails, then deleted. Playback of the original audio is out of scope.
11. **A finished meeting becomes an owner-private file.** When the minutes are `READY`, the meeting publishes one Markdown rendering (title, participants, summary, decisions, action items, private notes, named transcript) as a `chat_user_file` owned by the meeting owner, through the same upload path as `ChatLibraryService.copy`. It then appears in `/library`, can be attached to a conversation or Project, and is read by Chat's file tools with the existing owner checks. Renaming a speaker, editing notes or regenerating minutes republishes it, replacing the previous upload. Publishing through `DocumentCommandPort.publish` directly is rejected: it bypasses upload ownership and would be treated as unreferenced.
13. **Three ways to capture, as in ghiam-pro plus tab audio.** *Online meeting* (microphone + shared tab), *In person* (microphone only, phones included), and *Upload a recording* (`.mp3`, `.wav`, `.m4a` from a phone or dictaphone, transcribed in batch with diarization and then deleted). ghiam-pro offers live microphone recording and file upload; the tab track is the addition that captures remote participants.
14. **Questions during the meeting come in phase 2.** *Catch me up* and meeting-scoped questions need a live model call and their own model flow; phase 1 ships the transcript, notes and minutes.

## Security

Every meeting route and job checks that the actor owns the meeting in the active Tenant; a foreign meeting answers 404. Tickets are single-use and bound to one meeting. Logs, traces and metrics carry counts and durations, never transcript text, notes, names or prompts.

## Out of scope

Meeting bots joining calls; storing or playing back audio; shared team meetings; calendar-triggered detection in the browser; cross-meeting task tracking (MEM-92); the desktop app (phase 3).
