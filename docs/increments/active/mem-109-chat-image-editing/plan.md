# MEM-109 — Kế hoạch triển khai

Xem [design.md](design.md). Nhánh: `phamnhatanh811/mem-109-chat-image-editing`. Compile và chạy test liên quan trước mỗi commit.

## Phase 1 — Backend

- ✅ Probe Workers AI: schema chính thức và chạy thật klein/SD 1.5 (design §3); klein nhận PNG, tôn trọng `width`/`height`, chấp nhận cả 48×48.
- ✅ `V62__chat_image_artifact_lineage.sql`; `JdbcImageArtifactRepository.inSession` và insert có lineage; `ImageArtifactService.sessionImage`/`store(..., lineage)`.
- ✅ `TurnContext.generatedImages` nạp trong `ChatTurnPersistence`; `ChatTurnSetup` ghi `image_id` vào câu trả lời trước.
- ✅ `ImageHttp.postMultipart`.
- ✅ `ImageEditImages`: giải mã PNG/JPEG có giới hạn, chuẩn hoá ≤1024 px bội 16, mask làm mềm biên vào trong, ghép giữ nguyên ngoài vùng.
- ✅ `ImageProviderClient.edit` (Cloudflare klein, OpenAI edits); timer gắn `operation`.
- ✅ `EditImageTool` + đăng ký trong `ChatModelExecutor` + guidance `ChatPrompts`.
- ✅ Test: `ImageEditImagesTest`, `ImageProviderClientTest`, `EditImageToolTest`, `ChatTurnSetupTest`, `ChatWebPromptsTest`, `ChatPersistenceIntegrationTest`.
- ⏳ Verify local với Cloudflare thật: sửa ảnh đã sinh, ảnh tải lên, có mask.

## Phase 2 — Frontend

- ✅ Nút "Sửa ảnh" trên ảnh đã sinh; hộp thoại tô vùng (canvas, tuỳ chọn) và nhập lệnh; xuất `mask-for-<id>.png`; bật chế độ ảnh qua `ChatImageEditContext`; điền vào composer của thread.
- ✅ Timeline hoạt động hiển thị `edit_image`; i18n; vitest.

## Phase 3 — Hoàn thiện

- ✅ Cập nhật `docs/specs/chat.md`, `docs/tests/chat.md`.
- ⏳ `./gradlew clean check` và `pnpm check` xanh.
- ⏳ PR vào main, CI xanh mới merge; verify trên staging; chuyển increment sang `completed/`, cập nhật roadmap.

## Rủi ro

- Giới hạn neuron free tier (10k/ngày): mỗi lần sửa 1024 px khoảng vài trăm neuron.
- Mask nhắm đích qua tên file; server vẫn kiểm tra quyền trên cả nguồn và mask.
- Model không có vision không thấy nội dung mask, nhưng vẫn đọc được tên và id của nó.
