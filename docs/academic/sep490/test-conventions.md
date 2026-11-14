# Quy ước chung cho Report 5.1–5.5

Trang này là quy ước dùng chung cho năm workbook kiểm thử và cho [Report 5.0](README.md#bộ-mẫu-hiện-có). Mỗi người làm một workbook, nhưng số liệu của cả năm file được cộng lại ở 5.0 (mục 3.1–3.3), nên các điểm dưới đây phải giống nhau ở mọi file. Đây là quy ước trình bày hồ sơ, không thay các ma trận [docs/tests](../../tests/) và [hướng dẫn kiểm thử](../../guidelines/testing.md), vốn là nguồn sự thật của test tự động.

Mã yêu cầu (FE, UC, BR, BF, JOB, API, U/P/S/RL/Q) lấy từ Report 3 và không tự đặt thêm.

## 1. Phân công workbook

| File | Cấp | Nội dung | Phụ thuộc |
| --- | --- | --- | --- |
| 5.1 Unit Test | UT | Test backend (JUnit) và frontend (Vitest) không dựng hạ tầng | Kết quả CI; Report 3 |
| 5.2 Integration Test | IT | Test backend dựng Spring context, HTTP slice, Testcontainers hoặc database fixture | Kết quả CI; Report 3 |
| 5.3 System Test FRs | ST | Test trình duyệt Playwright theo luồng người dùng | Kết quả CI frontend; Report 3 |
| 5.4 System Test NFRs | ST | Một kịch bản cho mỗi yêu cầu U, P, S, RL, Q | Môi trường staging, RAG benchmark, công cụ load test (chưa chọn) |
| 5.5 Acceptance Test Scripts | AT | Một script cho mỗi business flow BF-01 đến BF-04 | Hệ thống ổn định sau 5.3 và 5.4; chạy ở tuần 12–13 |

5.1, 5.2, 5.3 độc lập về dữ liệu đầu vào và có thể làm song song. 5.4 cần số đo thật nên bắt đầu sớm. 5.5 viết kịch bản trước, ghi kết quả sau. 5.0 chỉ cập nhật sau khi bốn file còn lại chốt số.

## 2. Tính năng và nhóm trong workbook

Dùng đúng mã và tên tính năng của Report 3, giống nhau ở mọi file.

| Mã | Tính năng | Use case |
| --- | --- | --- |
| FE-01 | Account Access | UC-01 đến UC-03 |
| FE-02 | Data Sources, Search and Evidence | UC-04 đến UC-10 |
| FE-03 | Chat and Personal Workspace | UC-11 đến UC-16 |
| FE-04 | Organization Administration | UC-17 đến UC-21 |
| FE-05 | AI Administration | UC-22 đến UC-26 |
| FE-06 | MCP Integration | UC-27, UC-28 |
| FE-07 | Meetings | UC-29 đến UC-34 |

Ngoài FE còn có JOB-01 đến JOB-12 và API-01 đến API-05 (Report 3, III.8 và III.9). Test của chúng ghi vào cấp phù hợp (thường IT) và dùng cột Requirement.

Cách chia sheet:

- **5.1:** một sheet cho mỗi module, tên sheet là `FE-xx <module>`, ví dụ `FE-04 iam`. Module backend là package dưới `io.memoryos` (ví dụ `iam`, `chat`, `retrieval`); module frontend là thư mục dưới `web/src/features`.
- **5.2:** một sheet cho mỗi tính năng (`FE-xx`), nhóm theo UC trong sheet (cột Function của mẫu). Sheet `SecurityTest` giữ các kiểm tra phân quyền và rò rỉ.
- **5.3:** một sheet cho mỗi luồng (workflow), tên bắt đầu bằng mã FE. Mỗi luồng ứng với một hoặc nhiều UC.

Ánh xạ module sang FE do từng người xác nhận khi làm và ghi vào cột Requirement; khi một module phục vụ nhiều FE thì ghi test vào FE mà use case của nó thuộc về, không ghi đúp.

## 3. Đặt ID test case

Dạng `<cấp>-<FE>-<số 3 chữ số>`, số tăng dần trong cùng cấp và FE, không dùng lại khi test bị xóa.

| Cấp | Ví dụ | Ghi chú |
| --- | --- | --- |
| UT | `UT-FE04-012` | Dùng cho cả backend và frontend; cột Layer ghi `BE` hoặc `FE` |
| IT | `IT-FE02-107` | |
| ST (chức năng) | `ST-FE03-021` | |
| ST (phi chức năng) | `TC-SEC-001`, `TC-PERF-001`, `TC-USA-001`, `TC-REL-001`, `TC-QUA-001` | Giữ dạng của mẫu 5.4, thêm USA, REL, QUA cho U, RL, Q |
| AT | `SC-01` đến `SC-04` | Mỗi script một BF; ví dụ BF-01 là `SC-01` |

ID không chứa tên test tự động. Tên test (class và method hoặc tên spec) ghi riêng ở cột Automated test, để đổi tên test không làm đổi ID.

## 4. Cột bắt buộc của mỗi test case

Mẫu 5.1–5.3 đã có cột Test Case ID, Description, Procedure, Expected Results, Pre-conditions, kết quả theo Round, Test date, Tester, Defect IDs, Note. Giữ nguyên các cột đó và thêm ba cột sau, đặt cuối bảng:

| Cột thêm | Nội dung |
| --- | --- |
| Requirement | Mã UC, BR, JOB, API hoặc NFR mà test kiểm; nhiều mã cách nhau bằng dấu phẩy |
| Automated test | Class và method (backend) hoặc tệp và tên test (frontend, Playwright). Test chạy tay thì ghi `Manual` |
| Layer | `BE` hoặc `FE` (chỉ 5.1) |

5.4 và 5.5 giữ cột của mẫu và thêm Requirement (5.4) hoặc BF-ID đã có sẵn (5.5).

Một test case kiểm một hành vi. Mô tả viết bằng hành vi nhìn thấy được ("thành viên INACTIVE bị từ chối ở request kế tiếp"), không bằng tên hàm.

## 5. Cách đếm

Quy tắc đếm giống Report 5.0 (II.3) để các tổng cộng lại khớp.

1. **Trong phạm vi:** chỉ đếm test của FE-01 đến FE-07, JOB, API và NFR. Test của chat bot Lark, Zalo, WhatsApp và code sandbox nằm ngoài phạm vi và không vào số liệu.
2. **UT và IT:** lớp test khởi động Spring context, HTTP slice, Testcontainers hoặc database fixture là IT; còn lại là UT. Vitest luôn là UT; Playwright luôn là ST.
3. **Một test một lần:** mỗi test tự động chỉ xuất hiện ở một workbook và một sheet. Một test kiểm nhiều yêu cầu thì liệt kê tất cả ở cột Requirement.
4. **Skip:** test bị skip vẫn được đếm, trạng thái là `N/A`, lý do ghi ở cột Note (ví dụ: cần dịch vụ thật bên ngoài).
5. **Nguồn số liệu:** mọi workbook lấy kết quả từ cùng một lần chạy CI trên `main`. Ghi mã run và commit ở sheet Cover (cột Reference của bảng Record of change). Chạy lại thì cập nhật Round mới ở cả các file bị ảnh hưởng, không ghi đè Round cũ.
6. **Thống kê:** cột Passed, Failed, Pending, N/A của sheet Test Statistics cộng lại bằng cột Number of test cases; tổng của bốn file phải bằng dòng Total ở 5.0, mục 3.1.

## 6. Trạng thái

Các mẫu dùng từ khác nhau cho cùng ý nghĩa; chuẩn hóa như sau và dùng cùng nhãn trong 5.0.

| Ý nghĩa | 5.1–5.3 | 5.4 | 5.5 |
| --- | --- | --- | --- |
| Đạt | `Passed` | `Passed` | `Passed` |
| Không đạt | `Failed` | `Failed` | `Failed` |
| Chưa chạy | `Pending` | `Not Run` | `Not Executed` |
| Không áp dụng hoặc bị bỏ qua | `N/A` | `N/A` | `N/A` |

5.4 ghi thêm cột Target và Actual cho nhóm P (hiệu năng) kèm điều kiện đo (số người dùng, dữ liệu, công cụ). Chưa có số đo thì để trống Actual, không ước lượng.

## 7. Defect

- Mỗi defect là một issue Linear (`MEM-xxx`); ghi mã ở cột Defect IDs.
- Mỗi defect có độ nghiêm trọng (Critical, Major, Minor) và giai đoạn phát hiện: Review, UT, IT, ST hoặc AT.
- Số defect theo giai đoạn ở 5.0, mục 3.2, phải cộng bằng số theo sản phẩm ở mục 3.3 (cột Review, UT, IT, ST, AT và Total).

## 8. Dữ liệu và tài khoản test

Dùng bảng tài khoản và bảng dữ liệu tối thiểu của Report 5.0 (II.1.1 và II.1.2). Không tự thêm tài khoản; thiếu vai trò thì đề xuất bổ sung vào 5.0. Mật khẩu không ghi vào tài liệu; mọi dữ liệu là dữ liệu mẫu.

## 9. Tên file và phiên bản

- Giữ tên mẫu: `Report-5.1_Unit Test`, `Report-5.2_Integration Test`, `Report-5.3_System Test_FRs`, `Report-5.4_System Test_NFRs`, `Report-5.5_Acceptance Test Scripts`. 5.1 mẫu là `.xls`; nếu nhóm chuyển sang `.xlsx` thì chuyển cả báo cáo trong 5.0 mục II.2.
- Mỗi lần sửa thêm một dòng vào Record of change ở sheet Cover (ngày, phiên bản, A/M/D, mô tả, tài liệu tham chiếu).
- Workbook đã điền đặt ở `reports/`, không ghi đè mẫu trong `templates/`. `.gitignore` hiện chặn `*.docx` ngoài `templates/` nhưng không chặn `.xls` và `.xlsx`; cần đưa workbook lên repo hay không thì thống nhất trong nhóm trước.
