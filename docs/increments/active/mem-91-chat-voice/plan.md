# MEM-91 plan

Design: [design.md](design.md). Tham chiếu: [onyx-voice-reference.md](onyx-voice-reference.md). Branch: `anhnd05122004/mem-91-voice-cho-chat-stt-tts-voice-mode-va-catalog-provider-giong`.

**Viết tắt đường dẫn:**

| Viết tắt | Đường dẫn |
| --- | --- |
| `core:` | `core/src/main/java/io/memoryos/` |
| `core-test:` | `core/src/test/java/io/memoryos/` |
| `api:` | `api/src/main/java/io/memoryos/api/` |
| `api-test:` | `api/src/test/java/io/memoryos/api/` |
| `mig:` | `core/src/main/resources/db/migration/` |
| `web:` | `web/src/` |

**Ký hiệu:** `[x]` đã làm và có test; `[ ]` chưa làm.

## Giả định đang áp dụng

Người thực hiện yêu cầu triển khai ngay ngày 15/09/2026, nên các giả định dưới đây đang được dùng. Đổi giả định nào thì sửa design.md và mục liên quan trước.

| Câu hỏi | Giả định | Trạng thái |
| --- | --- | --- |
| Q2 — Nơi cấu hình | Bảng riêng `chat_voice_connection` theo Onyx và tiền lệ Web/Image | Đã triển khai; còn phải báo người tạo issue |
| Q3 — OpenAI server VAD | Bật nếu spike với key thật đạt; không đạt thì giữ đúng Onyx | Chưa có key; OpenAI tạm dùng đường chunked |
| Q1 — Azure | Chỉ REST; streaming Azure để sau | Chưa làm |

## Cách chia PR

Mỗi PR đưa vào main một năng lực dùng được thật. Không merge trang cấu hình khi chưa có nơi dùng ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)).

| PR | Giai đoạn | Người dùng có gì sau khi merge |
| --- | --- | --- |
| A | 1 + 2 | Model manager cấu hình provider; thành viên nói để nhập trong Chat và Search; Auto-Send |
| B | 3 | Nút đọc thành tiếng, tốc độ phát |
| C | 4 | Auto-Playback, chữ chạy theo tiếng, auto-listen |
| D | 5 | ElevenLabs, Azure |

## Giai đoạn 0 — Spike và chốt

- [x] **S0.1** Giả định Q1–Q3 được áp dụng theo yêu cầu triển khai. [ ] Comment Linear báo người tạo issue về Q2 (chỉ đăng khi được đồng ý).
- [x] **S0.2 (fixture)** Spring AI transcription gửi multipart có `filename="audio.wav"`, `model`, `language` và bearer tới endpoint OpenAI-protocol loopback; lỗi provider không lộ payload (`VoiceTranscriptionServiceTest`). Kiểm credential bằng `GET /models` (`VoiceProviderClientTest`).
- [ ] **S0.2 (thật)** Kiểm với OpenAI thật và một server OpenAI-compatible (Speaches): xử lý `/v1`, định dạng phản hồi, `speed`, giới hạn 4096 ký tự, chi phí dựng client.
- [ ] **S0.3** OpenAI Realtime GA transcription với server VAD (cần key).
- [x] **S0.4** Thay bằng kho vé trong bộ nhớ tiến trình: tiêu vé nguyên tử bằng `ConcurrentHashMap.remove`, handshake qua chain `/api/**` (`VoiceTicketStoreTest`, test WebSocket tích hợp).
- [x] **S0.5 (runtime giả)** `DictationAdapter`: final phát trước khi `stop()` settle, `status` cập nhật tại chỗ, hủy khi đang khởi động (`memoryos-dictation-adapter.test.ts`).
- [x] **S0.5 (composer thật)** Playwright `voice-dictation.spec.ts` chạy adapter trong composer Chat thật: interim, final, Auto-Send.
- [ ] **S0.6** Trình duyệt: AudioWorklet không có output dưới CSP hiện tại; MSE `audio/mpeg` với `media-src 'self' blob:`; Chrome, Firefox, Safari macOS/iOS.

