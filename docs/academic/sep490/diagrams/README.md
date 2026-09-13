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
