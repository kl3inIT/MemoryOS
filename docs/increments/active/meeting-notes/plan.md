# MEM-92 plan — meeting notes

Design: [design.md](design.md). Each phase is its own pull request.

## Phase 1 — record, transcribe, summarize

- [x] **Meeting transcription adapter.** `chat.voice` `LiveTranscriptionService` (named interface `voice`) over the Tenant's default speech-to-text connection: Soniox live with diarization, endpoints, context terms, keepalive and Anarlog-style reconnect/replay/offset; every other provider through pause-cut utterances and its REST adapter. Meetings use the Tenant's default connection rather than a separate selection.
- [x] **Soniox provider.** `VoiceProvider.SONIOX` (STT only, `speech` flag on the provider response), V94 widening `ck_chat_voice_connection_provider`, realtime adapter for dictation, async adapter, key verification, `/admin/voice` card listed only under speech to text. Meeting diarization and `context` terms belong to the meeting adapter.
- [x] **Evidence (Soniox provider).** 2026-09-21: `SonioxRealtimeTranscriberTest`, `SonioxAsyncTest`, the voice unit suite, `OpenApiContractTest`, the voice-connection API integration test and the voice web tests pass; typecheck, oxlint and oxfmt clean. A live probe with an invalid key confirmed the realtime URL, first-message format and the `error_code` error shape the adapter handles, and the REST 401 the key check maps.
- [x] **Meeting module.** `io.memoryos.meeting` with V95 `meeting` (notes held on the meeting row), `meeting_speaker`, `meeting_utterance`; owner-only `MeetingService`; REST list, create, read, name speaker, notes, end, delete, tickets.
- [ ] **Tenant setting** (all / administrators / off).
- [x] **Streaming.** Ticket scope `MEETING:{id}:{track}`; `/api/meeting-stream` per track with offset; utterances stored as committed; provider reconnect with replay; usage in seconds per track. Browser-side reconnect and resume after a closed tab belong to the capture slice.
- [x] **Browser capture.** Microphone + `getDisplayMedia` tab audio as two PCM16 tracks through the existing worklet; per-track level meters; silent-tab and stopped-share warnings; unsupported-browser notice with microphone-only fallback; `beforeunload` confirmation.
- [ ] **Upload a recording.** Upload `.mp3`/`.wav`/`.m4a` to object storage, batch transcription through the chosen connection's adapter, utterances stored like a live meeting, audio object deleted on completion or failure.
- [x] **Web UI.** Sidebar entry `Cuộc họp`; list grouped by date with status; new-meeting dialog with consent; meeting page with recording bar, live transcript, private notes, speaker naming and stop confirmation; result tabs (summary, decisions, action items, transcript) (follow the mock).
- [ ] **Publish to the library.** Render the finished meeting as Markdown and publish it as an owner-private `chat_user_file`; republish on speaker, notes or minutes changes; *Open in Chat* attaches it to a new conversation.
- [ ] **Minutes job.** `ModelFlow.MEETING_MINUTES`, `AiUsageFlow` value and CHECK migrations; session-less model resolution; worker job with lease and attempts; structured summary, decisions and action items with source utterances; prompts learned from ghiam-pro, rewritten for MemoryOS.
- [ ] **Tests.** Owner isolation (404 for a foreign meeting), utterance persistence across reconnect, silent-track detection, minutes job retry and failure, usage recording, web tests for capture states and speaker naming.
- [ ] **Docs.** `docs/specs/meeting.md`, `docs/tests/meeting.md`, `ARCHITECTURE.md` module graph and table, `AGENTS.md` module list and active increment entry.

## Phase 2 — minutes quality and search

- [ ] *Catch me up* and meeting-scoped questions during and after the meeting (own model flow, answers from the transcript only, with timestamps).
- [ ] Vietnamese administrative minutes (*biên bản*) template and Word export.
- [ ] Transcript correction suggestions with review and undo, using the span logic learned from ghiam-pro and the user glossary.
- [ ] Owner filter in retrieval so meeting notes appear in the owner's Search and `search_knowledge`.
- [ ] Speaker-name suggestions from participants and earlier meetings.

## Phase 3 — tasks and desktop

- [ ] Cross-meeting action items (MEM-92).
- [ ] Desktop companion: system-audio capture, meeting detection and calendar prompts, on the phase-1 protocol.
