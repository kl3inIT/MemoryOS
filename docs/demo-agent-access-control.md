# Demo: Trợ lý (Agent) — thành viên chỉ thấy dữ liệu mình có quyền đọc

Môi trường demo: https://memoryos.72-62-193-33.nip.io — trang Trợ lý tại `/agents`.

## Mục tiêu cần chứng minh

1. Thành viên được chia sẻ có thể **xem và chat** với trợ lý (quyền "Xem và chat").
2. Khi chat, dữ liệu trả về **chỉ gồm các tài liệu người hỏi có quyền truy cập** — không phải toàn bộ Nguồn/Bộ tài liệu đã gắn vào trợ lý.

## Nguyên tắc nền (dùng để giải thích với khán giả)

- Nguồn và Bộ tài liệu gắn vào trợ lý chỉ **thu hẹp** phạm vi tìm kiếm, **không cấp quyền** đọc.
- Mỗi lượt hỏi, hệ thống tính: *(nguồn của trợ lý)* ∩ *(nguồn người hỏi được đọc)* ∩ *(quyền từng tài liệu)*.
- Nếu giao kết quả rỗng → trợ lý không tìm thấy gì, không rò rỉ dữ liệu.
- Quyền được tính lại **mỗi lượt hỏi**: thu hồi quyền có hiệu lực ngay ở câu hỏi tiếp theo.

## Chuẩn bị (tài khoản quản trị, ~15 phút)

### 1. Tài khoản và nhóm

| Vai trò | Tài khoản | Thuộc nhóm |
| --- | --- | --- |
| Quản trị | admin (tạo dữ liệu và trợ lý) | — |
| Thành viên A | người thuộc phòng Kinh doanh | **Phòng Kinh doanh** |
| Thành viên B | người ngoài phòng Kinh doanh | — |

- Tạo nhóm **Phòng Kinh doanh** tại `/admin/groups` → **Nhóm mới**, thêm A làm thành viên. B không thuộc nhóm nào.

### 2. Hai nguồn dữ liệu (`/admin/sources`)

| Nguồn | Loại | Quyền truy cập | Nội dung gợi ý |
| --- | --- | --- | --- |
| Nội quy & Chính sách chung | Tệp (FILE) | **Công khai** | nội quy làm việc, quy định remote, phúc lợi |
| Tài liệu Kinh doanh | Tệp (FILE) | **Riêng tư** — liên kết nhóm Phòng Kinh doanh | bảng giá, chính sách chiết khấu, mẫu hợp đồng |

- Tải 2–3 tệp lên mỗi nguồn; chờ trạng thái lập chỉ mục hoàn tất trước khi demo.
- Nguồn Riêng tư: chọn nhóm **Phòng Kinh doanh** trong phần liên kết nhóm.

### 3. Trợ lý (`/agents` → **Tạo trợ lý**)

- **Thông tin chung**: tên "Trợ lý Nội bộ", mô tả ngắn.
- **Hướng dẫn**: "Trả lời dựa trên tài liệu nội bộ, luôn trích dẫn nguồn. Nếu không có tài liệu phù hợp, nói rõ là không tìm thấy."
- **Tri thức**: chọn **cả hai nguồn** trên (đây là điểm then chốt — trợ lý "nhìn thấy" cả hai, nhưng người hỏi thì không).
- **Công cụ**: bật tìm kiếm tài liệu.
- **Câu hỏi gợi ý**:
  - "Bảng giá mới nhất là bao nhiêu?"
  - "Nội quy quy định gì về làm việc từ xa?"

### 4. Chia sẻ trợ lý

- Mở **Chia sẻ trợ lý** → **Thêm người hoặc Group** → thêm A và B với vai trò **Xem và chat** → Lưu.
- (Cách khác: bật **Công khai** — "Mọi người trong tổ chức có thể xem và chat".)

## Kịch bản demo

### Cảnh 1 — Thành viên thấy và mở được trợ lý được chia sẻ