## Giai đoạn 1 — Provider và `/admin/voice` (PR A)

### Backend

- [x] `mig:V63__chat_voice_connections.sql` (đánh số lại từ V59 khi merge `main`, vì `main` đã dùng V59–V62): một dòng mỗi provider, CHECK provider có tên, CHECK active phải có model/giọng, partial unique `stt_active`/`tts_active`.
- [x] `core:chat/voice/VoiceProvider.java`, `VoiceFunction.java`.
- [x] `core:chat/voice/VoiceConnectionService.java`:
  - list, probe (key nháp), save (KEEP/REPLACE/REMOVE, kích hoạt khi tạo mới), delete, select (kèm model TTS), forTest, resolve;
  - `MODELS_MANAGE` và Tenant lock; revision.
- [x] `core:chat/voice/VoiceProviderClient.java`: kiểm credential `GET {base}/models`, không theo redirect, giới hạn body, tối đa 2 kiểm tra đồng thời, meter.
- [x] `core:chat/persistence/VoiceConnectionEntity.java`, `VoiceConnectionRepository.java`.
- [x] `api:chat/VoiceConnectionController.java` và `api:chat/contract/Voice{Availability,Provider,Connection}*`, `VoiceSelectionRequest`.
- [x] `openapi.yml` (`OpenApiContractTest`) và client hey-api `web:lib/hey-api/*` được tái tạo.
- [x] **Test:**
  - `core-test:chat/voice/VoiceProviderClientTest` (5 ca).
  - `api-test:chat/ChatSessionApiIntegrationTest.voiceConnectionsVerifyBeforeStoringAndKeepOneDefaultPerFunction`: key sai không lưu gì, mã hóa, kích hoạt khi tạo mới, chọn TTS kèm model, revision cũ, Test, provider chưa có, thiếu key, partial unique index, xóa, availability cho thành viên.

### Frontend

- [x] Nghiên cứu Mobbin (Braintrust, Retool, Vercel, Adaline, Vapi, ChatGPT, Mistral, WhatsApp…); tham chiếu và quyết định trình bày trong [ui-references.md](ui-references.md).
- [x] `web:routes/_authenticated.admin.voice.tsx`; mục "Giọng nói" trong `web:routes/_authenticated.admin.tsx` và `web:components/app-shell/app-shell.tsx` (`MODELS_MANAGE`).
- [x] `web:features/voice/voice-admin-page.tsx`, `voice-provider-card.tsx` (ngắt kết nối bằng `ConfirmDialog`, không cần file dialog riêng), `voice-provider-dialog.tsx`, `voice-providers.ts`, `use-voice-availability.ts`; `web:components/ui/dialog.tsx` từ registry shadcn.
- [x] i18n vi/en trong `web:i18n/app-translations.ts`; mã lỗi `voiceProviderUnavailable` trong `en.ts`/`vi.ts`.
- [x] **Test:**
  - `voice-admin-page.test.tsx` (4 ca): kết nối lần đầu thành mặc định qua lần lưu có kiểm key; đổi mặc định và chỉ xóa sau khi xác nhận; key bị từ chối thì giữ hộp thoại với copy theo mã, không lộ văn bản provider; không có quyền thì không đọc cấu hình.
  - Playwright `voice-administration.spec.ts`: 1440/390 px, không tràn ngang; người không có quyền không gọi API cấu hình.

## Giai đoạn 2 — Nhập bằng giọng nói (PR A)

### Backend

