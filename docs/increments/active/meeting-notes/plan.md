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
- [x] **Minutes job.** `ModelFlow.MEETING_MINUTES`, `AiUsageFlow.MEETING_MINUTES` and their CHECK migrations; session-less model resolution; an API job with lease and attempts; structured summary, decisions and action items with source utterances, plus the meeting type and a summary depth; prompts learned from ghiam-pro (explicit assignments only, a source quote per task, administrative wording), Nojoin (never invent, one named owner or unassigned) and silent-notetaker (an action without an owner becomes a key point).
- [ ] **Tests.** Owner isolation (404 for a foreign meeting), utterance persistence across reconnect, silent-track detection, minutes job retry and failure, usage recording, web tests for capture states and speaker naming.
- [x] **Docs.** `docs/specs/meeting.md`, `docs/tests/meeting.md`, `ARCHITECTURE.md` module graph and table, `AGENTS.md` module list and active increment entry.

## Phase 2 — minutes quality, export and search

Reading the transcript back:

- [ ] Search inside a transcript: highlighted matches and a match count, with next/previous.
- [ ] Star a line and filter to starred lines only (ghiam-pro's highlight, which also scopes what the AI reads).
- [ ] Show the provider's low confidence: a wavy underline on the span and a percentage, from the confidence already stored.
- [ ] A topic timeline that jumps to its utterance.

Minutes to send:

- [x] Vietnamese administrative minutes (*biên bản*) and Word export, built in Java with Apache POI XWPF, which the repository already carries for spreadsheets. The export is deterministic, testable and free of a model call; `run_python` stays for one-off formats a person asks Chat for. Layout follows Nghị định 30/2020 Mẫu 1.9 (Quốc hiệu/Tiêu ngữ, tên cơ quan, thời gian và địa điểm, I. Thành phần, II. Nội dung, III. Kết luận, IV. Nhiệm vụ, chữ ký chủ tọa and thư ký), which binds state bodies only — a private company such as Tasco follows it by convention, so the fields stay editable before export. A second template for *giao ban* and one for HĐQT/ĐHĐCĐ (Luật Doanh nghiệp 2020 điều 150/158 require the vote tallies and percentages) come only if asked for.
- [ ] Uploading a company's own `.docx` template is deferred: no notetaker ships it (0 of 20 surveyed; Glean states its format is fixed), and it needs Word content controls plus office-stamper or docx4j to survive Word splitting a placeholder across runs. Revisit when Tasco supplies a real ISO biểu mẫu.

Vietnamese transcript quality:

- [ ] Transcript correction suggestions with review and undo, using the span logic learned from ghiam-pro and the user glossary.
- [ ] Dialect mappings (local word to standard word), applied with the correction pass.

Asking and finding:

- [ ] *Catch me up* and meeting-scoped questions during and after the meeting (own model flow, answers from the transcript only, with timestamps).
- [ ] Owner filter in retrieval so meeting notes appear in the owner's Search and `search_knowledge`.
- [ ] Speaker-name suggestions from participants and self-introductions, offered for confirmation with the evidence quote, never applied silently.

## Phase 3 — tasks and desktop

- [ ] Cross-meeting action items (MEM-92).
- [ ] Desktop companion: system-audio capture, meeting detection and calendar prompts, on the phase-1 protocol.
