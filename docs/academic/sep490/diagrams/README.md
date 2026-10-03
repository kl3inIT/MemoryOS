# Sơ đồ MemoryOS — bản tiếng Việt

Project chỉnh sửa: [MemoryOS.vpp](MemoryOS.vpp).

- [01 — Bối cảnh hệ thống](memoryos-context-compact.png): 5 tác nhân/hệ thống ngoài và 12 luồng thông tin. Người quản trị là vai trò tổng hợp; quyền thực tế xét theo tác vụ, gồm SOURCES_MANAGE và MODELS_MANAGE.
- [02 — Nạp và xử lý tài liệu](memoryos-document-flow.png): xác minh quyền/cấu hình, nhận nội dung, trích xuất, công bố và theo dõi lỗi.
- [03 — Tìm kiếm và xem bằng chứng](memoryos-search-flow.png): phạm vi được phép, kết quả rỗng, chọn bằng chứng và kiểm tra lại quyền.
- [04 — Hỏi đáp và trích dẫn](memoryos-chat-flow.png): quyền hội thoại/mô hình, truy xuất bằng chứng, sinh trả lời, lỗi/dừng và xem nguồn; có thể kết thúc mà không chọn trích dẫn.

## Context Diagram (tiếng Anh)

[Context Diagram - MemoryOS](context-diagram.png): 9 tác nhân/hệ thống ngoài và 18 luồng thông tin quanh MemoryOS. Nét liền là luồng đi vào MemoryOS, nét đứt là luồng MemoryOS trả ra.

Sơ đồ vẽ bằng hình tự do (oval, chữ nhật bo góc, generic connector) trên một class diagram vì Visual Paradigm Community Edition không tạo được Data Flow Diagram; nó không phải DFD native nên `validateContextDiagram` không áp dụng. Trong model, mọi connector đi từ thực thể ngoài tới MemoryOS; chiều của luồng nét đứt thể hiện bằng đầu mũi tên. Lark nằm trong phạm vi báo cáo nhưng chưa có trong code (MEM-118).

## Main Business Flow cho Report 3 (tiếng Anh, swimlane ngang)

Sơ đồ BF-01 đến BF-07 trong project, bám phạm vi FE-01 đến FE-08 của Report 1/2 và đã đối chiếu với specs ngày 30/09/2026. Ảnh PNG là bản xem trước.

| BF | Sơ đồ | Tính năng | Trigger | End condition |
| --- | --- | --- | --- | --- |
| BF-01 | [Member Onboarding](bf-01-member-onboarding.png) | FE-04, FE-08 | Administrator invites a new member by email | The member is signed in and uses the features their groups allow |
| BF-02 | [Source Connection and Document Reading](bf-02-source-connection.png) | FE-01, FE-05 | Source manager uploads files or connects Google Drive, SharePoint or Lark | Documents are searchable and each result is recorded; a connected source keeps refreshing until it is paused or deleted |
| BF-03 | [Permission-Aware Search](bf-03-permission-aware-search.png) | FE-02 | Member enters keywords or a question | Member reads an allowed passage, or sees why it is not available |
| BF-04 | [Chat with Citations](bf-04-chat-with-citations.png) | FE-03, FE-07 | Member asks a question in chat | Member checks the cited passage, or sees the limit, blocked-topic or no-evidence message |
| BF-05 | [AI Model and Usage Management](bf-05-ai-model-usage.png) | FE-06, FE-07 | Model manager adds an AI provider | Default model and usage limits are saved and usage and cost are reviewed |
| BF-06 | [Audit Trail](bf-06-audit-trail.png) | FE-08 | Administrator changes members, groups, access rules, sources or AI settings | Auditor has reviewed the events and, if needed, downloaded the CSV file |
| BF-07 | [Personal File Library](bf-07-personal-file-library.png) | FE-03 | Member uploads a file to the personal library | The file is used in chat, kept because it is in use, restored, or deleted permanently |

Ghi chú khi đưa vào báo cáo:

- BF-02: tệp tải lên được đọc ngay từng tệp, không có sync run và không đồng bộ theo lịch; Google Drive và SharePoint đồng bộ lại mỗi 30 phút theo mặc định. Auto Sync chỉ dùng cho Google Drive.
- BF-04: MemoryOS chạy tìm kiếm khi mô hình gọi công cụ tìm kiếm. Bước kiểm tra trích dẫn áp dụng khi bật chế độ chỉ trả lời từ tài liệu công ty; kiểm tra chủ đề bị chặn chỉ chạy khi đã cấu hình.
- BF-05: hạn mức "each person" là một ngân sách áp cho từng người; hạn mức chỉ chặn lượt chat mới.
- Lark nằm trong phạm vi Report 1/2 nhưng chưa có trong code và spec (MEM-118). Mức INTERNAL/EXTERNAL của nhà cung cấp mới được ghi nhận và hiển thị; việc thực thi thuộc MEM-134.

## Phạm vi và nguồn đối chiếu

F1: người dùng tìm kiếm, hỏi đáp và đọc bằng chứng — specs Chat/Search. F2: xác thực qua Keycloak, phân quyền do MemoryOS — spec Identity. F3: nạp dữ liệu Google Drive — spec Connector. F4: mô hình nhận ngữ cảnh được phép — spec Chat Models. F5: quản lý nguồn, thành viên/quyền và cấu hình mô hình — specs Connector/Identity/Chat Models.

Các sơ đồ là bản trình bày nghiệp vụ rút gọn để review, chưa thay thế SRS đầy đủ hoặc nghiệm thu với giảng viên. Luồng nạp gộp các bước FILE/Drive ở mức tổng quan; không thể hiện mọi retry/recovery. Search/Chat vẫn phải tuân theo chính sách nguồn hiện tại trong specs; việc có Google Drive trên context không khẳng định mọi nguồn Drive đã được phép truy vấn. Bằng chứng thiếu không đồng nghĩa hệ thống luôn từ chối trả lời; trích dẫn chỉ có khi có tham chiếu nguồn hợp lệ. Việc sinh và truyền trả lời diễn ra theo stream; sơ đồ tổng hợp trình bày kết quả và xử lý dừng/lỗi.

Context đã qua kiểm tra native: 1 tiến trình, 5 external entities, 12 luồng, không có vi phạm được validator báo. Đã kiểm tra ảnh các luồng và sửa nhãn nhánh/đường đi. File VPP là bản chỉnh sửa chính; bốn ảnh PNG là bản xem trước. Các JSON dựng thử, log kiểm tra và bản xuất cũ không lưu trong Git.

Ảnh PNG xuất trực tiếp có watermark do Visual Paradigm Evaluation Copy; dùng để xem trước, chưa phải bản nộp cuối. Dùng sơ đồ 01–04 trong project; sơ đồ có tiền tố “Bản cũ” chỉ là bản lưu tham khảo.
