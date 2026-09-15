# MEM-91 — Voice cho Chat

Tracking: [MEM-91](https://linear.app/memory-os/issue/MEM-91). Tham chiếu hành vi Onyx: [onyx-voice-reference.md](onyx-voice-reference.md). Kế hoạch: [plan.md](plan.md). Liên quan: [MEM-77 catalog](../mem-77-provider-backend/design.md), [MEM-97 image connection](../../completed/mem-97-chat-image-generation/design.md), [Chat Web search](../chat-web-search/design.md).

Trạng thái: **đang triển khai** (15/09/2026).
- **Đã có code và test:** giai đoạn 1–4 — voice connection, vé, WebSocket nhập bằng giọng nói, cài đặt; trang `/admin/voice`, mic trong Chat và Search, Auto-Send; đọc thành tiếng và tốc độ đọc; Auto-Playback và auto-listen; ElevenLabs và Azure AI Speech qua REST.
- **Chưa làm:** chữ chạy theo tiếng, ElevenLabs realtime (Scribe realtime, `stream-input`), OpenAI Realtime.
- **Tiến độ chi tiết:** xem [plan.md](plan.md). Tham chiếu giao diện Mobbin: [ui-references.md](ui-references.md).

**Baseline: Onyx Voice (`06aa2b0`).** Người thực hiện chọn hướng này ngày 15/09/2026: làm một tính năng giống Voice của Onyx. Bản này thay bản đề xuất trước cùng ngày. Các ý sau đã bị bỏ để theo Onyx:
- VAD phía client;
- TTS HTTP theo đoạn phát bằng Web Audio;
- nút Voice mode riêng;
- đưa WebSocket xuống cuối.

## 1. Kết quả

MemoryOS có tính năng tương đương Onyx Voice:

- **Trang quản trị `/admin/voice`:** model manager kết nối provider STT/TTS; mỗi phần có một mặc định.
- **Mic:** trong composer Chat và ô tìm kiếm của Search; transcript chạy trực tiếp vào ô nhập.
- **Nút đọc thành tiếng** trên mỗi câu trả lời.
- **Hội thoại rảnh tay:**
  - Auto-Send on Pause;
  - Auto-Playback: đọc ngay khi câu trả lời đang sinh, chữ hiện theo tiếng;
  - auto-listen.
- **Mục Giọng nói trong Cài đặt:** ba giá trị, lưu phía server.

Nguyên tắc của MEM-91 giữ nguyên:
- Âm thanh chỉ đi theo đường trình duyệt → MemoryOS API → provider của Tenant.
- Không dùng Web Speech API / `speechSynthesis`.
- Không lưu âm thanh.

## 2. Hiện trạng MemoryOS trước increment (main `1bb7d4da`)

### Backend

- **Chưa có:** code voice, WebSocket, multipart.
- **Spring AI audio:** `spring-ai-openai` 2.0.1 có `OpenAiAudioTranscriptionModel` và `OpenAiAudioSpeechModel` (client openai-java 4.49.0, option `baseUrl`).
  - Chưa có module ElevenLabs hay Azure.
  - Spring AI không có contract cho âm thanh vào liên tục.
- **Tiền lệ cấu hình provider ngoài catalog LLM:**
  - `chat_web_connection` (V49): mặc định search/content qua partial unique index.
  - `chat_image_connection` (V56–V58).
  - Hai stack này dùng lại `ProviderCredentials` (AES-GCM, KEEP/REPLACE/REMOVE), `ModelCatalogService.validateEndpoint` (chính sách endpoint nội bộ tin cậy) và JPA `@Version`.
  - Catalog MEM-77 không hợp cho model âm thanh: bắt buộc context window, tokenizer và `ChatProviderAdapter`.
  - Hợp đồng catalog: đọc cấu hình chỉ trả `credentialConfigured`, không bao giờ trả key.
- **Bảo mật và hạ tầng phụ trợ:**
  - `/api/**` nhận cookie `SESSION` (SameSite=Lax) hoặc bearer.
  - Request khác GET cần header `X-MemoryOS-CSRF: 1`; handshake WebSocket là GET nên không được bảo vệ bởi cơ chế này.
  - Redis chỉ có ở worker; API không có rate limiter.
  - `IamCapability` chưa có quyền voice.
  - Tùy chọn theo người dùng hiện chỉ có `actors.ui_language`.

### Frontend

- **Runtime:** `@assistant-ui/react` 0.15.18 có slot `dictation` và `speech` trên `useAISDKRuntime` (`chat-runtime-provider.tsx:119-122`).
  - Gửi tin trong lúc dictation sẽ hủy phiên.
  - `stopDictation()` bỏ các kết quả đến sau khi `session.stop()` resolve.
- **Chat:** composer (`chat-thread.tsx:145-189`) chưa có mic.
- **Search:** `search-page.tsx:225-266` dùng `SpeechRecognition` của trình duyệt.
- **Quản trị:** `/admin/web-search` (`chat-web-settings.tsx`) là trang gần nhất.
- **Cài đặt:** `/settings/general` mới có ngôn ngữ giao diện.

### Hạ tầng

- `web/nginx.conf`, location `/api/`: `Connection ""`, không chuyển `Upgrade`, `proxy_read_timeout 300s`.
- CSP không có `media-src`, nên `blob:`/MediaSource bị chặn.
- Proxy Vite chưa bật `ws: true`.

## 3. Quy tắc parity

- Giữ khái niệm, luồng, trạng thái, giới hạn và copy (dịch vi/en) của Onyx như ghi trong [tham chiếu](onyx-voice-reference.md).
- Chỉ khác Onyx khi thuộc một trong ba loại, và mọi khác biệt được liệt kê ở §5:
  - **[MemoryOS]** contract hoặc stack hiện có bắt buộc;
  - **[MEM-91]** issue yêu cầu thêm;
  - **[Sửa lỗi]** lỗi Onyx đã được chứng minh ở [tham chiếu §6](onyx-voice-reference.md#6-lỗi-và-điểm-yếu-đã-xác-định).
- **Đổi tên** (theo tiền lệ Web/Image của MemoryOS):

  | Onyx | MemoryOS |
  | --- | --- |
  | `voice_provider` | `chat_voice_connection` |
  | `provider_type` | `provider` |
  | `api_base` | `endpoint` |
  | `default_voice` | `tts_voice` |
  | `is_default_stt/tts` | `stt_active/tts_active` |
  | `/api/voice/*`, `ws-token` | `/api/chat/voice/*`, `tickets` |
  | activate/deactivate | `PUT /selection` |
  | `is_final` | `isFinal` (JSON camelCase) |

## 4. Thiết kế

### 4.1 Dữ liệu

**`chat_voice_connection`** — V63, Chat sở hữu, JPA `VoiceConnectionEntity`:

| Cột | Onyx | Ghi chú |
| --- | --- | --- |
| `id` UUID, `tenant_id` | `id` | Theo Tenant [MemoryOS] |
| `provider` | `provider_type` | Enum `VoiceProvider`. Hiện có `OPENAI`, `OPENAI_COMPATIBLE`; CHECK có tên `ck_chat_voice_connection_provider` và chỉ được nới khi adapter của provider hoàn thành. `UNIQUE (tenant_id, provider)`, vì Onyx thực tế cũng mỗi loại một dòng |
| `endpoint` | `api_base` | Bắt buộc với OpenAI-compatible; rỗng nghĩa là API công khai của provider; kiểm bằng `validateEndpoint` |
| `credential` | `api_key` | `ProviderCredentials` gắn Tenant/connection [MemoryOS]; OpenAI-compatible cho phép không có key |
| `stt_model`, `tts_model`, `tts_voice` | `stt_model`, `tts_model`, `default_voice` | Chuỗi rỗng nghĩa là chức năng đó chưa dùng được; model/giọng gợi ý nằm trong enum [Sửa lỗi: Onyx dùng chung `whisper-1`] |
| `stt_active`, `tts_active` | `is_default_stt/tts` | Partial unique index theo Tenant; CHECK active phải có model (TTS có cả giọng) |
| `revision` | — | JPA `@Version`, chống ghi đè [MEM-91] |

- Azure dùng endpoint của tài nguyên Speech (Target URI) nên không cần cột region. Nhận dạng theo ngôn ngữ giao diện nên không lưu Spoken Languages. Model hiển thị `default` (STT) và `neural` (TTS) như Onyx.

**`chat_voice_settings`** — V64, JDBC `JdbcVoiceSettingsRepository`:
- **Cột:** `tenant_id`, `actor_id` (khóa ngoại tới `tenant_memberships`), `auto_send` (false), `auto_playback` (false), `playback_speed` (1.0, CHECK 0.5–2.0).
- **Ghi dữ liệu:** partial update nguyên tử bằng `INSERT … ON CONFLICT DO UPDATE SET x = COALESCE(:x, x)`, không đọc rồi ghi.
- **Khác Onyx:** Onyx lưu thành cột trên bảng `user`. MemoryOS để bảng này thuộc Chat, không ghi vào bảng IAM [MemoryOS].

### 4.2 API

| Onyx | MemoryOS | Quyền và ghi chú |
| --- | --- | --- |
| `GET /voice/status` | `GET /api/chat/voice` | Membership đang active; `{sttAvailable, ttsAvailable}` (có mặc định dùng được) |
| — | `GET /api/chat/voice/providers` | `MODELS_MANAGE`; provider đã có adapter, endpoint mặc định, model và giọng gợi ý |
| `GET /admin/voice/providers` | `GET /api/chat/voice/connections` | `MODELS_MANAGE`; trả `credentialConfigured`, không trả key đã mask [MemoryOS] |
| `POST /admin/voice/providers`, `POST …/providers/test` | `PUT /api/chat/voice/connections/{provider}` | Upsert có revision; credential KEEP/REPLACE/REMOVE. Kiểm key nháp với provider **ngoài transaction** rồi mới lưu; provider từ chối thì không lưu gì. `activate: STT\|TTS` chỉ áp dụng khi tạo mới |
| `DELETE /admin/voice/providers/{id}` | `DELETE /api/chat/voice/connections/{provider}?revision=` | Xóa cả dòng như Onyx |
| activate/deactivate STT/TTS | `PUT /api/chat/voice/selection` | `{function, provider \| null, model?}`; `model` chỉ dùng cho TTS; gỡ mặc định cũ trong cùng Tenant |
| — (Onyx chỉ test khi lưu) | `POST /api/chat/voice/connections/{provider}/test` | Kiểm connection đã lưu [MEM-91]; tối đa 2 kiểm tra đồng thời, deadline 15 s |
| `GET /admin/voice/voices?provider_type=` | (trong `/providers`) | Danh sách tĩnh |
| `GET /admin/voice/providers/{id}/voices` | — | Bỏ: không có caller (ADR 0002) |
| `POST /voice/transcribe` | — | Bỏ: Onyx không có caller ở frontend (ADR 0002) |
| `POST /voice/ws-token` | `POST /api/chat/voice/tickets` | Body tùy chọn `{purpose: TRANSCRIBE\|SYNTHESIZE}` (mặc định TRANSCRIBE); nghe cần `CHAT_WRITE` hoặc `SEARCH_READ`, đọc cần `CHAT_READ`; header CSRF; vé chỉ mở đúng WebSocket của mục đích [MEM-91] |
| WS `/voice/transcribe/stream` | WS `/api/chat/voice/transcribe/stream` | Kiểm lại quyền khi handshake |
| `PATCH /voice/settings` | `GET` + `PATCH /api/chat/voice/settings` | Membership đang active; cần `GET` vì MemoryOS không có endpoint preferences chung [MemoryOS] |
| `POST /voice/synthesize` | `POST /api/chat/voice/synthesize` | Giai đoạn 3; `CHAT_READ`; có giới hạn độ dài [Sửa lỗi] |
| WS `/voice/synthesize/stream` | WS `/api/chat/voice/synthesize/stream` | Giai đoạn 4; `CHAT_READ` |

- **Map quyền:** `FULL_ADMIN_PANEL_ACCESS` → `MODELS_MANAGE` (cùng quyền với Models và Web search). `BASIC_ACCESS` → membership đang active cộng capability của nơi dùng [MemoryOS].
- **Lỗi HTTP:** dùng lại mã Chat có sẵn (`CHAT_PROVIDER_UNAVAILABLE`, `CHAT_CAPACITY_EXCEEDED`, `CHAT_CONFLICT`, `CHAT_INVALID_REQUEST`), không kèm văn bản provider [MemoryOS].
- **Lỗi WebSocket:** `VOICE_*`.
- **Ngoài OpenAPI:** giao thức WebSocket được ghi trong spec, client viết tay.

### 4.3 Trang `/admin/voice`

**Điều hướng và header:** theo `MODELS_MANAGE`, nằm cạnh Models và Web search. Header "Giọng nói / Voice" kèm mô tả dịch từ Onyx.

**Section Speech to Text** ("Chọn model chuyển giọng nói thành chữ trong chat"):
- Card: Whisper (OpenAI), OpenAI-compatible [MEM-91], ElevenLabs, Azure Speech.
- Card của một provider chỉ xuất hiện khi adapter đã có.

**Section Text to Speech:** card nhóm theo provider.
- **OpenAI:** TTS-1, TTS-1 HD.
- **OpenAI-compatible:** một card [MEM-91].
- **ElevenLabs** và **Azure.**
- Mỗi section có banner khi chưa có mặc định.

**Card** — giữ đúng Onyx:
- Ba trạng thái disconnected / connected / selected, với nút Connect / Set as Default / Current Default.
- Bấm thân card thực hiện hành động chính (selected thì bỏ chọn).
- Khi hover hiện Edit và Disconnect, thêm **Test** [MEM-91].
- Trạng thái tính theo loại provider; TTS selected khi `tts_model` trùng card.

**Modal Set up / Configure:**
- Trường giữ như Onyx: Target URI (Azure), API Key, Spoken Languages (Azure STT, tối đa 10 cloud / 4 tự host), Default Model (TTS OpenAI), Voice (combobox cho nhập ID).
- Thêm:
  - **Base URL** cho OpenAI-compatible [MEM-91].
  - **STT Model** là danh sách kèm nhập tự do [MEM-91, Sửa lỗi].
- **API Key:** để trống là KEEP ("Để trống để giữ key hiện tại"), nhập mới là REPLACE. "Xóa key" (REMOVE) chỉ có với OpenAI-compatible.

**Luồng lưu** — giữ như Onyx:
1. Server kiểm key nháp với provider trước khi lưu; provider từ chối thì không lưu gì.
2. Kết nối lần đầu từ card nào thì tự thành mặc định của phần đó; sửa thì giữ nguyên mặc định.

Sửa lỗi kèm theo:
- `tts_model` không bị ghi đè khi sửa từ card STT [Sửa lỗi].
- Xung đột revision báo và giữ bản nháp không chứa bí mật [MEM-91].

**Disconnect:**
- Dùng `ConfirmDialog` với copy của Onyx. Nội dung nêu rõ mất cả STT lẫn TTS.
- Cảnh báo "không còn provider thay thế" tính **theo từng phần** [Sửa lỗi].

**Chọn / bỏ chọn:** có trạng thái đang xử lý và báo lỗi [Sửa lỗi]. Sau mọi thay đổi, invalidate query status [Sửa lỗi].

**Giao diện đã dựng** (`web/src/features/voice/voice-admin-page.tsx`, `voice-provider-card.tsx`, `voice-provider-dialog.tsx`), theo [tham chiếu Mobbin](ui-references.md):
- Mỗi chức năng là một danh sách có viền, một dòng cho mỗi provider; hành động luôn hiện thay cho hover [MemoryOS: truy cập bằng bàn phím và cảm ứng].
- Test nằm trong hộp thoại; TTS chọn model và giọng trong hộp thoại thay cho card theo model.
- Copy Disconnect chung cho cả hai chức năng, chưa có cảnh báo "không còn provider thay thế".
- Đang tải dùng `Skeleton`; lỗi tải có nút Tải lại.

**Dùng lại:** bố cục `/admin/web-search`, `ConfirmDialog`, generated client; `dialog` cài từ registry shadcn.

### 4.4 Nhập bằng giọng nói (STT)

**Frontend** — giữ như Onyx, trừ chỗ ghi chú:

- **Nơi hiện mic:** composer Chat (`ComposerPrimitive.Dictate` / `StopDictation`) và ô tìm kiếm Search. Mic hiện khi `sttAvailable`.
  - Model manager khi chưa cấu hình: nút mic là link tới `/admin/voice`, có title giải thích [Sửa lỗi: Onyx chỉ disable]. Thành viên khác không thấy mic.
  - Search không còn `SpeechRecognition` của trình duyệt; mic dùng `voice-dictation.ts`.
- **Mic bị disable** khi câu trả lời đang chạy (TTS đang tải/phát: giai đoạn 4).
- **Placeholder:** "Đang nghe…"; "MemoryOS đang nói..." ở giai đoạn 4.
- **Dải ghi âm** (`chat-dictation-controls.tsx`): chấm đỏ, đồng hồ `m:ss`, 40 thanh theo mức âm lượng thật, nút tắt mic, nút dừng. Trạng thái trình bày (mức âm, mute, lỗi) nằm trong `VoiceSessionStore`; văn bản nháp và trạng thái dictation vẫn do runtime assistant-ui giữ.
- **Logic** (đã có, `web/src/features/voice`):
  - `capture/pcm-capture.worklet.ts`: AudioWorklet cùng origin, resample 24 kHz, chunk 100 ms kèm mức âm lượng, không bỏ âm thanh khi luồng chính chậm [Sửa lỗi].
  - `capture/audio-capture.ts`: xin quyền mic trước, khử vọng/khử ồn, tắt mic, dọn dẹp track và context.
  - `transcribe-socket.ts`: URL cùng origin `ws(s)`, timeout mở 5 s, `end` rồi chờ final tối đa **15 s** (Onyx chờ 3 s rồi giữ interim; đường chunked transcribe lại cả bản ghi nên cần thời gian provider [MemoryOS]), xử lý `error` và đóng bất thường.
  - `voice-dictation.ts`: mic → vé → WebSocket; giữ tối đa khoảng 5 s âm thanh trong lúc mở kết nối.
  - `memoryos-dictation-adapter.ts`: `DictationAdapter`. Interim thay phần xem trước; final được phát trước khi `stop()` resolve; `status` cập nhật tại chỗ; hủy được cả khi đang khởi động.
- **Send** bị chặn cho tới khi có final [Sửa lỗi: Onyx ghi đè chữ gõ và không dừng ghi].
- **Lỗi:** thông báo đã dịch, không hiện văn bản thô.

**Backend** (đã có):

- **Vé** (`VoiceTicketStore`):
  - 32 byte ngẫu nhiên, 60 s, dùng một lần; tiêu vé ngay cả khi handshake thất bại.
  - Gắn với actor đã yêu cầu; tối đa 10 vé/phút mỗi actor theo cửa sổ cố định [Sửa lỗi: Onyx trượt cửa sổ].
  - Nằm trong **bộ nhớ tiến trình API**. Vé sống 60 giây, không cần tồn tại qua restart (chính sách persistence), và replay RAM cùng Stop cục bộ của Chat đã giả định một tiến trình API [MemoryOS]. Onyx dùng Redis, còn API MemoryOS không có Redis.
- **Handshake** (`VoiceHandshakeInterceptor`):
  - Chain bảo mật `/api/**` xác thực phiên hoặc bearer.
  - Spring chỉ nhận handshake cùng origin (không cấu hình allowed origins).
  - Tiêu vé đúng actor, kiểm lại `CHAT_WRITE` hoặc `SEARCH_READ`.
- **Giao thức** (`TranscribeWebSocketHandler`):

  | Chiều | Message |
  | --- | --- |
  | Client → server | Binary PCM16 LE mono 24 kHz; `{"type":"end"}` |
  | Server → client | `{"type":"transcript","text","isFinal"}`; `{"type":"error","code"}` rồi đóng |

  - Mã lỗi: `VOICE_BUSY`, `VOICE_UNAVAILABLE`, `VOICE_INVALID_REQUEST`, `VOICE_INVALID_AUDIO`, `VOICE_INVALID_MESSAGE`, `VOICE_AUDIO_TOO_LARGE`, `VOICE_IDLE`, `VOICE_SESSION_TOO_LONG`, `VOICE_PROVIDER_FAILED`.
  - `reset` của Onyx chỉ phục vụ provider có final giữa chừng, nên chưa làm.
- **Giới hạn:**
  - Như Onyx: 64 KiB/frame binary, 25 MiB/kết nối.
  - Thêm: 16 KiB/frame text (đặt trên từng session), 60 s không có âm thanh, 10 phút/phiên, close code theo loại lỗi [MEM-91, Sửa lỗi].
  - Mỗi actor một phiên, tối đa 16 phiên mỗi tiến trình.
- **Đường chunked** (`ChunkedTranscriber`, như Onyx):
  - Cửa sổ 3 s; bỏ cửa sổ không có frame 100 ms nào đạt RMS 150. Mỗi cửa sổ có tiếng trả interim đã nối.
  - Tối đa 4 cửa sổ chờ; provider chậm thì bỏ interim, phần final vẫn phủ toàn bộ âm thanh.
  - Khi `end`: hủy cửa sổ chưa chạy, cắt khoảng lặng cả bản ghi (frame 100 ms, giữ 0,5 s, ngưỡng tương đối khi nói nhỏ), transcribe lại toàn bộ.
  - Lỗi final thì dùng interim; không có interim thì trả `VOICE_PROVIDER_FAILED`.
  - Mọi lời gọi provider của một phiên chạy tuần tự trên một virtual thread.
- **Batch** (`VoiceTranscriptionService`):
  - Spring AI `OpenAiAudioTranscriptionModel` với client openai-java sync và async do MemoryOS tạo (retry 0, timeout 60 s, đóng sau mỗi request).
  - PCM được bọc thành WAV, filename `audio.wav`; ngôn ngữ `vi`/`en`.
  - Server không cần key vẫn nhận một bearer giữ chỗ hợp lệ.
- **OpenAI Realtime:**
  - Chưa làm; chờ spike với key thật (Q3).
  - Khi thêm adapter live đầu tiên, tạo interface phiên live chung cho nó và `ChunkedTranscriber`. Chưa tạo interface khi mới có một implementation.
  - Hiện OpenAI dùng đường chunked, nên chỉ gửi khi bấm dừng, giống hành vi Onyx với OpenAI.
- **Auto-Send:**
  - Có final sau khi bấm dừng, cài đặt bật, composer rảnh (không đang trả lời, tệp đính kèm sẵn sàng) → gửi tin (`ChatDictationAutoSend`).
  - Đường chunked chỉ có final khi bấm dừng, nên "gửi khi ngừng nói" và timer 10 s của Onyx chỉ áp dụng khi có adapter live có VAD [MemoryOS].

### 4.5 Đọc thành tiếng

- **Nút:** `ActionBarPrimitive.Speak` / `StopSpeaking` với `MemoryosSpeechAdapter`.
  - Ba trạng thái "Đọc thành tiếng" / "Đang tải..." / "Dừng phát".
  - Chỉ hiện khi `ttsAvailable`. Ẩn khi auto-playback đang đọc chính tin nhắn đó (như Onyx).
- **Văn bản:** client chuyển markdown sang văn bản thuần qua pipeline remark hiện có, bỏ citation và code block. Chỉ làm ở **một chỗ** [Sửa lỗi]; server chỉ kiểm độ dài.
- **REST `synthesize`** (như Onyx):
  - Lấy trước chunk đầu rồi mới trả header.
  - Stream `audio/mpeg`, `Cache-Control: no-cache`, `X-Accel-Buffering: no`.
  - Client gửi tốc độ người dùng đã cài [Sửa lỗi].
- **Độ dài:**
  - Tối đa 32 000 ký tự mỗi request [Sửa lỗi: Onyx không giới hạn].
  - Server tách thành đoạn ≤ 4096 ký tự tại ranh giới câu và stream mp3 nối tiếp, vì API speech của OpenAI giới hạn 4096 ký tự. Spike xác nhận.
- **Player:**
  - MSE `audio/mpeg` chế độ `sequence`, phát sau 100 ms; không có MSE thì dùng Blob (như Onyx).
  - Mỗi lúc chỉ một player [Sửa lỗi].
  - CSP thêm `media-src 'self' blob:` [MemoryOS].

**Đã triển khai:**
- **Server** (`VoiceSynthesisService`, `VoiceSynthesisController`):
  - `CHAT_READ`; tối đa 8 luồng mỗi tiến trình API, vượt thì `CHAT_CAPACITY_EXCEEDED`.
  - Dùng `OpenAiAudioSpeechModel.stream` của Spring AI; mỗi đoạn là một request tuần tự, chunk MP3 được ghi ngay ra response.
  - Lỗi provider sau khi đã gửi audio thì cắt response; trình duyệt báo lỗi phát.
- **Client:**
  - Runtime assistant-ui đưa markdown của tin nhắn; `speech-text.ts` chuyển sang văn bản đọc (parser `remark-parse` và `remark-gfm` như renderer, cần thêm dependency `unified` và `remark-parse`).
  - Nút ở action bar cạnh Sao chép: Đọc thành tiếng → biểu tượng đang tải (bấm để dừng) → Dừng đọc.
  - Tốc độ lấy từ cài đặt khi bắt đầu đọc; provider áp tốc độ, trình duyệt phát ở tốc độ 1.
  - Lỗi (bận, provider không phản hồi, trình duyệt không phát được, không có nội dung) hiện trên composer bằng copy tĩnh.

### 4.6 Auto-Playback và auto-listen

- **Kích hoạt** (như Onyx): tin nhắn assistant đang stream trong lần mount này, khi `auto_playback` bật và `ttsAvailable`. Không đọc lịch sử; dừng khi đổi hội thoại hoặc hủy.
- **Tách đoạn:** giống đúng Onyx ([tham chiếu §3.6](onyx-voice-reference.md#36-auto-playback-và-auto-listen)): hết câu ≥ 10/30, mệnh đề khi ≥ 150, từ khi ≥ 200, fast start 20 ký tự / 200 ms, flush 250 ms.
- **TTS WebSocket** (như Onyx):
  - Client gửi `config` trước, rồi các `synthesize` (≤ 4096 ký tự), cuối cùng `end`.
  - Server trả binary mp3 và `audio_done`.
  - Client xử lý `error` [Sửa lỗi].
- **Synthesizer theo provider:**
  - OpenAI và OpenAI-compatible: mỗi đoạn gọi HTTP tuần tự qua `OpenAiAudioSpeechModel.stream`; lỗi non-200 thành lỗi gửi client [Sửa lỗi].
  - ElevenLabs: WebSocket `stream-input`.
  - Azure: theo Q1.
- **Phát:** MSE. Không có MSE thì báo auto-playback không khả dụng trên trình duyệt đó, không để cờ kẹt [Sửa lỗi].
- **Chữ chạy theo tiếng** (như Onyx):
  - Lead 0,28 s, tối đa 8 ký tự/frame, tối thiểu 12 ký tự, fallback sau 5 s.
  - Vị trí được map sang markdown đã render qua vị trí mdast; citation không tính vào số ký tự.
- **Trong lúc đọc** (như Onyx): ẩn action bar, placeholder "MemoryOS đang nói...", pill waveform có nút tắt tiếng, nút Send thành Stop (dừng tay).
- **Auto-listen** (như Onyx): TTS đã thực sự phát và nay rảnh, `auto_playback` bật, người dùng đã bấm mic tay trong phiên, lần dừng trước không phải dừng tay → sau 400 ms bắt đầu ghi. Có guard phiên 5 phút.

**Đã triển khai:**
- **Server:** `StreamingSynthesizer` và `SynthesizeWebSocketHandler`.
  - Mỗi phần là một request speech tuần tự; audio ghi ra socket ngay khi có.
  - Tối đa 4096 ký tự mỗi phần, 32 000 ký tự mỗi phiên; không nhận text sau 5 phút rảnh, phiên tối đa 10 phút.
  - Vé đọc (`SYNTHESIZE`) khác vé nghe.
- **Client:**
  - `ChatAutoPlayback` theo dõi lượt chạy của thread, không theo component tin nhắn, nên đổi id tin nhắn giữa stream không làm đọc lại.
  - Chỉ đọc lượt bắt đầu sau khi mở hội thoại. Stream được resume khi mở lại hội thoại thì không đọc.
  - `speech-text.ts` chạy lại trên toàn bộ markdown mỗi lần cập nhật; chunker chỉ xét phần sau đoạn đã gửi.
  - Phát qua `playAudioStream` (MediaSource; không có MSE thì phát sau khi tải xong, thay vì không phát như Onyx). Tắt tiếng dùng `audio.muted`.
  - Auto-listen chỉ chạy khi lượt đọc kết thúc `finished` (audio phát hết). Dừng tay, hủy câu trả lời, đọc tay hay lượt mới đều là `stopped`.
  - Thanh "Đọc tự động" dùng nhịp CSS, không đo mức âm lượng của audio đang phát.
- **Chưa làm:** chữ chạy theo tiếng. Câu trả lời hiện theo stream chữ như khi không đọc.

### 4.7 Cài đặt

Mục "Giọng nói" trong `/settings/general`:
- Switch "Tự gửi khi ngừng nói".
- Switch "Tự đọc câu trả lời".
- Slider "Tốc độ phát" 0.5–2.0, bước 0.1 (shadcn `Switch`/`Slider`); server làm tròn tới 0,1.

Lưu optimistic, toast đã dịch. Mỗi điều khiển chỉ xuất hiện khi hành vi tương ứng đã được triển khai.

Hiện có (`voice-settings-section.tsx`):
- Switch "Tự động gửi khi dừng ghi âm", chỉ hiện khi Tenant có STT.
- Switch "Tự động đọc câu trả lời", chỉ hiện khi Tenant có TTS.
- Slider "Tốc độ đọc" 0.5–2.0 bước 0.1 kèm giá trị `1.0×`, chỉ hiện khi Tenant có TTS; chỉ lưu khi thả tay hoặc sau mỗi phím.
- Mỗi thay đổi chỉ gửi đúng một trường. Trạng thái lưu và lỗi hiện ngay trong mục, không dùng toast.

### 4.8 Provider

| Provider | STT chunked/batch | STT live | TTS | Kiểm credential |
| --- | --- | --- | --- | --- |
| OpenAI | Spring AI transcription (whisper-1, gpt-4o-transcribe, gpt-4o-mini-transcribe) — **đã có** | Realtime GA, server VAD — chờ spike | Spring AI speech (tts-1, tts-1-hd; alloy…) | `GET {base}/models` — **đã có** |
| OpenAI-compatible [MEM-91] | Spring AI với `baseUrl` — **đã có** | Không, dùng chunked | Spring AI speech | `GET {base}/models` — **đã có** |
| ElevenLabs | REST Scribe (WAV, `language_code` theo UI) — **đã có** | Scribe realtime — chưa làm | REST stream, `voice_settings.speed` 0,7–1,2 — **đã có**; `stream-input` chưa làm | `GET /v1/models` với `xi-api-key` — **đã có** |
| Azure | REST short-audio (16 kHz, phần ≤ 55 s, `vi-VN`/`en-US`) — **đã có** | Không (Q1) | REST SSML có escape, MP3 24 kHz — **đã có** | `GET /tts/cognitiveservices/voices/list` — **đã có** |

Kiểm credential đòi phản hồi 2xx có mảng `data`, không theo redirect và không đọc body lỗi.

### 4.9 Hạ tầng

- `spring-boot-starter-websocket` trong `api`.
- nginx đã có location riêng cho `/api/chat/voice/transcribe/stream`, có `Upgrade` / `Connection: upgrade` và timeout đọc/gửi 660 s. WebSocket TTS sẽ thêm location khi làm.
- Vite `/api` proxy bật `ws: true`.
- CSP `media-src 'self' blob:` đã thêm cho phát audio qua MediaSource/Blob.
- Không cần tăng `client_max_body_size` vì không có endpoint upload.

### 4.10 Quan sát và riêng tư

- **Meter:** `memoryos.chat.voice.request` (tag `provider`, `operation` = `verify|transcribe`, sau này `synthesize`, `outcome`).
- **Không ghi:** transcript, văn bản TTS, âm thanh hay lỗi provider vào log, trace hoặc message exception [Sửa lỗi, MemoryOS].
- Các record chứa key hay vé đều có `toString` đã che.

## 5. Khác biệt so với Onyx

| # | Onyx | MemoryOS | Loại |
| --- | --- | --- | --- |
| 1 | `FULL_ADMIN_PANEL_ACCESS`, `BASIC_ACCESS` | `MODELS_MANAGE`; membership + capability, kiểm lại khi handshake | MemoryOS |
| 2 | Bảng toàn cục, không revision, trả key đã mask | Theo Tenant, `ProviderCredentials`, revision, `credentialConfigured` | MemoryOS, MEM-91 |
| 3 | Ticket trong Redis, cửa sổ trượt | Bộ nhớ tiến trình API, dùng một lần, cửa sổ cố định | MemoryOS, Sửa lỗi |
| 4 | Không có base URL cho OpenAI; private network chỉ cho Azure | Provider OpenAI-compatible, cho phép endpoint nội bộ theo chính sách catalog | MEM-91 (cùng hướng PR #14304) |
| 5 | Test chỉ khi lưu; validate trong transaction | Hành động Test trên card; kiểm key nháp ngoài transaction trước khi lưu | MEM-91, MemoryOS |
| 6 | Model STT không chọn được; `tts_model` bị ghi đè | Danh sách model + nhập tự do; không ghi đè | Sửa lỗi, MEM-91 |
| 7 | `hasAlternatives` bỏ qua mode; chọn/bỏ chọn không báo lỗi; status cache không invalidate | Tính theo phần; pending + lỗi; invalidate | Sửa lỗi |
| 8 | ScriptProcessorNode, bỏ âm thanh khi tồn | AudioWorklet, không bỏ | Sửa lỗi |
| 9 | OpenAI không có VAD | Bật server VAD (chờ spike Q3) | Sửa lỗi |
| 10 | Lỗi giữa chừng fallback, mất âm thanh | Lỗi có kiểu, đóng | Sửa lỗi |
| 11 | Không giới hạn thời gian rảnh/phiên; REST TTS không giới hạn độ dài | 60 s, 10 phút, 32 000 ký tự | MEM-91, Sửa lỗi |
| 12 | Bỏ markdown hai lần, không bỏ citation | Một chỗ ở client, bỏ citation | Sửa lỗi |
| 13 | Đọc tay tốc độ 1.0; nhiều player chồng; bỏ qua `error` WS; thiếu MSE thì kẹt | Dùng tốc độ đã cài; một player; xử lý lỗi; báo không khả dụng | Sửa lỗi |
| 14 | Gõ phím hay Send trong lúc ghi bị ghi đè / không dừng | Chặn Send tới khi có final; giữ chữ gõ | Sửa lỗi |
| 15 | Log/trace chứa văn bản; lỗi provider hiện nguyên văn | Không ghi nội dung; lỗi có mã | MemoryOS, Sửa lỗi |
| 16 | Endpoint batch `transcribe`, `providers/{id}/voices` | Không làm (không có caller) | MemoryOS (ADR 0002) |
| 17 | UI tự dựng, không có tiếng Việt | Primitive assistant-ui, control shadcn, vi/en | MemoryOS |
| 18 | Ảnh hưởng hạ tầng Onyx | nginx WebSocket, CSP `media-src`, Vite `ws` | MemoryOS |
| 19 | Chờ final 3 s rồi giữ interim | Chờ final 15 s | MemoryOS |
| 20 | Card, hành động hiện khi hover; hai card TTS theo model | Danh sách dòng, hành động luôn hiện; model và giọng chọn trong hộp thoại ([ui-references.md](ui-references.md)) | MemoryOS |
| 21 | Search không có trong Onyx | Mic trong Search dùng cùng WebSocket, bỏ `SpeechRecognition` | MEM-91 |
| 22 | Một ws-token cho mọi socket; không có MSE thì Auto-Playback không phát | Vé theo mục đích; không có MSE thì phát sau khi tải xong; chưa có chữ chạy theo tiếng | MemoryOS, Sửa lỗi |

## 6. Quyết định

Người thực hiện yêu cầu triển khai ngay theo các giả định trong [plan.md](plan.md) (15/09/2026):
- **Q2 — Nơi cấu hình:**
  - Đã triển khai bảng `chat_voice_connection` riêng, theo Onyx và tiền lệ Web/Image.
  - Văn bản issue ghi "mở rộng catalog MEM-77", nên vẫn cần báo người tạo issue.
- **Q3 — OpenAI server VAD:** giả định bật nếu spike với key thật đạt. Chưa có key, nên OpenAI tạm dùng đường chunked.
- **Q1 — Azure:** giả định chỉ dùng REST; streaming Azure (Speech SDK native) để sau.

## 7. Thứ tự giao

1. **Provider và `/admin/voice`:** OpenAI, OpenAI-compatible; kèm endpoint availability.
2. **Nhập bằng giọng nói:** vé, STT WebSocket (chunked; OpenAI Realtime sau spike), mic trong Chat và Search, bảng cài đặt và Auto-Send.
3. **Đọc thành tiếng:** REST synthesize, player MSE, tốc độ phát.
4. **Hội thoại rảnh tay:** Auto-Playback (TTS WebSocket, tách đoạn, chữ theo tiếng) và auto-listen.
5. **ElevenLabs và Azure** (theo Q1).
6. **Hợp nhất tài liệu và nghiệm thu provider thật.**

## 8. Ngoài phạm vi

- Lưu âm thanh, cache TTS, transcript ngoài tin nhắn.
- Barge-in; speech-to-speech realtime.
- Voice-to-TODO; gateway dùng chung; triển khai Whisper/Kokoro (MEM-66).
- Giọng hay ngôn ngữ riêng từng người; nhiều connection cùng loại provider.
- Zoom Scribe; câu trả lời multi-model.

## 9. Rủi ro và khoảng trống bằng chứng

- Trang cloud chỉ quan sát qua ảnh docs; commit `40eb240df` không có trong bản clone; Northstar không có trên máy.
- API speech/dictation của assistant-ui còn thử nghiệm và khác mô hình transcript cộng dồn của Onyx. Adapter mới được kiểm bằng runtime giả, chưa kiểm trong composer thật.
- MSE không có trên iOS Safari: auto-playback không chạy ở đó (giống Onyx), nút đọc dùng Blob.
- **Chữ chạy theo tiếng:** map vị trí với renderer markdown/citation của MemoryOS có thể phức tạp.
- **Vé nằm trong bộ nhớ một tiến trình API:** chạy nhiều tiến trình API cần sticky session hoặc kho vé dùng chung.
- **AudioWorklet không có output** được giả định vẫn xử lý khi chỉ nối nguồn vào; cần kiểm trên Chrome, Firefox và Safari (S0.6).
- **Chưa xác minh với provider thật:** OpenAI Realtime GA với server VAD; Spring AI audio với server OpenAI-compatible (mới kiểm bằng fixture loopback); giới hạn 4096 ký tự của OpenAI speech; server OpenAI-compatible có chấp nhận `stream_format: "audio"` mà Spring AI gửi khi stream TTS hay không.
- **Chi phí provider:** Auto-Playback đọc mọi câu trả lời mới và chưa có quota.
- **Playwright:** dùng mic giả của Chromium, `routeWebSocket` và MP3 im lặng; mic thật, loa thật và Safari chưa được kiểm.
- **Chính sách tự phát âm thanh:** Auto-Playback bắt đầu vài giây sau thao tác gửi. Trình duyệt chặn phát thì composer báo không phát được âm thanh.
