# Tham chiếu Onyx Voice

Tài liệu này chỉ ghi **hành vi Onyx quan sát được**. Quyết định cho MemoryOS nằm ở [design.md](design.md).

**Nguồn:**
- **Source:** Onyx tại `D:\MemoryOS\.tmp\onyx`, HEAD `06aa2b0`. Bản clone là shallow; commit `40eb240df` mà issue trích không có trong đó.
- **Tài liệu công khai:** [Voice Mode (người dùng)](https://docs.onyx.app/overview/core_features/voice_mode), [Voice Mode (admin)](https://docs.onyx.app/admins/actions/voice_mode), [changelog](https://docs.onyx.app/changelog).
- **Trang `cloud.onyx.app/admin/voice`:** cần đăng nhập nên không quan sát trực tiếp. Bố cục được dựng lại từ source và đối chiếu với ảnh chụp trong docs.

**Viết tắt đường dẫn:**
- `VP/` = `web/src/views/admin/VoicePage/`
- `voice/` = `backend/onyx/voice/`
- `mv/` = `backend/onyx/server/manage/voice/`

## 1. Tính năng gồm những gì

| Khả năng | Người dùng thấy gì |
| --- | --- |
| Nhập bằng giọng nói | Nút mic trong ô chat; transcript chạy trực tiếp vào ô nhập; dải waveform, đồng hồ `mm:ss`, nút tắt mic |
| Đọc câu trả lời | Nút "Read aloud" trên action bar của câu trả lời |
| Auto-Send on Pause | Ngừng nói thì tin nhắn tự gửi (chỉ khi provider trả final giữa chừng, xem §3.4) |
| Auto-Playback | Câu trả lời được đọc **ngay khi đang sinh**; chữ hiện theo tiếng; đọc xong mic tự nghe lại |
| Trang admin Voice | Cấu hình provider STT/TTS, chọn mặc định riêng cho từng chức năng |
| Settings → Voice | Hai công tắc và thanh tốc độ phát |

Không có công tắc "voice mode" riêng. Vòng hội thoại rảnh tay hình thành khi Auto-Send và Auto-Playback cùng bật, và người dùng đã bấm mic thủ công ít nhất một lần trong phiên.

**Lịch sử phát hành (changelog):**
- v3.1.0 (2026-04-01): ra mắt voice mode; migration `93a2e195e25c` tạo ngày 2026-02-23.
- v4.1.0: OpenAI STT chuyển sang Realtime GA.
- v4.2.0: bỏ markdown ở backend trước TTS.
- v4.4.0: chặn đoạn im lặng trong chunked STT.
- v4.6.0: Azure hỗ trợ nhiều ngôn ngữ.

**PR đang mở:**
- #14304/#14305: provider OpenAI-compatible, chỉ STT.
- #14470: Zoom Scribe.
- #14728: khóa row và chặn bật TTS cho provider chỉ có STT.

## 2. Trang `/admin/voice`

### 2.1 Route và quyền

- **Route:** `web/src/app/admin/voice/page.tsx` re-export `VP/index.tsx`.
- **Khai báo điều hướng** (`web/src/lib/admin-routes.ts:128-136`): title "Voice", icon `SvgAudio`, quyền `FULL_ADMIN_PANEL_ACCESS`. Không giới hạn tier hay feature flag.
- **Sidebar:** nằm cùng nhóm không tiêu đề với Web Search, Image Generation, Code Interpreter.
- **Header:**
  - Title: "Voice".
  - Mô tả: "Configure speech-to-text and text-to-speech providers for voice input and spoken responses."

### 2.2 Bố cục

**Section "Speech to Text"** — "Select a model to transcribe speech to text in chats."
- Card phẳng:
  - **Whisper** — "OpenAI's general purpose speech recognition model."
  - **Azure Speech** — "Speech to text in Microsoft Foundry Tools."
  - **ElevenAPI** — "ElevenLabs Speech to Text API."
- Khi chưa có mặc định STT: banner "Connect a speech to text provider to use in chat."

**Section "Text to Speech"** — "Select a model to speak out chat responses."
- Card nhóm theo nhãn provider:
  - **OpenAI:** TTS-1 ("…optimized for speed"), TTS-1 HD ("…optimized for quality").
  - **Azure:** Azure Speech.
  - **ElevenLabs:** ElevenAPI.
- Banner tương ứng khi chưa có mặc định TTS.

Danh sách card được hard-code (`VP/index.tsx:52-117`). Card là `ProviderCard` dùng chung cho Web Search, Image Generation và LLM (`web/src/sections/admin/ProviderCard.tsx`).

### 2.3 Trạng thái card

| Trạng thái | Điều kiện (`VP/index.tsx:225-239`) | Vế phải | Bấm thân card |
| --- | --- | --- | --- |
| disconnected | Không có row cùng `provider_type`, hoặc row không có key | "Connect ⇄" | Mở modal Set up |
| connected | Có key nhưng không phải mặc định của card này | "Set as Default"; khi hover hiện Disconnect và Edit | Set as Default |
| selected | STT: `is_default_stt`. TTS: `is_default_tts && tts_model == card.id` | "Current Default ☑"; khi hover hiện Disconnect và Edit | **Bỏ chọn** |

Trạng thái tính theo `provider_type`. Connect OpenAI ở một card làm cả Whisper, TTS-1 và TTS-1 HD thành connected.

### 2.4 Modal Set up / Configure

Modal dùng Formik + Yup (`VP/shared.tsx:59-407`).
- **Header:** logo provider ⇄ logo Onyx.
- **Title:** "Set up {provider}" khi tạo, "Configure {provider}" khi sửa.
- **Mô tả:** "Connect to {provider} and set up your voice models."

| Trường | Hiện khi | Hành vi |
| --- | --- | --- |
| Target URI | Azure | Bắt buộc; region tách từ host `<region>.(tts\|stt).speech.microsoft.com` hoặc `<region>.api.cognitive.microsoft.com`; URL không phải cloud được coi là container tự host |
| API Key | Mọi provider | Ô password. Khi sửa, giá trị ban đầu là key đã mask (`sk-a...b1c2`); ảnh docs ghi "Leave blank to keep existing key". "Key đổi" = giá trị khác chuỗi mask |
| Spoken Languages | Azure, mở từ card STT | Danh sách locale cách nhau dấu phẩy; tối đa 10 (cloud) hoặc 4 (tự host); lưu `custom_config.stt_languages` |
| STT Model | Provider có >1 model STT | Frontend chỉ khai `whisper-1`, nên thực tế không hiện |
| Default Model | TTS OpenAI | Select TTS-1 / TTS-1 HD — "This model will be used by Onyx by default for text-to-speech." |
| Voice | Mode TTS | Combobox cho nhập tự do — "Select a voice or enter voice ID"; mỗi option có tên và id; danh sách tĩnh lấy từ `GET /admin/voice/voices?provider_type=` |

Nút footer: Cancel và Connect/Update. Nút submit bị disable khi form không hợp lệ hoặc chưa đổi gì.

### 2.5 Luồng thao tác

- **Connect / Update** (`VP/shared.tsx:161-236`):
  1. Nếu key đổi hoặc chưa có key: gọi `POST /admin/voice/providers/test`. Lỗi thì toast và dừng.
  2. Gọi `POST /admin/voice/providers`. Khi **tạo mới**, `activate_stt`/`activate_tts` theo card đang mở, nên provider **tự thành mặc định** cho mode đó. Khi sửa, giữ nguyên cờ mặc định.
  3. Backend kiểm credential lần nữa trước khi commit và rollback nếu lỗi.
- **Set as Default:**
  - STT: `POST /providers/{id}/activate-stt`.
  - TTS: `POST /providers/{id}/activate-tts?tts_model=<card id>`.
  - Backend gỡ cờ mặc định ở các row khác cùng mode (`backend/onyx/db/voice.py:124-181`).
- **Bỏ chọn:** `deactivate-stt|tts`. Không kiểm lỗi, không có trạng thái loading.
- **Disconnect** (`VP/shared.tsx:423-493`):
  - Title: "Disconnect {label}".
  - Mô tả: "This will remove the stored credentials for this provider."
  - Nội dung: "{label} models will no longer be used for speech-to-text or text-to-speech, and it will no longer be your default. Session history will be preserved."
  - Khi không có provider loại khác có key, thêm: "Connect another provider to continue using voice features."
  - Thực hiện: `DELETE /providers/{id}` xóa **cả row**, mất cả STT lẫn TTS.

### 2.6 Admin API

Mọi route cần `FULL_ADMIN_PANEL_ACCESS` (`mv/api.py`).

| Method | Path | Hành vi |
| --- | --- | --- |
| GET | `/admin/voice/providers` | Danh sách, sắp theo name; key mask; secret thành `********`; `custom_config` đã bỏ credential |
| POST | `/admin/voice/providers` | Upsert. Chỉ ghi key khi `api_key_changed` hoặc chưa có key. `llm_provider_id` copy key từ LLM provider (UI không dùng). Kiểm URL: chỉ Azure được trỏ tới private network. Từ chối credential nằm trong `custom_config`. `validate_credentials()` rồi rollback nếu lỗi |
| DELETE | `/admin/voice/providers/{id}` | Hard delete, 204 kể cả khi id không tồn tại |
| POST | `/admin/voice/providers/{id}/activate-stt`, `/deactivate-stt` | Đặt / gỡ mặc định STT |
| POST | `/admin/voice/providers/{id}/activate-tts?tts_model=`, `/deactivate-tts` | Đặt mặc định TTS (có thể kèm model) / gỡ |
| POST | `/admin/voice/providers/test` | Chỉ kiểm credential (key mới hoặc `use_stored_key` tra theo loại) |
| GET | `/admin/voice/voices?provider_type=`, `/admin/voice/providers/{id}/voices` | Danh sách giọng tĩnh; route theo id không có caller |

**`validate_credentials` từng provider:**
- OpenAI: `models.list()`.
- Azure: `GET …/cognitiveservices/voices/list`.
- ElevenLabs: `GET /v1/models`; lỗi 401 kèm `missing_permissions` vẫn được coi là hợp lệ.
- Không kiểm model, giọng hay khả năng STT/TTS.

### 2.7 Dữ liệu

**Bảng `voice_provider`** (`backend/onyx/db/models.py:3790-3847`):

| Cột | Ghi chú |
| --- | --- |
| `id` | Integer PK |
| `name` | Unique; UI đặt bằng nhãn provider, nên thực tế mỗi `provider_type` một row |
| `provider_type` | `openai` / `azure` / `elevenlabs`; không có enum hay CHECK |
| `api_key`, `api_secret` | Mã hóa; `api_secret` chưa provider nào dùng |
| `api_base` | Target URI của Azure |
| `custom_config` | JSONB: `speech_region`, `stt_languages` |
| `stt_model`, `tts_model`, `default_voice` | Nullable |
| `is_default_stt`, `is_default_tts` | Partial unique index `WHERE … = true`, mỗi mode một mặc định |
| `time_created`, `time_updated` | Không có revision hay optimistic lock |

**Bảng `user`:** `voice_auto_send` (false), `voice_auto_playback` (false), `voice_playback_speed` (1.0, giới hạn 0.5–2.0).

## 3. Trải nghiệm người dùng

### 3.1 Điều kiện hiện tính năng

- `GET /voice/status` (`mv/user_api.py:37-53`, `BASIC_ACCESS`) trả `{stt_enabled, tts_enabled}`. Mỗi cờ đúng khi có provider mặc định **và** provider đó có key.
- `useVoiceStatus` dùng SWR, cache 60 s, không revalidate khi admin đổi cấu hình.
- Nơi dùng status:
  - Mic trong ô nhập (`web/src/sections/input/AppInputBar.tsx:739-768`).
  - Nút Read aloud (`MessageToolbar.tsx:323-328`).
  - Auto-playback (`VoiceModeProvider.tsx:147-151`).
- Khi STT chưa bật:
  - Admin thấy mic xám, aria-label "Set up voice", tooltip "Voice not configured. Set up in admin settings." (không có link).
  - Người dùng thường không thấy mic.

### 3.2 Mic và dictation

**Nơi có mic:** ô nhập chung cho Chat, chế độ Search, trang new-tab của extension và agent preview. Chat được chia sẻ không có mic.

**Trạng thái mic:**

| Trạng thái | Hiển thị | Ghi chú |
| --- | --- | --- |
| Idle | Mic tertiary, "Start recording" | Bị disable khi câu trả lời đang stream, hoặc TTS đang tải/phát/chờ phát |
| Đang kết nối | Như idle | getUserMedia → ticket → WebSocket, tối đa 5 s, không có chỉ báo |
| Đang ghi | Mic nền đậm, "Stop recording" | Placeholder ô nhập: "Listening..." |
| Đang chốt | Spinner | Chờ final tối đa 3 s |

**Dải ghi âm** (`web/src/components/voice/Waveform.tsx`):
- 120 vạch cuộn trái theo mức âm lượng, đồng hồ `mm:ss`, nút tắt mic.
- Tắt mic đặt `track.enabled=false`; âm im lặng vẫn được gửi và đồng hồ vẫn chạy.
- Vị trí: trên ô nhập; phiên mới thì nằm dưới.

**Transcript:**
- Khi bắt đầu, văn bản đang có được lưu làm tiền tố.
- Mỗi cập nhật đặt ô nhập = `tiền tố + " " + transcript cộng dồn`, nên chữ gõ thêm trong lúc ghi bị ghi đè.

**Bấm dừng:** gửi `{"type":"end"}`, chờ final tối đa 3 s, rồi ô nhập = tiền tố + final. Nếu Auto-Send bật và chat đang ở trạng thái nhập, gửi luôn.

**Lỗi:**
- Toast "Could not access microphone", kèm một toast thứ hai chứa lỗi thô.
- Lỗi từ server hiện nguyên văn.

### 3.3 Giao thức STT

1. `getUserMedia({channelCount:1, sampleRate:{ideal:24000}, echoCancellation, noiseSuppression})`. Hộp xin quyền mic hiện trước.
2. `POST /api/voice/ws-token` trả `{token}`.
   - Token 32 byte urlsafe lưu trong Redis, TTL 60 s, dùng một lần (GETDEL).
   - Tối đa 10 lần/phút mỗi user, vượt thì 429 (`backend/onyx/redis/redis_pool.py:572-647`).
3. Mở `wss://…/api/voice/transcribe/stream?token=…`. Handshake kiểm (`backend/onyx/auth/users.py:2410-2480`):
   - `Origin` phải khớp `WEB_DOMAIN`;
   - tiêu token;
   - kiểm lại user;
   - chặn user bị giới hạn.
4. Thu âm qua `AudioContext(24000)` + ScriptProcessorNode(4096). Mỗi 250 ms gửi binary PCM16 LE mono 24 kHz. Nếu tồn hơn khoảng 1 s thì bỏ bớt âm thanh.

**Message:**

| Chiều | Message |
| --- | --- |
| Client → server | Binary PCM16; `{"type":"end"}`; `{"type":"reset"}` (server hỗ trợ, client không gửi) |
| Server → client | `{"type":"transcript","text":<cộng dồn>,"is_final":bool}`; `{"type":"error","message"}`; đóng socket không kèm close code riêng |

**Giới hạn** (`mv/websocket_api.py`): 64 KB/frame binary, 25 MB/kết nối. **Không có** giới hạn thời gian rảnh hay thời lượng phiên.

**Đường streaming:** dùng khi `supports_streaming_stt()` và không bật env `VOICE_DISABLE_STREAMING_STT`.
- Task nền poll `receive_transcript()` mỗi 100 ms.
- Gửi transcript khi text đổi, hoặc có `is_vad_end` (`is_final = is_vad_end`).
- Khi `end`: `close()` rồi gửi final.

**Đường chunked (fallback khi tạo hoặc chạy streaming lỗi):**
- Chia cửa sổ 3 s; bỏ cửa sổ có RMS < 150.
- Mỗi cửa sổ gọi `provider.transcribe` và gửi `is_final:false` với các kết quả nối lại.
- Khi `end`: cắt khoảng lặng của **cả bản ghi**, transcribe lại toàn bộ, gửi final.
- Không bao giờ có final giữa chừng.

`POST /voice/transcribe` (multipart, 25 MB) tồn tại nhưng **không có caller ở frontend**.

### 3.4 Auto-send

- **Nguồn:** `voice_auto_send` truyền vào recorder làm `autoStopOnSilence`.
- **Khi final giữa chừng về:**
  - Có chặn trùng: một lần mỗi phiên, cùng text trong 1500 ms.
  - Nếu không phải dừng tay, Auto-Send bật và chat đang nhập: gửi tin, dừng TTS, dừng phiên.
- **Khi Auto-Send tắt:** final giữa chừng khởi động timer 10 s để dừng mà không gửi.
- **Thực tế theo provider:**

| Provider | Final giữa chừng | Tự gửi khi ngừng nói |
| --- | --- | --- |
| ElevenLabs (VAD phía server, im lặng 1,0 s) | Có | Có |
| Azure (SDK tách câu) | Có | Có |
| OpenAI Realtime (`turn_detection: None`, chỉ commit khi đóng) | Không | Không; chỉ gửi ngay khi bấm dừng |
| Chunked fallback | Không | Không |

### 3.5 Read aloud

- **Nút** (`web/src/.../TTSButton.tsx`): nằm sau Copy/Like/Dislike.
  - Ba trạng thái: "Read aloud" (play-circle), "Loading..." (spinner), "Stop playback" (stop).
  - Chỉ hiện khi TTS bật.
  - Toolbar bị ẩn khi auto-playback đang đọc tin nhắn đó.
- **Văn bản đọc:**
  - Client bỏ markdown bằng regex (`web/src/lib/voice/utils.ts:13-25`).
  - Server bỏ lại bằng markdown-it, bỏ luôn code fence (`mv/text_utils.py`).
  - Không xử lý citation.
- **Luồng** (`web/src/lib/streamingTTS.ts`):
  1. `POST /api/voice/synthesize {text, speed}`.
  2. Server lấy trước chunk đầu để lỗi provider thành lỗi HTTP (502), rồi stream `audio/mpeg` với `Cache-Control: no-cache`, `X-Accel-Buffering: no`.
  3. Client phát bằng `MediaSource` + `SourceBuffer("audio/mpeg")`, bắt đầu sau 100 ms. Không có MSE thì tải hết thành Blob rồi phát.
- **Dừng và phát lại:** Stop hủy fetch. "Pause" thực chất là dừng; phát lại là tổng hợp lại.
- **Tốc độ:** nút đọc thủ công luôn gửi `speed=1.0`, bỏ qua cài đặt của người dùng.

### 3.6 Auto-playback và auto-listen

**Kích hoạt** (`web/src/app/app/message/messageComponents/AgentMessage.tsx:206-280`):
- `useLayoutEffect` chạy mỗi khi số packet tăng:
  - đang sinh: `streamTTS(text, false, nodeId)`;
  - hoàn tất: `streamTTS(text, true, nodeId)`.
- Chỉ áp dụng cho tin nhắn thấy tăng trưởng trong lần mount này (không phát lại lịch sử).
- Bỏ qua câu trả lời multi-model và khi người dùng hủy.
- Đổi node hoặc unmount thì reset.

**Tách đoạn** (`web/src/providers/VoiceModeProvider.tsx:112-144, 744-909`): làm sạch toàn bộ văn bản cộng dồn, xử lý phần chưa chốt.
- **Ranh giới câu** `[.!?](\s|$)` ở vị trí ≥ 10: lấy ranh giới đầu tiên ở ≥ 30, không có thì lấy ranh giới cuối ở ≥ 10.
- **Phần chưa chốt ≥ 150 ký tự:** cắt ở dấu `,;:` tại ≥ 70.
- **Phần chưa chốt ≥ 200 ký tự:** cắt ở khoảng trắng cuối trước 120, nếu > 80.
- **Fast start:** chưa gửi đoạn nào và có ≥ 20 ký tự → sau 200 ms gửi tới khoảng trắng cuối trong 50 ký tự (nếu ≥ 15).
- **Flush:** phần đuôi kết thúc bằng `.!?` → sau 250 ms gửi cả phần đuôi.
- **Hoàn tất:** gửi phần còn lại rồi `{"type":"end"}`.

**Giao thức TTS WebSocket** `/api/voice/synthesize/stream?token=`:

| Chiều | Message |
| --- | --- |
| Client → server | `{"type":"config","speed"}` (message đầu, được đọc như config); `{"type":"synthesize","text"}`; `{"type":"end"}` |
| Server → client | Binary mp3; `{"type":"audio_done"}`; `{"type":"error","message"}` (client bỏ qua) |

- **Giới hạn:** 16 KB/frame text, 4096 ký tự/đoạn.
- **Server:** bỏ markdown từng đoạn, rồi `send_text`.
  - Synthesizer OpenAI gọi một `POST /v1/audio/speech` cho mỗi đoạn, tuần tự.
  - Fallback chunked gom hết text tới `end` rồi tổng hợp một lần.

**Phát:**
- Một `MediaSource` ở chế độ `sequence`. `play()` sau 100 ms; nếu bị chính sách autoplay chặn thì thử lại ở chunk sau.
- `audio_done` → `endOfStream`, rồi poll `audio.ended` mỗi 200 ms.
- Không có MSE thì không phát gì, và cờ chờ phát có thể kẹt.

**Chữ chạy theo tiếng** (`MessageTextRenderer.tsx:115-260`):
- Chữ bị ẩn cho tới khi âm thanh bắt đầu, sau đó hiện `(currentTime + 0.28 s) / duration × tổng ký tự`.
- Mỗi frame hiện thêm tối đa 8 ký tự; đặt đúng vị trí trong markdown.
- Sau 5 s chưa có âm thanh thì trở lại stream chữ bình thường.
- Trong lúc đọc: toolbar ẩn, placeholder "Onyx is speaking...", một pill waveform có nút tắt tiếng, nút Send biến thành Stop.

**Auto-listen** (`MicrophoneButton.tsx:220-295`): mic tự nghe lại khi **tất cả** điều kiện sau đúng:
- TTS đã thực sự phát và nay đã rảnh;
- `voice_auto_playback` bật;
- người dùng đã bấm mic thủ công ít nhất một lần trong phiên (phiên mới thì mất);
- chat không đang stream, không đang ghi;
- lần dừng trước không phải dừng tay.

Chờ 400 ms rồi `startRecording()`.

**Dừng vòng lặp:**
- **Dừng tay** (không tự nghe lại): Stop, nút đọc, stop generation, hủy.
- **Gửi tin mới.**
- **Timeout phiên 5 phút.**
- **Đổi node hoặc unmount.**
- **Tắt auto-playback.**
- Lỗi WebSocket chỉ làm hết trạng thái loading, không có thông báo.

### 3.7 Settings

Settings → section "Voice" (`web/src/views/SettingsPage.tsx:1600-1665`), hiện kể cả khi voice chưa cấu hình:

| Điều khiển | Tiêu đề và mô tả | UI |
| --- | --- | --- |
| `voice_auto_send` | "Auto-Send on Pause" — "Automatically send voice input when you stop speaking." | Switch |
| `voice_auto_playback` | "Auto-Playback" — "Automatically play voice responses." | Switch |
| `voice_playback_speed` | "Playback Speed" — "Adjust the speed of voice playback." | Range 0.5–2, bước 0.1, nhãn `1.0x`; lưu khi thả chuột/phím |

- **Lưu:** optimistic, gọi `PATCH /api/voice/settings` chỉ với trường đã đổi.
- **Toast:** "Preferences saved" / "Failed to save preferences".

## 4. Provider

| | OpenAI | Azure | ElevenLabs |
| --- | --- | --- | --- |
| STT batch | `audio.transcriptions.create` (PCM bọc WAV 24 kHz; không truyền language) | REST short-audio `…/conversation/cognitiveservices/v1?language=<đầu tiên>` | `POST /v1/speech-to-text`, `model_id=scribe_v1` |
| Model STT | whisper-1, gpt-4o-transcribe, gpt-4o-mini-transcribe (chỉ HTTP) | Nhãn "default" | scribe_v2_realtime, scribe_v1 |
| STT streaming | WebSocket `/v1/realtime?model=gpt-realtime-1.5`, transcription `gpt-realtime-whisper`, `turn_detection: None`; base64 `input_audio_buffer.append`; `close()` gửi commit và chờ 5 s | Speech SDK `PushAudioInputStream` 16 kHz (resample 24→16), nhận diện ngôn ngữ tự động khi có nhiều locale | WebSocket `/v1/speech-to-text/realtime` với `commit_strategy=vad`, im lặng 1,0 s, `language_code=en` hard-code |
| TTS | `audio.speech` mp3, đọc khối 8192 byte, speed 0.25–4.0 | SSML + prosody rate, mp3 16 kHz | `POST /v1/text-to-speech/{voice}/stream` |
| TTS streaming | Hàng đợi text; mỗi đoạn một HTTP; lỗi non-200 chỉ ghi log | SDK `speak_ssml_async` mỗi đoạn | WebSocket `stream-input` với `chunk_length_schedule` |
| Model TTS | tts-1 (mặc định), tts-1-hd | "neural" | eleven_multilingual_v2 (mặc định), turbo_v2_5, monolingual_v1 |
| Giọng (tĩnh) | alloy (mặc định), echo, fable, onyx, nova, shimmer | 16 giọng Neural, Jenny mặc định | 9 giọng premade, Rachel mặc định |
| Dependency | `openai`, `aiohttp` | `azure-cognitiveservices-speech` 1.50.0 (native) | HTTP thô, không dùng SDK |

`voice/factory.py` dispatch theo `provider_type`. Interface `VoiceProviderInterface` và các protocol streaming nằm ở `voice/interface.py`.

## 5. Giới hạn, bảo mật, quan sát

| Mục | Giá trị |
| --- | --- |
| Upload STT batch | 25 MB, đọc theo khối 8 KB |
| WebSocket STT | 64 KB/frame, 25 MB/kết nối |
| WebSocket TTS | 16 KB/frame text, 4096 ký tự/đoạn |
| REST synthesize | Tối thiểu 1 ký tự, **không** có giới hạn trên; speed 0.5–2.0 |
| Ticket WebSocket | 60 s, dùng một lần, 10/phút (cửa sổ trượt vì EXPIRE được đặt lại mỗi INCR) |
| Thời gian rảnh, thời lượng phiên | Không có (chỉ có guard phiên TTS 5 phút ở client) |
| Quyền | Admin: `FULL_ADMIN_PANEL_ACCESS`. REST người dùng: `BASIC_ACCESS`. WebSocket: ticket + Origin + kiểm user, không kiểm `BASIC_ACCESS` |
| Lưu trữ | Không lưu âm thanh; không cache TTS |
| Env | `VOICE_DISABLE_STREAMING_STT`, `VOICE_DISABLE_STREAMING_FALLBACK` |

## 6. Lỗi và điểm yếu đã xác định

### Trang admin

1. Sửa từ card Whisper gửi `tts_model="whisper-1"` (`VP/shared.tsx:69-71, 210-211`), làm TTS OpenAI hỏng và mất trạng thái selected.
2. `stt_model` mặc định `whisper-1` cho cả Azure và ElevenLabs. `tts_model` của Azure/ElevenLabs bị gán bằng card id.
3. Mỗi loại provider một row; không có trường base URL cho OpenAI/ElevenLabs; private network chỉ cho Azure (issue [#11142](https://github.com/onyx-dot-app/onyx/issues/11142)).
4. Disconnect xóa cả STT lẫn TTS. `hasAlternatives` bỏ qua mode.
5. Chọn/bỏ chọn không xử lý lỗi. Bấm nhầm thân card đang chọn là bỏ chọn luôn.
6. Không có revision; không có nút Test riêng; status cache 60 s, không invalidate.

### STT

7. ScriptProcessorNode đã deprecated và âm thầm bỏ âm thanh khi tồn.
8. OpenAI streaming hard-code model realtime, không có VAD, nên Auto-Send on Pause không chạy như docs mô tả.
9. Streaming lỗi giữa chừng thì fallback chunked trên cùng socket, mất phần âm thanh đã nhận. Flush chunked transcribe lại cả bản ghi, gấp đôi chi phí.
10. ElevenLabs: final ghi đè nhau thay vì cộng dồn; `language_code=en` hard-code; chunked gửi PCM không có header WAV (suy luận).
11. Gõ phím hay bấm Send trong lúc ghi: chữ bị ghi đè, phiên ghi không dừng.

### TTS

12. Bỏ markdown hai lần (regex ở client, markdown-it theo từng đoạn ở server); không bỏ citation.
13. Azure streaming đẩy `None` sau mỗi utterance, nên `audio_done` có thể đến sớm (suy luận).
14. Client bỏ qua `{"type":"error"}`. Synthesizer OpenAI nuốt lỗi non-200.
15. Phát chỉ qua MSE; iOS Safari không tự phát được.
16. Nút đọc thủ công bỏ qua tốc độ đã cài; nhiều player có thể phát chồng nhau.

### Bảo mật, riêng tư, vận hành

17. Văn bản TTS đi vào LLM tracing. Transcript và văn bản TTS ghi log ở mức INFO. Lỗi provider hiện nguyên văn cho người dùng.
18. WebSocket không có giới hạn thời gian rảnh hay thời lượng; REST TTS không giới hạn độ dài.
19. Hành vi bật/tắt qua env; fallback được chọn bằng cách bắt exception; frontend có nhiều timer và cờ ẩn.

## 7. Khoảng trống bằng chứng

- Không quan sát trực tiếp trang cloud; ảnh docs có thể lệch với HEAD. Ví dụ ảnh modal ghi "Edit OpenAI", còn source ghi "Configure OpenAI".
- Không chạy Onyx cục bộ. Hành vi Azure/ElevenLabs và các lỗi đánh dấu *suy luận* chưa được kiểm chứng bằng provider thật.
- Bản Northstar không có trên máy; chỉ có tóm tắt trong comment MEM-91.