- [x] `spring-boot-starter-websocket` (`gradle/libs.versions.toml`, `api/build.gradle.kts`).
- [x] `mig:V64__chat_voice_settings.sql` (trước là V60); `core:chat/voice/VoiceSettings.java`, `VoiceSettingsService.java`; `core:chat/persistence/JdbcVoiceSettingsRepository.java` (partial update nguyên tử).
- [x] `core:chat/voice/Pcm16.java`, `Transcript.java`, `ChunkedTranscriber.java`, `VoiceTranscriptionService.java`.
- [x] `api:chat/VoiceTicketStore.java`, `VoiceSessionController.java` (`/tickets`, `/settings`), `VoiceHandshakeInterceptor.java`, `TranscribeWebSocketHandler.java`, `VoiceWebSocketConfiguration.java`; contract `VoiceTicketResponse`, `VoiceSettings{Request,Response}`.
- [x] `web/nginx.conf`: location WebSocket nhập bằng giọng nói. `web/vite.config.ts`: `ws: true`.
- [ ] Adapter live OpenAI Realtime (chờ S0.3) và interface phiên live chung khi có implementation thứ hai.
- [x] **Test:**
  - `core-test:chat/voice/Pcm16Test` (4 ca), `ChunkedTranscriberTest` (4 ca), `VoiceTranscriptionServiceTest` (2 ca).
  - `api-test:chat/VoiceTicketStoreTest` (3 ca).
  - `ChatSessionApiIntegrationTest.voiceTranscriptionStreamsInterimAndFinalTextOverATicketedSameOriginWebSocket`: bearer thật, interim → final → đóng bình thường, vé đã dùng bị từ chối, handshake khác origin bị từ chối.
  - `ChatSessionApiIntegrationTest.voiceSettingsArePrivatePartialAndBounded`.

### Frontend

- [x] **Logic** `web:features/voice`:
  - `capture/pcm-capture.worklet.ts`, `capture/audio-capture.ts`;
  - `transcribe-socket.ts`, `voice-dictation.ts`, `memoryos-dictation-adapter.ts`;
  - Vitest 11 ca.
- [x] **Giao diện** (`web:features/voice/chat-dictation-controls.tsx`):
  - Mic cạnh Send (`ComposerPrimitive.Dictate`), disabled khi đang trả lời; model manager chưa có provider thấy link tới `/admin/voice`.
  - Dải ghi âm (`ComposerPrimitive.StopDictation`): chấm đỏ, đồng hồ, mức âm lượng thật, tắt mic; placeholder "Đang nghe…"; lỗi đã dịch có nút đóng.
  - `chat-composer.tsx` chặn Send và Enter khi đang dictation; `ChatDictationAutoSend` gửi sau final khi bật Auto-Send.
  - Trạng thái trình bày trong `voice-session-store.ts`; copy lỗi và vé trong `voice-failure.ts`.
- [x] `web:features/chat/chat-runtime-provider.tsx`: `use-chat-dictation-adapter.ts` chỉ trả adapter khi `sttAvailable`.
- [x] `web:features/search/search-page.tsx`: bỏ `SpeechRecognition`, dùng `voice-dictation.ts`; mic ẩn khi Tenant chưa có STT; cập nhật `search-page.test.tsx`.
- [x] `web:features/identity/general-settings-page.tsx`: `voice-settings-section.tsx`, switch "Tự động gửi khi dừng ghi âm".
- [x] `capture/audio-capture.ts`: resume `AudioContext` bị suspend sau hộp thoại quyền mic.
- [x] **Test:**
  - `voice-session-store.test.ts` (2 ca); `memoryos-dictation-adapter.test.ts` kiểm thêm thứ tự sự kiện.
  - Playwright `voice-dictation.spec.ts`: mic giả của Chromium qua AudioWorklet thật, WebSocket bằng `page.routeWebSocket`. Kiểm interim, Send bị chặn, vé có header CSRF, tắt/bật mic, final, Auto-Send, không có mic khi chưa có STT.

### Bằng chứng web (15/09/2026)

- `tsc -b`, `oxlint --deny-warnings`, `oxfmt`, `check:i18n`: sạch.
- Vitest voice, search, identity, chat, i18n, components: 34 file, 187 ca đạt.
- Vitest toàn bộ: 288/289; ca lỗi `source-history-presentation.test.tsx` so sánh `toLocaleString(undefined, …)` theo locale máy, không liên quan thay đổi này (không file nào trong `features/sources` bị sửa).
- Playwright `voice-administration.spec.ts` và `voice-dictation.spec.ts`: 6/6 đạt.
- `gradlew clean check` toàn repo chưa có kết quả mới: lần chạy 15/09/2026 bị dừng giữa `:core:test` vì máy còn khoảng 0,5 GB RAM. Backend không đổi từ lần kiểm trước (voice core 15/15, API voice và `OpenApiContractTest` đạt; lỗi `ChunkedTranscriberTest` đã sửa). Cần chạy lại trước khi mở PR.

