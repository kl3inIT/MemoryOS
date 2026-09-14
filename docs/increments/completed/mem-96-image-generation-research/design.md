# MEM-96 — Nghiên cứu image generation cho Chat qua provider/model catalog

Trạng thái: research (giai đoạn 1). Kết quả bàn giao cho tích hợp ở [MEM-97](../mem-97-chat-image-generation/design.md).

## 1. Mục tiêu & phạm vi

Đánh giá **nên/không nên** đưa sinh ảnh (image generation) vào Chat MemoryOS, **theo phương án nào**, và **kiến trúc tích hợp** ra sao — ưu tiên **khả năng mở rộng** và **bám chuẩn của các nhà cung cấp lớn** (OpenAI, Anthropic, assistant-ui). Không hard-code một provider.

Kết luận ngắn: **nên làm**, theo mô hình **LLM = router; image-gen = một tool chạy backend; ảnh = một artifact/attachment**. Đây là điểm hội tụ của cả OpenAI, Anthropic và assistant-ui.

## 2. Bản chất: gen ảnh khác gen text thế nào

- **Text (LLM)**: autoregressive, đoán token kế tiếp, **stream từng chữ**, nhanh, rẻ.
- **Ảnh** có 2 trường phái:
  - **Diffusion** (SD/SDXL/Flux/Imagen/DALL·E/Midjourney): khởi từ nhiễu → khử nhiễu nhiều bước (20–50 steps) trong latent space theo prompt. Điều khiển bằng steps, **guidance scale (CFG)**, **seed**, sampler, **negative prompt**. "Stream" = preview mờ→nét.
  - **Autoregressive image** (OpenAI gpt-image): coi ảnh như chuỗi token ảnh (VQ codec) rồi đoán lần lượt như text → hỗ trợ tốt image-to-image, inpaint, multi-image; trả **partial images**.

Hệ quả bắt buộc lên kiến trúc:

| Khía cạnh | Text | Ảnh | Hệ quả |
|---|---|---|---|
| Độ trễ | ra chữ ngay | vài giây → vài chục giây | cần placeholder + xử lý bất đồng bộ/job |
| Đầu ra | chuỗi text | blob nhị phân (PNG/JPEG) | phải lưu attachment + trả URL, không nhét text |
| Billing | token in/out | per-image (diffusion) hoặc per-token (gpt-image) | catalog cần 2 kiểu cost model |
| Tính lặp lại | temperature | **seed** | lưu seed vào metadata |
| Phần cứng self-host | model nhỏ chạy CPU | diffusion cần **GPU/VRAM** | self-host ảnh ≠ self-host text |
| An toàn | lọc text | moderation ảnh + watermark provenance (C2PA/SynthID) | thêm tầng kiểm duyệt hình ảnh |

## 3. Cơ chế của các nhà cung cấp lớn

### Anthropic / Claude
- **Không sinh ảnh.** Docs chính thức: *"Claude is an image understanding model only... it cannot generate, produce, edit... images."* Chỉ có **vision INPUT** (đọc/hiểu ảnh, PDF).
- Cách đưa sinh ảnh vào chat Claude = **tool use / function calling** (hoặc **MCP server** bọc model ảnh): Claude tự quyết gọi tool (`tool_choice: auto`, description = tín hiệu định tuyến) → backend chạy model ảnh → trả `tool_result` (URL/handle; chỉ nhét `image` block base64 khi cần Claude "nhìn lại").
- Có thể dùng Tool Runner thay vì tự viết loop.

### OpenAI
- **Có model sinh ảnh riêng** (họ GPT-Image, autoregressive).
- **2 chế độ tích hợp**:
  - (A) **Images API** — `POST /v1/images/generations`, `/v1/images/edits`. gpt-image **luôn trả base64, không URL**; billing **theo token**.
  - (B) **Responses API `image_generation` tool** — model tự quyết gọi, tự viết lại prompt (`revised_prompt`), edit đa lượt (`previous_response_id`), streaming partial images. **Đây là cách ChatGPT làm.**
- Quan trọng: **shape Images API là chuẩn de-facto**, nhiều bên nhân bản (Azure/Together/LiteLLM/self-host) → portable. **Chế độ (B) là OpenAI-specific**, không portable.

