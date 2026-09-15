# MEM-97 — Tích hợp image generation vào Chat

Trạng thái: In Progress. Nghiên cứu nền: [MEM-96](../mem-96-image-generation-research/design.md). Kế hoạch/tiến độ: [plan.md](plan.md).

> Tài liệu này phản ánh trạng thái sau khi rebase lên main mới (đã có nhiều hạ tầng Chat) và sau khi chốt **Phương án B** (image-provider connection per-tenant) + **Option B** (lưu ảnh như artifact riêng).

## 1. Mục tiêu

Người dùng yêu cầu tạo ảnh trong hội thoại (LLM tự gọi tool **và** lệnh `/image`); ảnh sinh ra hiển thị inline, lưu có phân quyền, đi qua tầng provider ảnh **mở rộng được** (thêm provider = thêm adapter). Provider đầu tiên: **OpenAI Images (gpt-image)** dùng lại key OpenAI hiện có; Gemini/self-host thêm sau cùng interface.

## 2. Quyết định kiến trúc

| # | Quyết định | Lý do |
|---|---|---|
| D1 | Image-gen là **đường riêng**, KHÔNG qua chat `ChatProviderAdapter` | `ChatProviderAdapter` chỉ bind streaming `ChatModel`; MEM-77 cố ý tách image-gen ra tool riêng |
| D2 | **Phương án B**: stack connection per-tenant, mirror nguyên `chat_web_connection` (web-search) | Có tiền lệ đã chấp nhận trong repo → không phải layer speculative; đúng ưu tiên mở rộng |
| D3 | Provider đầu: **OpenAI Images**; enum `ImageProvider` mở rộng dần | Dùng lại key OpenAI; OpenAI-Images là shape chuẩn de-facto |
| D4 | Kích hoạt: tool `generate_image` (model tự gọi) **+** lệnh `/image` | Cả hai như yêu cầu |
| D5 | **Option B lưu trữ**: ghi bytes bằng `ObjectWriteService`, lưu `objectId` tham chiếu trên message, **serving qua endpoint mới có kiểm quyền** | `ObjectWriteService` là primitive ghi server-side đúng công dụng; hệ `UserFile` chỉ phục vụ file upload-từ-client (`object_uploads` ADOPTED), không có đường biến bytes server-sinh thành UserFile phục vụ được — không bẻ cong nó |
| D6 | Enforce quyền **`IMAGE_GENERATE`** (đã có sẵn trong `IamCapability`) ở turn path | Capability đã được scaffold ở increment `basic-access-capabilities` |

## 3. Trạng thái main mới (tái dùng vs phải xây)

**Tái dùng (đã có trên main):**
- Khuôn "external provider tool": `chat/tools/WebTools` + `chat/web/WebProviderClient` + `WebConnectionEntity/Service/Repository` + `WebConnectionController` + migration `V49` → mirror cho ảnh.
- Seam đăng ký tool per-turn: `ChatModelExecutor.execute` (đăng ký Artifact/Web/File/Search tool sau cờ); `ChatTurnSetup.withWeb(...)` + `ChatTurnService.sendLocked` resolve access.
- `IamCapability.IMAGE_GENERATE` đã định nghĩa (chỉ chưa enforce chỗ nào).
- `ObjectWriteService.stage/adopt` (ghi bytes server-side) + `ObjectStorage.open` (đọc bytes).
- `MeterRegistry`, `ProviderCredentials` (AES-GCM), `ModelCatalogService.validateEndpoint`.

**Phải xây:**
- Stack connection ảnh (Steps 1–4 — đã xong).
- Tool `generate_image` + wiring + enforce `IMAGE_GENERATE` (Step 5).
- Lưu ảnh (Option B) + tham chiếu trên message + endpoint serving (Step 5 + Phase 2).
- SSE image event + persist ref (Phase 2); FE render + `/image` (Phase 3–4).

## 4. Contract chính

- **Connection**: bảng `chat_image_connection` (tenant, provider, endpoint, model, credential mã hoá, active, revision) — mirror web.
- **`ImageConnectionService`**: MODELS_MANAGE-gated list/save/select/forTest/resolve; `Access(generate)`; `key(connection)` giải mã.
- **`ImageProviderClient.generate(connection, prompt, size) -> Result(bytes, mediaType, revisedPrompt)`**; adapter OPENAI_IMAGE gọi `/images/generations`.
- **REST** `/api/chat/images`: availability / list / save / select / test.
- **Tool** `generate_image(prompt, [size])` → gọi client → `ObjectWriteService.stage/adopt` → trả ack (kèm tham chiếu ảnh) cho model; ảnh surface qua SSE (Phase 2).
- **Serving** (Option B): endpoint mới `GET /api/chat/.../images/{objectId}` ownership-checked → `ObjectStorage.open`.

## 5. Bảo mật, quyền, quota

- Admin connection: `MODELS_MANAGE` + Tenant lock + stale-write.
- Turn: enforce `IMAGE_GENERATE` (+ `CHAT_WRITE` sẵn có); gate per-turn bằng cờ image + connection resolve.
- Ảnh là private; ACL bằng ownership check ở endpoint serving.
- ⚠️ Gemini free tier (nếu thêm sau): dữ liệu có thể bị dùng train + watermark → chỉ demo; dữ liệu Tasco thật → self-host.
- Credential/lỗi provider không bao giờ vào output model/UI; meter `memoryos.chat.image.request`.

## 6. Scope control

- Bám khuôn web-search có sẵn thay vì phát minh mới.
- Non-streaming trước; progressive streaming/multi-image = sau.
- Chỉ thêm provider đã implement (OPENAI_IMAGE); không predeclare provider rỗng.