### Tài liệu trong PR A

- [x] `design.md`, `plan.md` cập nhật theo phần đã triển khai.
- [ ] `docs/specs/chat.md` (mục Voice), `docs/specs/chat-models.md` (liên kết), `docs/tests/chat.md`, `ARCHITECTURE.md`, `verification.md`.

## Giai đoạn 3 — Đọc thành tiếng (PR B)

- [x] `core:chat/voice/VoiceSynthesisService.java`:
  - `CHAT_READ`; văn bản 1–32 000 ký tự, tốc độ 0,5–2,0; tối đa 8 luồng đồng thời mỗi tiến trình.
  - Tách đoạn ≤ 4096 tại hết câu, rồi khoảng trắng, không cắt đôi cặp surrogate.
  - Spring AI `OpenAiAudioSpeechModel.stream` nối các đoạn theo thứ tự; lấy chunk đầu trước khi trả header, nên lỗi provider vẫn là 503 có mã.
  - Meter `operation=synthesize` với outcome `succeeded|failed|cancelled`.
- [x] `api:chat/VoiceSynthesisController.java`: `POST /api/chat/voice/synthesize`, `StreamingResponseBody` `audio/mpeg`, `Cache-Control: no-store`, `X-Accel-Buffering: no`; contract `VoiceSynthesisRequest` che văn bản trong `toString`.
- [x] `web/nginx.conf`: CSP `media-src 'self' blob:`.
- [x] **Web:**
  - `speech-text.ts`: `unified` + `remark-parse` + `remark-gfm`, bỏ code, ảnh, link đích và citation `[1]`–`[24]`.
  - `audio-player.ts`: MediaSource `sequence`, Blob khi không có MSE, một lần phát mỗi lúc.
  - `memoryos-speech-adapter.ts`, `use-chat-speech-adapter.ts` (chỉ khi `ttsAvailable`, tốc độ từ cài đặt), `chat-read-aloud.tsx` (`ActionBarPrimitive.Speak/StopSpeaking`: đọc, đang tải, dừng).
  - Lỗi đọc dùng chung thông báo trên composer (`ChatVoiceFailure`).
  - `voice-settings-section.tsx`: slider "Tốc độ đọc" (`shadcn add slider`), lưu khi thả.
- [x] **Test:**
  - `VoiceSynthesisServiceTest` (4 ca): tách đoạn, stream đúng thứ tự với model/giọng/tốc độ, lỗi trước audio không lộ payload và trả slot, đóng sớm hủy.
  - `ChatSessionApiIntegrationTest.voiceSynthesisStreamsTheDefaultProviderAudioAfterValidation`: 503 khi chưa có TTS, 400 văn bản rỗng và tốc độ ngoài khoảng, 403 thiếu CSRF, audio và header đúng, provider nhận đúng input/model/voice/speed.
  - Vitest `speech-text`, `audio-player`, `memoryos-speech-adapter`, `voice-settings-section`.
  - Playwright `voice-read-aloud.spec.ts`: câu trả lời thật từ fixture, request có CSRF và văn bản đã bỏ markdown, phát MP3 qua MediaSource của Chromium tới hết, dừng giữa chừng.

**Bằng chứng giai đoạn 3 (15/09/2026):**
- Gradle `:core:test --tests io.memoryos.chat.voice.*`: đạt.
- Gradle `:api:test` với `OpenApiContractTest` (có `MEMORYOS_OPENAPI_WRITE=true`) và `ChatSessionApiIntegrationTest.voice*`: BUILD SUCCESSFUL; `openapi.yml` và client hey-api được tạo lại.
- Web: `tsc -b`, `oxlint`, `oxfmt`, `check:i18n` sạch; Vitest voice, chat, identity, search, i18n, components: 37 file, 195 ca, cộng `voice-settings-section.test.tsx` 3 ca, đều đạt.
- Playwright `voice-administration`, `voice-dictation`, `voice-read-aloud`: 7/7 đạt.
- `gradlew clean check` toàn repo vẫn chưa chạy lại được vì thiếu RAM.

