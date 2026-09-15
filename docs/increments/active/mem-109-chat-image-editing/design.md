# MEM-109 — Sửa ảnh trong Chat

Trạng thái: In Progress. Linear: [MEM-109](https://linear.app/memory-os/issue/MEM-109). Nền: [MEM-97](../../completed/mem-97-chat-image-generation/design.md) (sinh ảnh). Kế hoạch: [plan.md](plan.md).

## 1. Vấn đề

MEM-97 chỉ sinh ảnh một chiều:

- Ảnh tải lên chỉ tới chat model dưới dạng vision context; không có đường nào đưa ảnh vào bộ sinh ảnh.
- `ImageProviderClient` chỉ có `generate()`; `GenerateImageTool` ghi rõ không dùng để sửa ảnh.
- `flux-1-schnell` là text-to-image, không nhận ảnh đầu vào.
- Lịch sử gửi cho model giữ câu trả lời trước dưới dạng text; id ảnh đã sinh bị mất ở lượt sau.

Hệ quả: "giữ nguyên toàn bộ chi tiết, chỉ đổi màu áo" bị vẽ lại thành một ảnh khác.

## 2. Mục tiêu

- **(A)** Sửa ảnh vừa tải lên.
- **(B)** Sửa ảnh đã sinh ở lượt trước.
- **(C)** Sửa trong vùng người dùng chọn (mask); ngoài vùng giữ nguyên từng pixel.

## 3. Bằng chứng (probe Workers AI, 2026-09-15)

Schema lấy từ `GET /accounts/{id}/ai/models/schema`; chạy thật trên ảnh chân dung 1024×1024 do `flux-1-schnell` sinh.

| Model | Giao thức | Kết quả |
|---|---|---|
| `@cf/black-forest-labs/flux-2-klein-4b` | multipart `prompt` + `input_image_0`; JSON `result.image` (base64 JPEG) | 5,8 s; giữ mặt, tóc, dáng, ánh sáng, nền; chỉ đổi màu áo |
| `@cf/runwayml/stable-diffusion-v1-5-inpainting` | JSON `image`/`mask` uint8[]; binary PNG | 7–16 s; áo giữ màu cũ, in chữ hoặc hoa văn lạ; mặc định 512 px |
| `@cf/runwayml/stable-diffusion-v1-5-img2img` | — | schema 404, không còn phục vụ |

## 4. Quyết định

| # | Quyết định | Lý do |
|---|---|---|
| D1 | Tool `edit_image` riêng, cạnh `generate_image` | Mô tả tool rõ cho model; hợp đồng generate giữ nguyên |
| D2 | Cloudflare sửa bằng `flux-2-klein-4b` cho mọi lần sửa; không dùng SD 1.5 | §3 |
| D3 | `OPENAI_IMAGE` sửa qua `POST {base}/images/edits` multipart; `input_fidelity=high` cho model `gpt-image-*` | Cùng hợp đồng provider, không đổi cấu hình connection |
| D4 | Mask xử lý phía server bằng ghép ảnh: provider sửa toàn ảnh theo lệnh, server chỉ lấy kết quả trong vùng trắng của mask (biên làm mềm) và giữ ảnh gốc ngoài vùng | Một ngữ nghĩa mask cho mọi provider; bảo đảm ngoài vùng giữ nguyên; mask phía provider chỉ mang tính gợi ý |
| D5 | Trạng thái do app quản lý: nguồn sửa là artifact ảnh trong cùng session của owner, hoặc file đính kèm thuộc ngữ cảnh lượt | Provider-agnostic (pattern Onyx); không dựa `previous_response_id` |
| D6 | Model tham chiếu id có trong hội thoại: file đính kèm đã mang id trong metadata; câu trả lời trước ghi `image_id` của ảnh đã sinh | Model không phải đoán hay bịa id |
| D7 | Mask là PNG tải lên qua pipeline file chat sẵn có, tên `mask-for-<image_id>.png`; server lấy ảnh đích từ tên mask khi có | Không đổi hợp đồng API; nhắm đích xác định kể cả khi model truyền sai id |
| D8 | Ảnh làm việc chuẩn hoá: cạnh dài tối đa 1024 px, kích thước bội số 16, PNG; giải mã bằng ImageIO có giới hạn pixel; chỉ PNG/JPEG | Chi phí tile của klein; an toàn giải mã (`java.desktop` đã có trong runtime, xem `ChatImageExtractor`) |
| D9 | Lineage `source_artifact_id`/`source_file_id` trên `chat_image_artifact` (V62), không khoá ngoại | Truy vết; nguồn có thể bị xoá sau |
| D10 | Dùng lại `IMAGE_GENERATE` | Không mở thêm quyền |

## 5. Hợp đồng

- **Tool** `edit_image(imageId, prompt, maskId?)`: tối đa 4 lần mỗi câu trả lời; phát `ChatImageEvent` như generate; ảnh kết quả là artifact mới trên câu trả lời hiện tại, trả `image_id` cho model.
- **Provider** `ImageProviderClient.edit(connection, prompt, image)` → `Result`; Cloudflare gọi `/ai/run/@cf/black-forest-labs/flux-2-klein-4b`, OpenAI gọi `/images/edits`.
- **Ảnh** chuẩn hoá và ghép mask trong `core/chat/image` (thuần Java2D).
- **Lưu trữ** `ImageArtifactService.sessionImage(...)` đọc nguồn; `store(..., sourceArtifactId, sourceFileId)` ghi lineage.
- **Lịch sử** `TurnContext.generatedImages` (message id → artifact id) được nạp trong `ChatTurnPersistence`; `ChatTurnSetup` thêm dòng `image_id` vào câu trả lời trước.
- **Frontend** nút "Sửa ảnh" trên ảnh đã sinh → hộp thoại tô vùng (tuỳ chọn) và nhập lệnh → đính kèm `mask-for-<id>.png`, bật chế độ ảnh, gửi qua composer.

## 6. Bảo mật

- Nguồn sửa chỉ là artifact trong session của owner (`JdbcImageArtifactRepository.inSession`) hoặc file đính kèm thuộc ngữ cảnh lượt (`ChatTurnSetup.fileIds()`) mà owner sở hữu và đã READY (`ChatFileContentService.image`).
- Ảnh không tin cậy được giải mã có giới hạn kích thước và số pixel; mọi đầu vào gửi provider là PNG do server mã hoá lại.
- Lỗi provider và credential không đi vào output của model hay UI; meter `memoryos.chat.image.request` giữ nhãn provider/outcome.

## 7. Hạn chế đã biết

Klein sửa toàn ảnh nên tông nền và vị trí chủ thể có thể xê dịch nhẹ. Khi có mask, pixel ngoài vùng tô vẫn giữ nguyên tuyệt đối, nhưng nét tô cắt qua vùng nền mịn có thể lộ đường nối (kiểm chứng live: dải ngang cắt qua nền và cổ cho vạch rõ). Biên được làm mềm vào trong khoảng 1,2% cạnh dài để giảm vạch; không tô cho kết quả liền mạch nhất. Cân bằng tông màu quanh biên là hướng cải thiện sau nếu cần.

## 8. Ngoài phạm vi

Tự động tạo mask, ghép nhiều ảnh, nút sửa trên ảnh tải lên (sửa bằng câu lệnh trong chat vẫn hoạt động), WebP làm nguồn sửa.
