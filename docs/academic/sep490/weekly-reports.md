# Báo cáo tuần SEP490_G53 — cửa sổ W1–W10

Đây là kế hoạch vận hành cho **một chuỗi báo cáo tuần 10 tuần**, dùng để chuẩn bị snapshot Excel phục vụ SEP490. Nó không thay thế [timeline tổng của dự án](README.md#mốc-thời-gian), vốn vẫn cần được nhóm và giảng viên xác nhận.

Các workbook nộp/soạn thảo nằm ngoài repository, tại `docx/docs/docs-upload/`:

- `Project_Weekly_Report_1.xlsx` đến `Project_Weekly_Report_5.xlsx`: chỉ cho cửa sổ báo cáo đầu, W1–W5.
- `Linear_10_Week_Setup_SEP490_G53.xlsx`: backlog đề xuất W1–W10, gồm cả phần chưa báo cáo W6–W10.

## Nguyên tắc ghi báo cáo

- W1–W2 tham chiếu biên bản họp đã có. W3–W5 là kế hoạch thiết kế được đề xuất, không phải bằng chứng đã triển khai.
- Trường *In-charge* trong các snapshot hiện là **phân công đề xuất**; xác nhận người phụ trách và deadline trước khi nộp.
- Chỉ dùng `Done`/`Completed` khi có bằng chứng cụ thể. Snapshot hiện ghi `In Progress` hoặc `Pending` để không biến kế hoạch thành kết quả đã đạt.
- Báo cáo tuần ghi nhận cả biên bản họp, tài liệu, sơ đồ, trao đổi nhóm và kết quả kiểm tra. **Linear chỉ là một lớp theo dõi chọn lọc** cho dependency, deadline, blocker hoặc đầu việc cần phối hợp xuyên tuần; không phải danh sách đầy đủ mọi việc nhóm đã làm.
- Khi một đầu việc được chọn đưa vào Linear, ghi mã issue thật ở Notes và giữ liên kết truy vết đến tài liệu/kiểm thử. Việc không có issue Linear vẫn cần có bằng chứng phù hợp trong biên bản, tài liệu hoặc report.

## Phân bổ mười tuần

| Tuần | Khoảng thời gian | Trọng tâm | Trạng thái báo cáo |
| --- | --- | --- | --- |
| W1 | 07/09–13/09/2026 | Scope, backlog Linear, sơ đồ core, rủi ro guardrail/SSO | Đã tạo snapshot |
| W2 | 14/09–20/09/2026 | Organization/role/group, ACL/metadata, đầu vào R1 và swimlane | Đã tạo snapshot |
| W3 | 21/09–27/09/2026 | SRS v0.9, access matrix, mô hình dữ liệu quyền, prototype | Đã tạo snapshot |
| W4 | 28/09–04/10/2026 | SDS mức cao, đặc tả guardrail/Keycloak, thiết kế Drive/OCR/ACL | Đã tạo snapshot |
| W5 | 05/10–11/10/2026 | Rà soát SRS/SDS/test plan, refine iteration 1 | Đã tạo snapshot |
| W6 | 12/10–18/10/2026 | Iteration 1: IAM, source metadata, UI shell | Báo cáo sau |
| W7 | 19/10–25/10/2026 | Hoàn thiện luồng phân quyền/ingestion, unit test | Báo cáo sau |
| W8 | 26/10–01/11/2026 | Tích hợp truy vấn kiểm soát quyền và UI | Báo cáo sau |
| W9 | 02/11–08/11/2026 | Hardening guardrail/quyền, integration/system test | Báo cáo sau |
| W10 | 09/11–15/11/2026 | Ổn định demo, tổng hợp test evidence và kế hoạch tiếp | Báo cáo sau |

Đây là phân kỳ để tránh dồn toàn bộ hạng mục vào năm báo cáo đầu. Scope, owner và deadline cuối cùng thuộc Linear sau khi nhóm/giảng viên xác nhận.
