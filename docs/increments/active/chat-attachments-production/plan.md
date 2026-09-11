# MEM-81 — Kế hoạch attachments production

[Issue](https://linear.app/memory-os/issue/MEM-81) ở Backlog theo quyết định ngày 2026-09-11. [Design](design.md) giữ baseline Onyx, giới hạn 100/250 MiB, mapping vào stack hiện có, tradeoff và điều kiện nghiệm thu. Chưa bắt đầu implementation/runtime.

## Ranh giới với MEM-11

- MEM-11 làm trước: quản lý hội thoại, edit/regenerate/branch, feedback, Persona, Projects với instructions/hội thoại và sharing có xác thực. Các phần đó vẫn một PR, checkout hiện tại, không tách worktree.
- MEM-81 sở hữu mọi file gắn message/Persona/Project, upload/status/readiness, context/read_file/images/tables, private retrieval, preview/citations/history và retention/cleanup.
- Hai issue có quan hệ related; MEM-81 không block PR hoặc completion của phạm vi MEM-11 đã điều chỉnh. Khi bắt đầu MEM-81, dùng contract Persona/Project/branch thực đã giao, không chuẩn bị trước schema hoặc abstraction không có consumer trong MEM-11.
- Không coi việc tách issue là hoàn thành attachments hoặc giảm baseline; cap 100/250 MiB và production acceptance được giữ nguyên tại đây.

## Triển khai

- [ ] Xác nhận reference/format parity và khác biệt upload/worker parsing theo design; giữ giới hạn đã chốt.
- [ ] Migration UserFile/references, server upload policy và adoption/cleanup ownership; giữ policy Source/Google.
- [ ] Upload/status/read/delete và worker processing/projection/cleanup với limits, claims, restart và retry.
- [ ] Send/history/idempotency/branch context, native read_file, private search, multimodal binding và authorized citations.
- [ ] UI upload/drop/recent/status/retry/preview/history; gắn file vào Persona/Project đã có.
- [ ] Contract và runtime verification theo điều kiện design; đo file lớn 100/250 MiB, peak RAM/disk và thời gian từng chặng; reuse coverage hiện có.
- [ ] Review, required CI và runtime/deployment acceptance theo lifecycle repository; cập nhật spec/tests cùng code. Post-merge evidence và closure ghi Linear, không mở PR chỉ để ghi trạng thái.
