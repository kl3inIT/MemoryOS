# MEM-11 — Sơ đồ tham khảo còn hiệu lực

[Design](../design.md#baseline-da-chot) là kiến trúc mục tiêu đã chốt, chưa phải implementation. Bộ sơ đồ worker/snapshot/journal/history dependency ACL trước đây đã được rút khỏi tài liệu active vì không còn đúng quyết định. Các bản nháp và QA captures được giữ trong ignored `.tmp/mem11-retired-design-20260909/`, không đưa vào commit.

| Sơ đồ | HTML | Nguồn |
| --- | --- | --- |
| Search tool theo baseline reference implementation | [Xem](04-retrieval.html) | [JSON](04-retrieval.json) |

Sơ đồ Retrieval giữ luồng query/fusion/section selection và authorized context reads. Ghi chú web adapter là khả năng mở rộng sau, không yêu cầu xây registry hoặc provider trong lần giao đầu. Các luồng hệ thống còn lại theo design/plan; sơ đồ cũ không là implementation contract.

Artifact Retrieval trước đó đã đạt Archify showcase 9/9 và kiểm hình light/dark; không thay đổi JSON/HTML trong lần reconcile này. Đó là kiểm trình bày, không phải runtime acceptance. Khi sửa hình, dùng skill Archify project-local, validate/deliver/visual-check và xem render; QA outputs đặt trong ignored scratch, chỉ giữ nguồn và HTML được kiểm vào docs.
