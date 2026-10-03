# Critique lần 2 — 03/10/2026, 26/40

Báo cáo gốc: [../../2026-10-03T05-46-55Z__web-src.md](../../2026-10-03T05-46-55Z__web-src.md). Giao cho Việt ở [MEM-221](https://linear.app/memory-os/issue/MEM-221): sửa P1 trước, các nhóm P2 chốt phạm vi với Đạt trước khi làm.

Ảnh `1a`–`1d` chụp ở 390×844 (điện thoại), còn lại ở 1440×900.

## Lỗi ưu tiên

| Mã | Mức | Vấn đề | Ảnh |
|---|---|---|---|
| L2-1a | P1 | Trợ lý (điện thoại): thanh sửa/chia sẻ/"…" đè lên tên trợ lý và dòng mô tả đầu. | [1a](L2-1a-dien-thoai-tro-ly.png) |
| L2-1b | P1 | Thư viện (điện thoại): tên tệp bị cắt còn "bao-cao-tai-…" vì 3 nút thao tác chiếm chỗ. | [1b](L2-1b-dien-thoai-thu-vien.png) |
| L2-1c | P1 | Models (điện thoại): tên provider và URL bị ép vào khoảng 80px, URL gãy giữa chữ. | [1c](L2-1c-dien-thoai-models.png) |
| L2-1d | P1 | Cuộc họp (điện thoại): ô tìm kiếm co lại còn chữ "Tìm". | [1d](L2-1d-dien-thoai-cuoc-hop.png) |
| L2-2a | P2 | Thư viện: 5 tab, thanh dung lượng, 3 chế độ xem, tìm kiếm, lọc, sắp xếp, 2 kiểu bố cục nằm trên tệp đầu tiên. | [2a](L2-2a-thu-vien-dieu-khien-truoc-noi-dung.png) |
| L2-2b | P2 | Chi tiết cuộc họp: tóm tắt một câu nằm dưới 4 ô số liệu, thẻ chia sẻ và vùng xóa. | [2b](L2-2b-chi-tiet-cuoc-hop.png) |
| L2-3a | P2 | Menu quản trị 19 mục: nhóm "Theo dõi" khuất dưới màn hình khi ở trang khác, không có dấu hiệu cuộn. | [3a](L2-3a-menu-quan-tri.png) |
| L2-3b | P2 | "Thêm nguồn" là thao tác nhưng nằm trong menu như một trang; trang có ô tìm kiếm cho 3 ô lựa chọn. | [3b](L2-3b-them-nguon.png) |
| L2-4a | P2 | Bộ lọc mỗi trang một kiểu: select gốc (Cuộc họp, Người dùng), select tự làm (Thư viện), nút menu (Search). | [4a](L2-4a-nguoi-dung.png) · [4c](L2-4c-cuoc-hop.png) |
| L2-4b | P2 | Nút chính khác cỡ: 34px không icon ở Nguồn, 28px có icon ở Người dùng. | [4b](L2-4b-nguon.png) · [4a](L2-4a-nguoi-dung.png) |
| L2-4c | P2 | Ngày tháng có 4 định dạng: "3/10/2026", "30 thg 9, 2026", "09:19 3 thg 10, 2026", "Thứ Ba, 29 tháng 9". | [4c](L2-4c-cuoc-hop.png) |
| L2-4d | P2 | Icon header trang Nguồn là `BookOpen`, icon trên menu là `Plug` (`sources-page.tsx:45`, `admin-pages.ts:165`). | [4b](L2-4b-nguon.png) |
| L2-5a | P2 | Vô hiệu hóa người dùng: "mất quyền truy cập từ yêu cầu được bảo vệ tiếp theo", không nói là kích hoạt lại được. | [5a](L2-5a-vo-hieu-hoa.png) |
| L2-5b | P2 | "Xóa mọi cuộc chat": dialog không nêu số hội thoại sẽ xóa. | [5b](L2-5b-xoa-moi-cuoc-chat.png) |
| L2-5c | P2 | Xóa một hội thoại: không nói có vĩnh viễn không. | [5c](L2-5c-xoa-hoi-thoai.png) |
| L2-5d | P2 | Ba tên cho cùng một thứ: "hội thoại" (12 câu), "cuộc trò chuyện" (14 câu), "cuộc chat" (2 câu). | [5b](L2-5b-xoa-moi-cuoc-chat.png) |

## Lỗi nhỏ

| Mã | Vấn đề | Ảnh |
|---|---|---|
| L2-n1 | "4544", "2317" không có dấu phân cách hàng nghìn ở dải số liệu Nguồn. | [4b](L2-4b-nguon.png) |
| L2-n2 | 1 GiB hiện là "1.024 MB". | [2a](L2-2a-thu-vien-dieu-khien-truoc-noi-dung.png) |
| L2-n3 | Provider đã kết nối vẫn hiện nút "Kết nối". | [n3](L2-n3-provider-ket-noi.png) |
| L2-n4 | Thư viện trống vẫn giữ nguyên thanh công cụ cho 0 tệp. | [n4](L2-n4-thu-vien-trong.png) |
| L2-n5 | "Tìm kiếm Web" bị xám trong menu composer mà không nói lý do. | [n5](L2-n5-tim-kiem-web-xam.png) |
| L2-n6 | Ba kiểu loading: loader thương hiệu (Cuộc họp, Nguồn), skeleton (Trợ lý), dòng chữ (Models). Chỉ đọc từ code. | — |
| L2-n7 | Cột "Tiêu chuẩn" lặp ở mọi dòng Người dùng dù chỉ có một giá trị. | [4a](L2-4a-nguoi-dung.png) |
| L2-n8 | Bộ lọc Cuộc họp mất khi bấm Back (`meetings-page.tsx:62-64`). Chỉ đọc từ code. | — |
| L2-n9 | Ô chọn dòng 16×16px; tab cao 25px và chip nhãn 28px trên điện thoại. | [1b](L2-1b-dien-thoai-thu-vien.png) |
| L2-n10 | Trang hội thoại không có h1 (`app-shell-header.tsx:19`). Chỉ đọc từ code. | — |

## Không tính là lỗi

- Nguồn thất bại chỉ hiện badge "Thất bại": quyết định của owner.
- Phím tắt và chọn hàng loạt: đã có MEM-219, MEM-220.
- 37 cảnh báo của detector trong trình duyệt: báo nhầm hoặc thuộc primitive của shadcn.