1. Đăng nhập B → vào `/agents` → tab **Được chia sẻ** (hoặc **Tất cả** nếu trợ lý công khai).
2. Thẻ "Trợ lý Nội bộ" hiện ra → bấm **Bắt đầu chat**.
3. Nhấn mạnh với khán giả: B xem được mô tả, câu hỏi gợi ý và *tên* các nguồn trong cấu hình — nhưng tên nguồn không cấp quyền đọc nội dung.

### Cảnh 2 — Cùng một câu hỏi, hai kết quả khác nhau (điểm chính)

1. **B hỏi**: "Bảng giá mới nhất là bao nhiêu?" → trợ lý trả lời không tìm thấy tài liệu; phần **Nguồn trích dẫn** không có tài liệu nào từ "Tài liệu Kinh doanh".
2. **A hỏi cùng câu** → trợ lý trả lời đầy đủ, trích dẫn tới tệp trong nguồn "Tài liệu Kinh doanh"; bấm trích dẫn mở được nội dung.
3. Kết luận: cùng một trợ lý, cùng bộ nguồn gắn vào — kết quả phụ thuộc quyền của **người hỏi**, không phải quyền của trợ lý.

### Cảnh 3 — Dữ liệu công khai ai cũng thấy

- Cả A và B hỏi: "Nội quy quy định gì về làm việc từ xa?" → cả hai nhận câu trả lời kèm trích dẫn từ nguồn Công khai.

### Cảnh 4 (tuỳ chọn) — Thu hồi quyền có hiệu lực ngay

1. Admin gỡ A khỏi nhóm Phòng Kinh doanh (`/admin/groups`).
2. A hỏi lại câu về bảng giá → không còn thấy tài liệu Kinh doanh nữa.
3. Thêm A lại vào nhóm → quyền được khôi phục ở lượt hỏi tiếp theo.

## Câu hỏi thường gặp khi demo

- **"Thấy tên nguồn trong cấu hình trợ lý có phải rò rỉ?"** — Không. Tên nguồn hiển thị trong phần cấu hình nhưng không cấp quyền tìm kiếm hay đọc; nội dung tài liệu vẫn bị lọc theo quyền của người hỏi.
- **"Tệp đính kèm trực tiếp vào trợ lý thì sao?"** — Khác biệt có chủ đích: tệp đính kèm vào trợ lý được **mọi người dùng trợ lý** đọc trong lượt chat (theo thiết kế tham chiếu Onyx). Vì vậy demo này dùng Nguồn/Bộ tài liệu — **không** đính kèm tệp trực tiếp vào trợ lý.
- **"Bộ tài liệu (Document Set) khác gì?"** — Chỉ là cách gom nhiều nguồn thành một bộ lọc (`/admin/document-sets`); cũng không cấp quyền. Có thể gắn Bộ tài liệu vào trợ lý thay cho từng nguồn — hành vi lọc quyền giống hệt.
- **"Nguồn Google Drive?"** — Nguồn Drive ở chế độ **Auto Sync** lọc theo đúng quyền Google của từng người đọc (khớp email đã xác minh): hai người cùng chat một trợ lý có thể thấy các tệp khác nhau *trong cùng một nguồn*.
- **"Người được chia sẻ có sửa được trợ lý không?"** — Không, vai trò "Xem và chat" chỉ cho dùng. Muốn cho sửa thì chọn vai trò **Chỉnh sửa** trong hộp thoại Chia sẻ.

## Checklist trước giờ demo

- [ ] Cả hai nguồn đã lập chỉ mục xong (kiểm tra trạng thái tại `/admin/sources`)
- [ ] A thuộc nhóm Phòng Kinh doanh; B không thuộc nhóm nào
- [ ] Trợ lý đã chia sẻ/công khai; cả A và B đều thấy tại `/agents`
- [ ] Đã chạy thử cả 3 câu hỏi với cả 2 tài khoản
- [ ] Hai trình duyệt (hoặc profile) đăng nhập sẵn A và B để chuyển nhanh
