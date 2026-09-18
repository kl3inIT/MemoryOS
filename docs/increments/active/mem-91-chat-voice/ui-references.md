# MEM-91 — Tham chiếu giao diện

Design: [design.md](design.md). Nghiên cứu qua Mobbin MCP ngày 15/09/2026, nền tảng web, theo yêu cầu dựng giao diện "chuyên nghiệp enterprise" bằng control shadcn. Onyx vẫn là baseline hành vi; Mobbin chỉ quyết định cách trình bày.

## 1. Danh sách provider trong trang quản trị

| Sản phẩm   | Màn hình                                                                                 | Điều đã dùng                                                                                                 |
| ---------- | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| Braintrust | [AI providers](https://mobbin.com/screens/e4d603f8-78bc-439b-ad77-721850970ead)          | Một danh sách có viền, mỗi dòng: logo, tên, trạng thái; hành động sửa và xóa luôn hiện trên dòng đã cấu hình |
| Retool     | [Retool AI](https://mobbin.com/screens/fb50d9b9-96bc-4f79-ad46-c19672f1552c)             | Mặc định được nêu rõ; dòng chưa cấu hình có một nút thiết lập                                                |
| Vercel     | [Bring Your Own Key](https://mobbin.com/screens/6cf10946-059f-4b49-b0b6-392d4b8e9863)    | Dòng provider gọn, nút phụ bên phải, chia bằng đường kẻ                                                      |
| Adaline    | [Providers đã cấu hình](https://mobbin.com/screens/46113df2-db45-4deb-b260-0b0e565ebe45) | Dòng đã cấu hình hiện thêm chi tiết kết nối và nút xóa                                                       |
| Vapi       | [Voice configuration](https://mobbin.com/screens/13efb9e6-97fe-4fa1-8501-d01540f4aa4d)   | Tách cấu hình giọng đọc và nhận dạng thành hai phần riêng                                                    |

## 2. Hộp thoại kết nối

| Sản phẩm   | Màn hình                                                                             | Điều đã dùng                                                      |
| ---------- | ------------------------------------------------------------------------------------ | ----------------------------------------------------------------- |
| Adaline    | [Add OpenAI](https://mobbin.com/screens/3fda8202-a304-4067-818a-a3dd2f7f3037)        | Base URL không bắt buộc, gợi ý dùng địa chỉ mặc định khi để trống |
| Braintrust | [Configure API key](https://mobbin.com/screens/f357643c-dd4c-4d04-961d-3a28a0d42fe6) | Câu nói rõ khóa được mã hóa; nút kiểm tra tách khỏi nút lưu       |
| n8n        | [OpenAI credential](https://mobbin.com/screens/a5de95a4-e338-4f90-972b-c0cd31cf9f92) | Base URL cho server tương thích OpenAI                            |

## 3. Composer khi ghi âm

| Sản phẩm        | Màn hình                                                                                   | Điều đã dùng                                                          |
| --------------- | ------------------------------------------------------------------------------------------ | --------------------------------------------------------------------- |
| ChatGPT         | [Dictation](https://mobbin.com/screens/874aee2b-7f1c-4a52-9751-c36de1f13b01)               | Waveform trong khung nhập; dừng bằng nút xác nhận (✓) để chèn văn bản |
| Mistral Le Chat | [Recording](https://mobbin.com/screens/7411b9f0-68ad-4585-98bb-d3b13384de4b)               | Chấm đỏ cạnh đồng hồ `m:ss`; thanh mức âm lượng                       |
| WhatsApp Web    | [Voice note](https://mobbin.com/screens/f1ec2014-4464-461c-80ac-39162d2ccb12)              | Chấm đỏ, đồng hồ và waveform trên một dải ngang                       |
| Codecademy      | [Recording your response](https://mobbin.com/screens/11a51a31-38da-463b-a593-ece064467b66) | Trạng thái ghi âm bằng chữ trong ô nhập                               |

## 4. Tìm kiếm bằng giọng nói và cài đặt

| Sản phẩm | Màn hình                                                                         | Điều đã dùng                                                |
| -------- | -------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| Bard     | [Listening](https://mobbin.com/screens/80ee2f64-3369-4eab-bf64-f33644ffca59)     | Trạng thái nghe ngay trong ô nhập, mic giữ vị trí           |
| YouTube  | [Voice search](https://mobbin.com/screens/5e6934f8-f6f8-456d-b81f-4082fec95780)  | Chữ "Listening…" rõ ràng                                    |
| Grok     | [Voice speed](https://mobbin.com/screens/823f2ee6-21e2-4b12-a836-50e02b6efbe1)   | Slider tốc độ kèm giá trị `1.0x` — dùng cho giai đoạn 3     |
| Discord  | [Voice & Video](https://mobbin.com/screens/dbb26083-0d44-4d90-9498-c07ee9df7a21) | Cài đặt giọng nói là một mục riêng trong cài đặt người dùng |

## 5. Quyết định trình bày

- **Dòng thay cho card hover.** Onyx chỉ hiện Edit/Disconnect khi hover. Các sản phẩm enterprise ở §1 để hành động luôn hiện, dùng được bằng bàn phím và màn hình cảm ứng. MemoryOS dùng một danh sách có viền cho mỗi chức năng; hành động là `Button` (Kết nối, Cấu hình, Đặt làm mặc định) và `IconButton` xóa mở `ConfirmDialog`.
- **Trạng thái:** `StatusBadge` "Mặc định" (success), "Đã kết nối" (neutral), "Cần cấu hình thêm" (warning) khi dòng đã có khóa nhưng chưa có model hoặc giọng cho chức năng này.
- **Kiểm tra kết nối** nằm trong hộp thoại, cạnh nút lưu (Braintrust), vì lưu đã kiểm key nháp với provider.
- **Biểu tượng provider:** chỉ OpenAI có logo trong `public/provider-logos`. ElevenLabs (`AudioWaveform`), Azure AI Speech (`Cloud`) và OpenAI-compatible (`Server`) dùng icon Lucide trung tính, tránh nhúng logo thương hiệu chưa được kiểm tra nguồn.
- **TTS theo provider, không theo model.** Onyx có hai card TTS-1 và TTS-1 HD. MemoryOS chọn model và giọng trong hộp thoại, giống Vapi; một dòng cho mỗi provider khớp với một dòng `chat_voice_connection`.
- **Composer:** dải ghi âm dưới văn bản nháp gồm chấm đỏ, đồng hồ, 40 thanh mức âm lượng thật, nút tắt mic và nút dừng (✓). Placeholder đổi thành "Đang nghe…". Mic nằm cạnh Send như ChatGPT.
- **Search:** mic chỉ hiện khi Tenant có STT; trạng thái bằng chữ dưới ô tìm kiếm như trước.
- **Đọc tự động:** thanh "Đọc tự động" trên composer, cùng vị trí và kiểu với thông báo lỗi giọng nói, gồm nhịp đang đọc, trạng thái, tắt tiếng và dừng. Ô nhập hiện "MemoryOS đang đọc…" như Onyx; nút gửi vẫn giữ nguyên để gửi câu mới.

## 6. Nghiên cứu bổ sung ngày 18/09/2026

- [Vapi voice configuration](https://mobbin.com/screens/8a3359d4-1f08-46a5-a881-09cbb49dd4fa) tách Voice và Transcriber thành các capability độc lập, có fallback riêng; [Hume AI](https://mobbin.com/screens/f4e18c6b-e945-4cef-a1e1-3948ab8bf125) giữ lựa chọn model/voice trong các vùng form có tiêu đề. MemoryOS giữ hai section STT/TTS của Onyx nhưng thêm overview trạng thái và nhóm Connection / Model and voice trong dialog.
- [ChatGPT dictation](https://mobbin.com/screens/874aee2b-7f1c-4a52-9751-c36de1f13b01) giữ waveform và nút xác nhận ngay trong composer; [Mistral recording](https://mobbin.com/screens/7411b9f0-68ad-4585-98bb-d3b13384de4b) đặt thời lượng, trạng thái ghi và hành động trên một dải. Dải ghi âm MemoryOS tiếp tục nằm trong composer, không chuyển thành voice mode toàn màn hình.
- [Microsoft Copilot voice picker](https://mobbin.com/screens/cbfd2b9a-e991-4048-aa9b-4772d46cee20) dùng lựa chọn giọng dạng lưới gọn; [Grok voice settings](https://mobbin.com/screens/59b3d887-2cbb-473e-bb34-ab60844f8728) đặt tốc độ cùng voice controls. MemoryOS không thêm voice picker cho User vì giọng là mặc định cấp Tenant, nhưng gom Auto-Send, Auto-Playback và tốc độ vào cùng `SettingRows`.
- Onyx vẫn quyết định hành vi: provider mặc định độc lập theo STT/TTS, mic trong Chat/Search, read-aloud theo message, Auto-Send/Auto-Playback và audio không lưu. Những thay đổi ở đây chỉ làm rõ hierarchy, trạng thái và trust boundary.

Điều chỉnh trình bày:

- Admin header có trust badge “Audio is never stored”; hai capability cards cho biết provider mặc định và trạng thái trước danh sách chi tiết.
- Mỗi capability nằm trong `Card` có header, warning hành động được và lưới provider responsive.
- Dialog dùng hai vùng rõ: Connection (endpoint/key) và Model and voice; kiểm tra kết nối, lỗi và footer vẫn theo contract cũ.
- Settings cá nhân dùng `SettingRow` có icon; tốc độ đọc là một row responsive thay vì control rời.
- Dùng lại `Card`, `Dialog`, `StatusBadge`, `SettingRows`, `Switch`, `Slider`, `Button` và `IconButton`; không viết control mới.

## 7. Control shadcn

- Đã có sẵn và dùng lại: `Button`, `IconButton`, `StatusBadge`, `Input`, `Select`, `Checkbox`, `Label`, `Switch`, `Skeleton`, `ConfirmDialog`, `SettingsLayout`.
- **Cài thêm `dialog`** từ registry (`radix-nova`). `shadcn add dialog` đòi ghi đè `button.tsx` của MemoryOS, nên file được lấy nguyên từ `shadcn add dialog --view` và chỉ đổi:
  - import `cn` về `@/lib/utils`;
  - nút đóng dùng `IconButton` có tên truy cập đã dịch, nút đóng ở footer dùng `Button prominence="secondary"`;
  - class màu và chữ theo token MemoryOS (`surface-overlay`, `surface-scrim`, `font-heading-h3`).
- Tên export, `data-slot` và phần truy cập của Radix giữ nguyên.
- **Cài thêm `slider`** bằng `shadcn add slider` cho tốc độ đọc. Chỉ đổi:
  - import `cn` về `@/lib/utils`;
  - class màu theo token MemoryOS; trạng thái disabled dùng token nội dung và nền thay cho chỉ giảm opacity;
  - `aria-label` của Root được chuyển xuống Thumb, vì Radix đặt `role="slider"` trên Thumb.
- **Nút đọc thành tiếng** theo Grok và Onyx: `IconButton` loa cạnh Sao chép, khi đang tải hiện vòng xoay nhưng vẫn bấm được để dừng.
