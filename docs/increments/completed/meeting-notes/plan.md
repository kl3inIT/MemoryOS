# MEM-92 plan — meeting notes

> Closed 2026-09-27 by owner decision (Linear Done); open acceptance items not run:
> - **Tenant setting** (all / administrators / off) — [MEM-178](https://linear.app/memory-os/issue/MEM-178).
> - A choice between the Nghị định 30 biên bản and a shorter internal one (no quốc hiệu, số or cơ quan cấp trên) — raised in review, deferred.
> - Uploading a company's own `.docx` template is deferred: no notetaker ships it (0 of 20 surveyed; Glean states its format is fixed), and it needs Word content controls plus office-stamper or docx4j…
> - Dialect mappings (local word to standard word) — [MEM-177](https://linear.app/memory-os/issue/MEM-177): a Tenant dictionary fed to the minutes prompt, never a rewrite of the transcript.
> - *Catch me up* and meeting-scoped questions during and after the meeting (own model flow, answers from the transcript only, with timestamps) — [MEM-185](https://linear.app/memory-os/issue/MEM-185).
> - Owner filter in retrieval so meeting notes appear in the owner's Search and `search_knowledge`.
> - Cross-meeting action items — [MEM-180](https://linear.app/memory-os/issue/MEM-180). The original scope of MEM-92: one page gathering the work every meeting handed out, an owner who is an account ra…
> - Desktop companion — [MEM-181](https://linear.app/memory-os/issue/MEM-181). System-audio capture, meeting detection and calendar prompts on the phase-1 protocol. Uploading a recording already covers…


Design: [design.md](design.md). Each phase is its own pull request.

## Phase 1 — record, transcribe, summarize

- [x] **Meeting transcription adapter.** `chat.voice` `LiveTranscriptionService` (named interface `voice`) over the Tenant's default speech-to-text connection: Soniox live with diarization, endpoints, context terms, keepalive and Anarlog-style reconnect/replay/offset; every other provider through pause-cut utterances and its REST adapter. Meetings use the Tenant's default connection rather than a separate selection.
- [x] **Soniox provider.** `VoiceProvider.SONIOX` (STT only, `speech` flag on the provider response), V113 widening `ck_chat_voice_connection_provider`, realtime adapter for dictation, async adapter, key verification, `/admin/voice` card listed only under speech to text. Meeting diarization and `context` terms belong to the meeting adapter.
- [x] **Evidence (Soniox provider).** 2026-09-21: `SonioxRealtimeTranscriberTest`, `SonioxAsyncTest`, the voice unit suite, `OpenApiContractTest`, the voice-connection API integration test and the voice web tests pass; typecheck, oxlint and oxfmt clean. A live probe with an invalid key confirmed the realtime URL, first-message format and the `error_code` error shape the adapter handles, and the REST 401 the key check maps.
- [x] **Meeting module.** `io.memoryos.meeting` with V108 `meeting` (notes held on the meeting row), `meeting_speaker`, `meeting_utterance`; owner-only `MeetingService`; REST list, create, read, name speaker, notes, end, delete, tickets.
- [ ] **Tenant setting** (all / administrators / off) — [MEM-178](https://linear.app/memory-os/issue/MEM-178).
- [x] **Streaming.** Ticket scope `MEETING:{id}:{track}`; `/api/meeting-stream` per track with offset; utterances stored as committed; provider reconnect with replay; usage in seconds per track. Browser-side reconnect and resume after a closed tab belong to the capture slice.
- [x] **Browser capture.** Microphone + `getDisplayMedia` tab audio as two PCM16 tracks through the existing worklet; per-track level meters; silent-tab and stopped-share warnings; unsupported-browser notice with microphone-only fallback; `beforeunload` confirmation.
- [x] **Upload a recording.** Upload `.mp3`/`.wav`/`.m4a`/`.mp4` through the existing object-storage reservation, with the connection chosen per file from a member-readable listing of the Tenant's speech-to-text connections. A leased API job hands the file to the provider in its original container and stores the segments it answers as utterances, after which the minutes run as for a live meeting. Soniox separates speakers and accepts five hours; an OpenAI-compatible connection answers one speaker and caps the file, and the dialog says so before the file is sent. The stored object is deleted when the job finishes, when it gives up and when the meeting is deleted.
- [x] **Web UI.** Sidebar entry `Cuộc họp`; list grouped by date with status; new-meeting dialog with consent; meeting page with recording bar, live transcript, private notes, speaker naming and stop confirmation; result tabs (summary, decisions, action items, transcript) (follow the mock).
- [x] **Share a meeting.** The participants field picks real members and Groups beside free-text names for people outside MemoryOS; `meeting_user_share` and `meeting_group_share` follow the shape used by Agents and Document Sets. A reader opens the meeting, watches the transcript arrive, reads the minutes and downloads the biên bản; only the owner edits or deletes.
- [x] **Publish to the library.** The owner takes the minutes into their library as Markdown on demand — never the transcript — and *Open in Chat* opens a new conversation with the file in the composer. Rewriting the minutes drops the published file so the next use republishes. A reader's own copy waits on MEM-179, where a Group's knowledge base answers without anyone attaching anything.
- [x] **Minutes job.** `ModelFlow.MEETING_MINUTES`, `AiUsageFlow.MEETING_MINUTES` and their CHECK migrations; session-less model resolution; an API job with lease and attempts; structured summary, decisions and action items with source utterances, plus the meeting type and a summary depth; prompts learned from ghiam-pro (explicit assignments only, a source quote per task, administrative wording), Nojoin (never invent, one named owner or unassigned) and silent-notetaker (an action without an owner becomes a key point).
- [x] **Tests.** Owner isolation, ticketed sockets and utterance persistence, the upload job and its refusals, sharing, the minutes job and its billing, the biên bản bytes, the published Markdown, browser capture, recorder and socket, two Playwright flows, and the nginx WebSocket routes. Listed one by one in [docs/tests/meeting.md](../../../tests/meeting.md).
- [x] **Docs.** `docs/specs/meeting.md`, `docs/tests/meeting.md`, `ARCHITECTURE.md` module graph and table, `AGENTS.md` module list and active increment entry.

## Phase 2 — minutes quality, export and search

Reading the transcript back — [MEM-183](https://linear.app/memory-os/issue/MEM-183):

- [x] Search inside a transcript: highlighted matches and a match count, with next/previous. It runs in the browser over the transcript already loaded.
- [x] Star a line and filter to starred lines only, plus a bookmark for a moment while the meeting is still running. Both belong to whoever left them. Scoping what the model reads to starred lines belongs with the minutes work ([MEM-188](https://linear.app/memory-os/issue/MEM-188)).
- [x] Show the provider's low confidence: the uncertain stretches are highlighted in the transcript, with the percentage on hover. Soniox reports a confidence per token, so the mark is the word rather than the line; every other provider reports none and marks nothing.
- [x] A topic timeline that jumps to its utterance, named in the same model call as the minutes — [MEM-188](https://linear.app/memory-os/issue/MEM-188).
- [x] Small changes answer with what they changed (audit item 1.4, approved 2026-09-24): notes, the name and people, a speaker, stars, bookmarks, readers, a minutes item and the summary no longer re-read and send every utterance, and the page folds the answer into the meeting it holds. Creating, ending, rerunning, recordings and transcript corrections still answer the whole meeting. The contract is in [the spec](../../../specs/meeting.md#what-a-change-answers). Evidence 2026-09-24: `:core:test --tests 'io.memoryos.meeting.*'`, the six meeting methods of `ChatSessionApiIntegrationTest`, `MeetingStreamWatchdogTest` and `OpenApiContractTest` pass; web typecheck, oxlint, oxfmt and `vitest run src/features/meetings` clean. Playwright was not run locally.
- [x] One transcript correction answers with what it changed (audit item 1.4, extended 2026-09-25): accepting, keeping or reverting one proposal and writing a word by hand answer the line they rewrote and the proposal, read back by the `UPDATE … RETURNING` that wrote them, and the page folds both into the meeting and the proposals it holds. Accept-all and revert-all still answer the whole meeting, since they rewrite any number of lines. Ticking and rewriting a minutes topic now answer 404 like taking one out. Evidence 2026-09-25: `:core:test --tests 'io.memoryos.meeting.*'`, `OpenApiContractTest` and the four correction and minutes methods of `ChatSessionApiIntegrationTest` pass; web typecheck, oxlint, oxfmt, `check:api` and `vitest run src/features/meetings` clean. Playwright was not run locally.

Minutes to send:

- [x] The minutes are the owner's to correct: the summary, each decision and each piece of work, with the model's
  own words kept as events beside them, and a rerun that asks before discarding that work —
  [MEM-188](https://linear.app/memory-os/issue/MEM-188).

- [x] Vietnamese administrative minutes (*biên bản*) and Word export, built in Java with Apache POI XWPF, which the repository already carries for spreadsheets. The export is deterministic, testable and free of a model call; `run_python` stays for one-off formats a person asks Chat for. Layout follows Nghị định 30/2020 Mẫu 1.9 (Quốc hiệu/Tiêu ngữ, tên cơ quan, thời gian và địa điểm, I. Thành phần, II. Nội dung, III. Kết luận, IV. Nhiệm vụ, chữ ký chủ tọa and thư ký), which binds state bodies only — a private company such as Tasco follows it by convention, so the fields stay editable before export. A second template for *giao ban* and one for HĐQT/ĐHĐCĐ (Luật Doanh nghiệp 2020 điều 150/158 require the vote tallies and percentages) come only if asked for.
- [x] Less to fill in: the start form asks only kind, language (remembered, as Fireflies does) and consent, with title, participants, terms and sharing behind *Thêm chi tiết*; the page renames the meeting and edits its participants later. The export dialog shows the API's own PDF beside the heading (Mercury/Acctual invoice editors on Mobbin), keeps the owner's heading on the meeting and carries the organization and typeface to the next one; the owner adds and removes decisions and work on the page. The opening of the biên bản uses labelled *Thời gian bắt đầu* / *Địa điểm* lines.
- [ ] A choice between the Nghị định 30 biên bản and a shorter internal one (no quốc hiệu, số or cơ quan cấp trên) — raised in review, deferred.
- [ ] Uploading a company's own `.docx` template is deferred: no notetaker ships it (0 of 20 surveyed; Glean states its format is fixed), and it needs Word content controls plus office-stamper or docx4j to survive Word splitting a placeholder across runs. Revisit when Tasco supplies a real ISO biểu mẫu.

- [x] Download the transcript as Word or PDF, for sending to somebody who was not there.

Vietnamese transcript quality:

- [x] Transcript correction suggestions with review and undo, using the span logic learned from ghiam-pro and the user glossary — [MEM-183](https://linear.app/memory-os/issue/MEM-183). One button proposes for every marked stretch; the owner accepts, accepts in their own words or keeps what was heard, and anything applied can be undone. Accepting the sure ones without review, behind the owner's own choice, is PR B and comes next.
- [ ] Dialect mappings (local word to standard word) — [MEM-177](https://linear.app/memory-os/issue/MEM-177): a Tenant dictionary fed to the minutes prompt, never a rewrite of the transcript.

Asking and finding:

- [ ] *Catch me up* and meeting-scoped questions during and after the meeting (own model flow, answers from the transcript only, with timestamps) — [MEM-185](https://linear.app/memory-os/issue/MEM-185).
- [ ] Owner filter in retrieval so meeting notes appear in the owner's Search and `search_knowledge`.
- [x] Speaker-name suggestions read from self-introductions by rule, offered to the owner with the line they came from and never applied silently — [MEM-186](https://linear.app/memory-os/issue/MEM-186), whose second tier, ghiam-pro's voice profiles behind consent and deletion, remains open.

## Phase 3 — tasks and desktop

- [ ] Cross-meeting action items — [MEM-180](https://linear.app/memory-os/issue/MEM-180). The original scope of MEM-92: one page gathering the work every meeting handed out, an owner who is an account rather than a string, a deadline that is a date, the history of every postponement, and a Chat tool to ask about it.
- [ ] Desktop companion — [MEM-181](https://linear.app/memory-os/issue/MEM-181). System-audio capture, meeting detection and calendar prompts on the phase-1 protocol. Uploading a recording already covers most of the need; only live transcription and not having to remember are left.