## Giai đoạn 4 — Hội thoại rảnh tay (PR C)

- [x] **Backend:**
  - `core:chat/voice/StreamingSynthesizer.java`:
    - các phần văn bản được đọc tuần tự trên một worker; tối đa 4096 ký tự mỗi phần, 32 000 ký tự mỗi phiên;
    - lỗi provider thành `CHAT_PROVIDER_UNAVAILABLE`; đóng thì hủy request đang chạy.
  - `VoiceSynthesisService.openStreaming` dùng chung 8 slot với REST.
  - `api:chat/SynthesizeWebSocketHandler.java` `/api/chat/voice/synthesize/stream`:
    - `config` (tùy chọn, trước mọi text) → `synthesize` → `end`;
    - server trả MP3 binary, `audio_done` và `error` có mã (`VOICE_INVALID_MESSAGE`, `VOICE_TEXT_TOO_LONG`, `VOICE_PROVIDER_FAILED`, `VOICE_BUSY`, `VOICE_UNAVAILABLE`, `VOICE_IDLE` sau 5 phút, `VOICE_SESSION_TOO_LONG` sau 10 phút).
  - Vé có mục đích (`VoiceTicketPurpose`, `VoiceTicketRequest`): handshake kiểm đúng mục đích và quyền (nghe: `CHAT_WRITE` hoặc `SEARCH_READ`; đọc: `CHAT_READ`). Helper chung `VoiceSockets`.
  - `web/nginx.conf`: một location regex cho cả hai WebSocket voice.
- [x] **Web:**
  - `tts-chunker.ts` (thuật toán Onyx), `synthesize-socket.ts`, `auto-playback.ts` (một lượt mỗi tab; `finished` chỉ khi audio phát hết), `use-chat-auto-playback.ts`.
  - `chat-auto-playback.tsx`:
    - `ChatAutoPlayback`: chỉ đọc câu trả lời bắt đầu stream sau khi mở hội thoại; dừng khi hủy câu trả lời, đổi hội thoại hoặc tắt cài đặt;
    - `ChatSpeakingIndicator`: trạng thái, tắt tiếng, dừng;
    - `ChatAutoListen`: bật mic sau 400 ms khi đọc hết, chỉ khi thành viên đã bấm mic tay trong 5 phút.
  - Composer: placeholder "MemoryOS đang đọc…", mic bị khóa khi đang đọc; câu trả lời đang tự đọc không có nút đọc thành tiếng; đọc tay hoặc lượt mới dừng lượt tự đọc.
  - Cài đặt: switch "Tự động đọc câu trả lời".
- [ ] Chữ chạy theo tiếng: chưa làm; câu trả lời vẫn hiện theo stream chữ.
- [x] **Test:**
  - `StreamingSynthesizerTest` (4 ca); `VoiceSynthesisServiceTest` thêm stream qua provider loopback; `VoiceTicketStoreTest` thêm vé theo mục đích.
  - `ChatSessionApiIntegrationTest.voiceSynthesisStreamReadsAnswerPartsOverATicketedWebSocket`: vé nghe không mở được socket đọc; tốc độ, hai phần, audio đúng thứ tự, `audio_done`, đóng NORMAL.
  - Vitest `tts-chunker` (4), `synthesize-socket` (4), `auto-playback` (4).
  - Playwright `voice-conversation.spec.ts`: nói → tự gửi → tự đọc trong lúc stream (bỏ code) → mic tự bật lại; dừng đọc bằng tay thì mic không bật lại.

