# MEM-97 — Kế hoạch triển khai (Phương án B + Option B)

Xem [design.md](design.md). Mỗi step commit riêng, compile/`clean check` trước khi commit. Nhánh: `phamnhatanh811/mem-97-chat-image-generation`.

## Phase 1 — Backend image-provider stack + tool

- ✅ **Step 1** — Schema: `V54__chat_image_connections.sql` + `ImageProvider` enum + `ImageConnectionEntity` + repository. (`8c5686c4`)
- ✅ **Step 2** — `ImageConnectionService` (MODELS_MANAGE CRUD/select/resolve, credential mã hoá, stale-write). (`4ac63f5c`)
- ✅ **Step 3** — `ImageProviderClient` + `ImageHttp` (adapter OpenAI Images, decode b64 → PNG, meter). (`37ac07df`)
- ✅ **Step 4** — REST `/api/chat/images` (`ImageConnectionController` + DTO). (`ee44eb65`)
- ⏳ **Step 5** — tool `generate_image` + wiring:
  - `GenerateImageTool` (`@LlmTool`) mirror `WebTools`; gọi `ImageProviderClient`.
  - Lưu ảnh (**Option B**): `ObjectWriteService.stage/adopt` → giữ `StoredObjectReference`; trả ack + tham chiếu cho model.
  - Wiring: inject client + resolve access vào `ChatModelExecutor`; `ChatTurnSetup.withImage(...)`; resolve trong `ChatTurnService.sendLocked`.
  - Enforce `IMAGE_GENERATE` ở turn path; guidance trong `ChatPrompts`.
- ⏳ **Step 6** — tests (service authz, client mock HTTP, tool) + regenerate `openapi.yml`.

## Phase 2 — Stream + persist

- SSE `image` event xuyên `StreamBufferWriter → ChatEventStream → ChatStreamController` (OpenAPI `oneOf`).
- Thread tham chiếu ảnh vào turn `Outcome` + `finishAndRead` để persist trên assistant message; trả trong `ChatMessageResponse`.
- Endpoint serving có kiểm quyền cho `objectId` ảnh (Option B) → `ObjectStorage.open`.

## Phase 3 — Frontend hiển thị

- Element `image-generation` (chờ) + `Image` (kết quả); wire trong `chat-thread.tsx` / `web/src/features/chat/`.
- `chat-transport.ts` xử lý SSE `image` event (metadata) + rehydrate history; i18n (MEM-74).

## Phase 4 — Lệnh `/image`

- `unstable_useSlashCommandAdapter` + `ComposerPrimitive.TriggerPopover` (char `/`) trong composer.

## Phase 5 — Hoàn thiện

- Quota (tùy), moderation, cập nhật `docs/specs/chat*.md` + `ARCHITECTURE.md` + verification matrix; `clean check` xanh; chuyển increment sang `completed/` khi PR merge.

## Rủi ro

- Serving/persist ảnh (Option B) cần endpoint + ref mới — verify khi chạy được app.
- API `unstable_` slash-command có thể đổi → pin bằng test.
- Tên model/giá OpenAI verify tại thời điểm code.