### Kết luận cơ chế
MemoryOS **tự sở hữu orchestration tool-use** (không dùng chế độ B của OpenAI) để chạy được cho cả Claude lẫn model OpenAI-compatible. Image-gen = **một tool** mà LLM gọi; provider ảnh nằm sau một interface trong catalog.

## 4. UI: assistant-ui (đã dùng trong web MemoryOS)

Web dùng `@assistant-ui/react` 0.15.18 + `@assistant-ui/ai-sdk` + Vercel AI SDK 7.

- **Element `ImageGeneration`** (`/elements/image-generation`): khung chờ (lưới chấm nhấp nháy trên gradient mờ) khi `status==="running"`; **không nhận URL ảnh**.
- **Element `Image`** (`/elements/image`): render ảnh thật từ `ImageMessagePart` — preview zoom fullscreen, loading, **content-filter error state**, `Image.Actions` (download/copy/regenerate).
- **Guide** (`/docs/guides/image-generation`): *"needs no dedicated primitive"* — sinh ảnh ở backend → lưu `ImageMessagePart` (`image` = data:/https:/blob URL) → render bằng `Image`. **Streaming partial images & multi-image gallery = out of scope**.
- **Phiên bản 0.15.18 hỗ trợ đầy đủ**: `Image`/`File` part, `tools` map (`by_name`/`Fallback`/`Override`) hoặc `makeAssistantToolUI`, và slash-command `unstable_useSlashCommandAdapter` + `ComposerPrimitive.TriggerPopover`.

## 5. Landscape model gen ảnh (tháng 9/2026)

Không có "một model ngon nhất" — tùy tiêu chí. Số liệu leaderboard (Artificial Analysis, LMArena) snapshot 9/2026; tên biến thể/giá/license từ nguồn hỗn hợp, **verify trước khi chốt spec**.

**Top proprietary (API):**
- **OpenAI GPT Image 2.5** (Flare nhanh / Sunburst edit) — #1–#2 cả 2 arena; bám prompt tốt nhất; edit #1. **Tốn phí** (token, ~$30/1M token output).
- **Google "Nano Banana"** (Pro = Gemini 3 Pro Image; 2 = Gemini 3.1 Flash Image) — **chữ trong ảnh & identity-lock tốt nhất**; **có free tier** ở Google AI Studio.
- Nhóm sau: Microsoft MAI-Image-2.6, Reve 2.1, Meta Muse, ByteDance Seedream 5.0.
- ⚠️ **Midjourney V8.2**: đẹp nhất nhưng **không có API dùng được** → loại khỏi catalog.

**Open-weights (self-host):**
- **FLUX.2 [klein]** — nhẹ (1 GPU tiêu dùng), Apache-2.0 (verify) → cân bằng nhất.
- **Ideogram 4.0** — mới mở weights, render chữ tốt; license commercial **gated**.
- **FLUX.2 [dev]** — chất lượng open cao nhất; **non-commercial mặc định** (mua license BFL).
- **SD 3.5 / SDXL** — rẻ, VRAM vừa, hệ sinh thái **ComfyUI** trưởng thành → fallback an toàn.
- (Stability "SD4" **chưa tồn tại**; Qwen-Image 3.0 đã đóng weights.)

**Xếp theo tiêu chí (tóm tắt):** bám prompt: GPT Image 2.5 > Nano Banana Pro > Ideogram 4.0 · chữ trong ảnh: Nano Banana Pro ≈ Ideogram 4.0 · edit/consistency: GPT Image 2.5 Sunburst > Nano Banana Pro · self-host: FLUX.2 klein > SDXL · rẻ nhất/ảnh: self-host ≈ $0 sau capex GPU.

## 6. Tình trạng MemoryOS hiện tại

- **Chat LLM**: catalog khởi tạo sẵn 1 model OpenAI (baseline **GPT-5 mini**), hỗ trợ text + tool-calling + **vision INPUT**; có BYOK.
- **Embedding**: có (MEM-67) cho Search.
- **Sinh ảnh**: ❌ **chưa có model/adapter/UI**. `docs/specs/chat-models.md` ghi rõ: *"Web search and image generation remain separately configured tools. Vision input capability does not mean image generation support. Neither tool is implemented by this catalog change."*