**Bằng chứng giai đoạn 4 (16/09/2026):**
- Gradle `:core:test --tests io.memoryos.chat.voice.*`: đạt.
- Gradle `:api:test` với `VoiceTicketStoreTest`, `OpenApiContractTest` (`MEMORYOS_OPENAPI_WRITE=true`) và `ChatSessionApiIntegrationTest.voice*`: BUILD SUCCESSFUL. `openapi.yml` và client hey-api được tạo lại.
- Web: `tsc -b`, `oxlint`, `oxfmt`, `check:i18n` sạch.
- Vitest voice, chat, search, identity, i18n, components: 41 file, 210 ca. Lần đầu có 1 ca `synthesize-socket` lỗi vì `ArrayBuffer` của test khác realm; đã sửa cách nhận frame nhị phân, chạy lại thư mục voice 12 file 40 ca đạt.
- Playwright bốn spec voice: lần đầu 8/9. Ca hands-free kiểm số socket trước khi socket kịp mở; đã đổi sang poll, chạy lại `voice-conversation.spec.ts` 2/2 đạt.
- `gradlew clean check` toàn repo vẫn chưa chạy lại vì thiếu RAM.

## Giai đoạn 5 — ElevenLabs và Azure (PR D)

- [ ] Migration nới CHECK provider cho từng adapter hoàn thành.
- [ ] **ElevenLabs:** kiểm credential, Scribe REST, Scribe realtime (final cộng dồn, ngôn ngữ theo UI), TTS REST và `stream-input`.
- [ ] **Azure (REST):** Target URI và region, Spoken Languages, `voices/list`, STT short-audio, TTS SSML có escape; cột cấu hình riêng.
- [ ] **Test:** fixture loopback cho từng adapter; UI admin theo provider.

## Giai đoạn 6 — Nghiệm thu và đóng increment

- [ ] Staging có nginx WebSocket, CSP, cấu hình voice.
- [ ] Nghiệm thu provider thật do chủ sản phẩm chạy (OpenAI, OpenAI-compatible nội bộ, ElevenLabs/Azure nếu có key).
- [ ] Mic thật trên Chrome/Safari; ảnh desktop/mobile, sáng/tối.
- [ ] Khi PR cuối merge: chuyển increment sang `completed/`, cập nhật roadmap và `AGENTS.md`.

## Hoàn thành cho mỗi PR

- `gradlew.bat clean check --no-daemon` và `pnpm --dir web check` xanh.
- JetBrains inspection sạch trên file đã đổi.
- `openapi.yml` và client hey-api được tái tạo trong cùng PR; không drift.
- vi/en đủ.
- Không có transcript, văn bản TTS hay lỗi provider trong log/trace; lỗi trả client có mã.
- Chạy thử trên stack cục bộ cả đường bị từ chối lẫn đường thành công, với người dùng OIDC tạm; dọn dữ liệu tạm sau đó.
- Spec, ma trận test, ARCHITECTURE và `verification.md` phản ánh đúng phần đã triển khai.

## Rủi ro và cách giảm

| Rủi ro | Cách giảm |
| --- | --- |
| Schema OpenAI Realtime GA thay đổi | S0.3; fixture test ghim message; lỗi có kiểu thay vì fallback âm thầm |
| Vé trong bộ nhớ một tiến trình API | Ghi trong spec; nhiều tiến trình cần sticky session hoặc kho vé dùng chung |
| iOS Safari không có MediaSource | Nút đọc dùng Blob; Auto-Playback báo không khả dụng |
| Chữ chạy theo tiếng khó map với markdown/citation | Tách riêng `text-reveal.ts` có test; fallback 5 s |
| API speech/dictation của assistant-ui còn thử nghiệm | Ghim 0.15.18; test adapter theo contract; kiểm composer thật (S0.5) |
| AudioWorklet không output có thể không chạy ở vài trình duyệt | S0.6; nếu cần, nối qua `GainNode` tắt tiếng |
| Chi phí provider do Auto-Playback | Meter theo provider/operation/outcome; quota nằm ngoài phạm vi |
| Playwright với mic và WebSocket | Chromium fake media, WebSocket mock; mic thật kiểm tay ở giai đoạn 6 |
