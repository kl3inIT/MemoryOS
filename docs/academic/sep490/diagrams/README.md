# Sơ đồ MemoryOS — bản tiếng Việt

Project chỉnh sửa: [MemoryOS.vpp](MemoryOS.vpp).

- [01 — Bối cảnh hệ thống](memoryos-context-compact.png): 5 tác nhân/hệ thống ngoài và 12 luồng thông tin. Người quản trị là vai trò tổng hợp; quyền thực tế xét theo tác vụ, gồm SOURCES_MANAGE và MODELS_MANAGE.
- [02 — Nạp và xử lý tài liệu](memoryos-document-flow.png): xác minh quyền/cấu hình, nhận nội dung, trích xuất, công bố và theo dõi lỗi.
- [03 — Tìm kiếm và xem bằng chứng](memoryos-search-flow.png): phạm vi được phép, kết quả rỗng, chọn bằng chứng và kiểm tra lại quyền.
- [04 — Hỏi đáp và trích dẫn](memoryos-chat-flow.png): quyền hội thoại/mô hình, truy xuất bằng chứng, sinh trả lời, lỗi/dừng và xem nguồn; có thể kết thúc mà không chọn trích dẫn.

## Phạm vi và nguồn đối chiếu

F1: người dùng tìm kiếm, hỏi đáp và đọc bằng chứng — specs Chat/Search. F2: xác thực qua Keycloak, phân quyền do MemoryOS — spec Identity. F3: nạp dữ liệu Google Drive — spec Connector. F4: mô hình nhận ngữ cảnh được phép — spec Chat Models. F5: quản lý nguồn, thành viên/quyền và cấu hình mô hình — specs Connector/Identity/Chat Models.

Các sơ đồ là bản trình bày nghiệp vụ rút gọn để review, chưa thay thế SRS đầy đủ hoặc nghiệm thu với giảng viên. Luồng nạp gộp các bước FILE/Drive ở mức tổng quan; không thể hiện mọi retry/recovery. Search/Chat vẫn phải tuân theo chính sách nguồn hiện tại trong specs; việc có Google Drive trên context không khẳng định mọi nguồn Drive đã được phép truy vấn. Bằng chứng thiếu không đồng nghĩa hệ thống luôn từ chối trả lời; trích dẫn chỉ có khi có tham chiếu nguồn hợp lệ. Việc sinh và truyền trả lời diễn ra theo stream; sơ đồ tổng hợp trình bày kết quả và xử lý dừng/lỗi.

Context đã qua kiểm tra native: 1 tiến trình, 5 external entities, 12 luồng, không có vi phạm được validator báo. Đã kiểm tra ảnh các luồng và sửa nhãn nhánh/đường đi. File VPP là bản chỉnh sửa chính; bốn ảnh PNG là bản xem trước. Các JSON dựng thử, log kiểm tra và bản xuất cũ không lưu trong Git.

Ảnh PNG xuất trực tiếp có watermark do Visual Paradigm Evaluation Copy; dùng để xem trước, chưa phải bản nộp cuối. Dùng sơ đồ 01–04 trong project; sơ đồ có tiền tố “Bản cũ” chỉ là bản lưu tham khảo.

## R2 — Quy trình phát triển (draw.io)

Bản chỉnh sửa: [r2-process-methodology.drawio](r2-process-methodology.drawio), mở bằng draw.io desktop hoặc app.diagrams.net. File có hai trang, đều là UML activity diagram cho mục II.1 của R2. Nhãn viết bằng tiếng Anh để dùng trong bản nộp.

- [Quy trình Waterfall](r2-waterfall-process.png) (trang 1): 6 phase nối tiếp. Đầu ra của mỗi phase là object node, sau đó là review gate: đạt thì sang phase sau, chưa đạt thì làm lại phase đó.
- [Thực hiện một issue](r2-implementation-issue-flow.png) (trang 2): các bước từ issue Linear đến staging. Nếu review hoặc CI Gate chưa đạt thì quay lại bước code. Sơ đồ vẽ review pull request là điều kiện bắt buộc trước khi merge. Hiện `main` chưa bắt buộc review.

Hai ảnh PNG được chụp ở tỉ lệ 2x bằng draw.io viewer. Khi sửa file `.drawio` thì xuất lại PNG.

## R4 — Thiết kế phần mềm (archify)

Bản chỉnh sửa là file JSON, dựng bằng skill archify: `node .claude/skills/archify/bin/archify.mjs deliver architecture <file>.json <file>.html --quality showcase`. Nhãn viết bằng tiếng Anh. Phạm vi là FE-01 đến FE-07 của Report 3, có Lark là một nguồn tài liệu; không vẽ bot, channels hay sandbox.

- [Kiến trúc phần mềm](r4-software-architecture.png) (mục I.1): nguồn [r4-software-architecture.json](r4-software-architecture.json), bản xem [r4-software-architecture.html](r4-software-architecture.html). Khung nét đứt là phần nhóm tự chạy; hệ thống ngoài nằm ngoài khung.

PNG chụp ở tỉ lệ 2x từ bản HTML, nền trắng, ẩn thanh công cụ và chú giải của viewer. Khi sửa JSON thì dựng lại HTML rồi chụp lại PNG.

- [Package diagram](r4-package-diagram.png) (mục I.2): archify chưa có loại package diagram nên hình này vẽ tay bằng SVG theo ký hiệu UML (package có tab, phụ thuộc `«use»`). Nguồn là script [r4-package-diagram.mjs](r4-package-diagram.mjs); chạy `node docs/academic/sep490/diagrams/r4-package-diagram.mjs` để sinh [r4-package-diagram.html](r4-package-diagram.html), rồi chụp phần tử `#diagram` ở tỉ lệ 2x thành PNG. Bốn tầng của `memoryos-core` tóm tắt `allowedDependencies` trong các `package-info.java` sau khi bỏ cạnh bắc cầu (49 còn 18). Quy ước đặt tên class nằm trong Table 2 của report, không vẽ trong hình.

- ERD (mục I.3), bốn hình theo vùng: [Identity and access](r4-erd-identity.png), [Sources and documents](r4-erd-sources.png), [Chat and AI administration](r4-erd-chat.png), [Meetings and MCP](r4-erd-meetings.png). Nguồn của mỗi hình là `r4-erd-<vùng>.json`, sinh bằng [r4-erd.py](r4-erd.py) từ schema thật của staging (`pg_dump --schema-only --no-owner --no-privileges -d memoryos` trong container `memoryos-postgres`; file dump không lưu trong Git): `python r4-erd.py <schema.sql> <identity|sources|chat|meetings> r4-erd-<vùng>.json`, rồi `archify finalize erd` và chụp PNG 2x vùng các bảng. Mỗi bảng chỉ hiện cột khoá và vài cột nghiệp vụ; khoá ngoại ghép được đánh trên cột trỏ tới `id` của bảng đích, unique ghép hoặc unique một phần không đánh UK. Một vài bảng kỹ thuật chỉ vẽ đường cho quan hệ chính (tham số thứ năm trong `AREAS`); các khoá ngoại khác của chúng vẫn có nhãn FK.
