# MEM-91 — Voice verification

Design: [design.md](design.md). Plan and historical evidence: [plan.md](plan.md). Canonical matrix: [Chat tests](../../../tests/chat.md#voice-mem-91).

## OpenAI Realtime transcript — 17/09/2026

Implemented boundary:

- `TranscriptionSession` keeps the API WebSocket handler independent of native live versus chunked REST transcription.
- Public OpenAI uses `gpt-live-transcribe`, PCM16 24 kHz append, low-delay transcription deltas and manual buffer commit. Turn detection is disabled; final text does not claim an utterance boundary.
- Every MemoryOS transcript response carries a monotonically increasing `revision`. The browser ignores stale or malformed revisions while remaining compatible with the previous response shape.
- The session retains at most the existing 25 MiB connection limit in memory. Provider connection, stream, parse, close or final-timeout failure replays the recording through the batch transcriber. Audio and transcript content are not logged or persisted.

Evidence:

| Command / boundary | Result |
| --- | --- |
| `gradlew.bat :core:test --tests "io.memoryos.chat.voice.*" :api:test --tests "io.memoryos.api.chat.ChatSessionApiIntegrationTest.voiceTranscriptionStreamsInterimAndFinalTextOverATicketedSameOriginWebSocket" --no-daemon` | PASS — 10 core Voice classes / 40 tests and the focused full-context WebSocket integration test; PostgreSQL via the normal test runtime |
| `pnpm exec vitest run src/features/voice/transcribe-socket.test.ts src/features/voice/voice-dictation.test.ts src/features/voice/memoryos-dictation-adapter.test.ts` | PASS — 3 files / 12 tests |
| `pnpm typecheck` | PASS |
| `pnpm exec oxlint --deny-warnings src/features/voice/transcribe-socket.ts src/features/voice/transcribe-socket.test.ts` | PASS |
| `pnpm exec oxfmt --check src/features/voice/transcribe-socket.ts src/features/voice/transcribe-socket.test.ts` | PASS |
| `gradlew.bat :core:check :api:test --tests "io.memoryos.api.chat.ChatSessionApiIntegrationTest.voice*" --tests "io.memoryos.api.chat.VoiceTicketStoreTest" --no-daemon` | PASS — full Core check plus 5 Voice API integration and 4 ticket-store tests |
| `pnpm exec playwright test tests/e2e/voice-dictation.spec.ts tests/e2e/voice-conversation.spec.ts` | PASS — 5 Chromium cases |
| `pnpm check:routes` | PASS — production build, font assertion, TypeScript and generated route stability |

The Realtime fixture asserts the provider URI, authorization and hashed safety identifier without exposing the key; session configuration; base64 audio append; cumulative delta delivery; fragmented JSON reassembly; commit/final completion; batch replay; fallback metric; and one release. The API integration assertion covers `revision` 1/2 and `utteranceEnd:false` on interim/final messages. Browser unit coverage proves a delayed lower revision cannot regress the composer.

`pnpm check` completed generated-client stability, CI image assertion, i18n, lint, formatting and typecheck, then ran 392 unit cases. All 40 Voice tests passed; one unrelated desktop Chat file-preview timing case failed because its `<code>` node was detached. The isolated rerun of that file passed 11/11. Because the single command exited early, this is recorded as a flaky near-pass rather than a green full Web gate.

## Existing increment evidence

Provider administration, connection encryption, tickets, chunked transcription, Chat/Search dictation, user settings, read-aloud, streaming synthesis, Auto-Playback, auto-listen, ElevenLabs REST and Azure REST were verified in the dated phase sections of [plan.md](plan.md). Those earlier controlled checks remain valid; the current source must still pass the repository-wide gates below before merge.

## Open gates

- Run `gradlew.bat clean check --no-daemon` and obtain one uninterrupted green `pnpm --dir web check` on the final tree.
- Exercise public OpenAI Realtime with a real key and record latency/failure behavior. Server VAD is not enabled and is not claimed.
- Exercise the configured OpenAI-compatible, ElevenLabs and Azure resources with real credentials.
- Validate microphone, speaker, WebSocket proxy/CSP and responsive light/dark UI on staging, including Safari/macOS/iOS.
- Refresh comparative Voice UI research through the connected Mobbin MCP before another UI design change; continue to reuse the installed shadcn/Radix and assistant-ui components.

No controlled test above certifies provider availability, billing, production rollout, multi-replica ticket routing or live audio quality.