Gap chính (từ map code, xem MEM-97 để chi tiết):
1. **Catalog adapter chỉ dành cho chat** (`ChatProviderAdapter.Client` chỉ trả `ChatModelBinding`; `validateModel` từ chối model non-streaming; không có capability image). MEM-77 **cố ý** tách image gen ra tool riêng.
2. **Stream chỉ có part text + search** — không có part ảnh/tool chung.
3. **Không có đường tải ảnh về** (S3 chỉ presign PUT; không GET/serving controller). Ghi bytes thì đã có `ObjectWriteService.stage/adopt`.
4. **Không có `CHAT_WRITE` & không có quota store cross-turn** — authz theo ownership; budget chỉ per-turn.

## 7. Khuyến nghị (ưu tiên mở rộng + theo leader)

1. **Kiến trúc**: image-gen = **đường riêng tách khỏi catalog chat** (đúng như MEM-77 đã cố ý), gọi qua **tool `generate_image`** mà LLM tự invoke; provider ảnh nằm sau một **`ImageGenerationProvider` SPI** (thêm provider = thêm bean).
2. **Model slots** (catalog đa provider):
   - **Slot mặc định (PoC/demo)**: **Google Nano Banana 2** qua Gemini API **free tier** — miễn phí, hợp gói SV. ⚠️ prompt/ảnh free tier có thể bị dùng train + watermark SynthID → **chỉ hợp demo, không hợp dữ liệu Tasco thật**.
   - **Slot self-host (riêng tư, sau)**: SDXL → FLUX.2 klein qua ComfyUI, sau một adapter OpenAI-Images.
   - **Slot chất lượng (tùy chọn)**: OpenAI GPT Image 2.5.
3. **Chuẩn hóa contract** theo OpenAI-Images cho các provider tương thích; Gemini cần adapter riêng (không phải OpenAI-shape).
4. **Metadata mỗi model trong catalog** (chính là extensibility): `hosting: external|self`, `data-leaves-tenant`, `watermark`, `license`, `min-vram`, `billing-unit: token|per-image`, `supports: edit/inpaint/multi-image`.
5. **Lưu ảnh**: private attachment (MEM-81) + ACL/quota theo Chat (MEM-93). Trả `https://` URL, **không** nhét base64 vào history.
6. **Delivery UI**: mặc định **mirror pattern search** (SSE event out-of-band + metadata) cho nhất quán/ít rủi ro; element `ImageGeneration` (chờ) → `Image` (kết quả). Progressive streaming để phase sau (out of scope assistant-ui guide).

## 8. Nguồn & caveats

- Anthropic Vision & Tool use docs (platform.claude.com); OpenAI Images API & Responses `image_generation` (developers.openai.com); assistant-ui elements/guides (assistant-ui.com).
- Leaderboard: Artificial Analysis, LMArena (9/2026).
- ⚠️ Tên model/giá/license/điều khoản free tier thay đổi nhanh — verify nguồn chính thức tại thời điểm triển khai.

## 9. Cập nhật sau khi rebase lên main mới (bàn giao cho MEM-97)

Phần "Tình trạng hiện tại" (mục 6) viết trên bản local cũ. Trên main mới, nhiều gap đã được giải quyết/scaffold — thuận lợi hơn:
- `IamCapability.IMAGE_GENERATE` **đã có** (chỉ chưa enforce) — không cần thêm capability.
- Đã có **khuôn external-provider tool** (`WebTools`/`WebProviderClient`/`WebConnection*`, migration `V49`) → mirror cho ảnh.
- Đã có `ChatFileController` + serving file; nhưng chỉ phục vụ `UserFile` (upload-từ-client).
- Image gen **vẫn chưa được implement** → MEM-97 net-new.

Quyết định triển khai (xem [MEM-97 design](../mem-97-chat-image-generation/design.md)): **Phương án B** (image-provider connection per-tenant, mirror web-search) + provider đầu **OpenAI Images (gpt-image)** + **Option B** lưu ảnh (ghi `ObjectWriteService` + endpoint serving riêng, không bẻ cong hệ `UserFile`).
