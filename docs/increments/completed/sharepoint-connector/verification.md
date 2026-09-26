# MEM-126 SharePoint connector — verification

Ma trận đầy đủ nằm ở [Connector verification](../../../tests/connector.md#mem-126-sharepoint-connector--2026-09-16). File này giữ bằng chứng riêng của increment.

## Review PR #230 — 19/09/2026

Review tìm ra các lỗi hành vi mà bộ test lúc đó chưa bắt được. Mỗi lỗi có một test tái hiện; tất cả **fail trên code trước khi sửa** và pass sau khi sửa (PostgreSQL thật qua Testcontainers, Microsoft là double).

| Lỗi | Sửa | Test |
| --- | --- | --- |
| Attempt bị huỷ/thất bại để run `IN_PROGRESS`, index `uq_sharepoint_run_live` chặn mọi run sau | `terminal`/`retry` hết lượt đóng run; `openRun` huỷ run mà attempt khác bỏ lại | `aRunLeftOpenByACancelledAttemptDoesNotBlockTheNextOne`, `aFailedAttemptClosesItsRun` |
| `postpone` đẩy `next_prune_at` trước khi run đọc nó, prune theo lịch không bao giờ chạy | Enqueue theo lịch chỉ dời `next_sync_at`; chỉ prune xong mới dời lịch prune; prune không bắt đầu được thì chờ lượt refresh sau | `aScheduledPruneRunsWhenItIsDue`, `aPruneThatCannotStartWaitsForTheNextRefreshSlot` |
| Đổi scope ẩn mọi Document nhưng refresh vẫn đọc cửa sổ cũ, file không đổi không bao giờ hiện lại | `replaceScope` xoá `refresh_window_end` | `aScopeChangeRereadsTheWholeNewScope` |
| Root là thư mục chỉ đọc một cấp | Duyệt BFS như Onyx; thư mục đang đọc và hàng đợi lưu ở `checkpoint_folder_id`/`checkpoint_folders` | `aFolderRootWalksItsSubfoldersAcrossContinuations` (20 cấp, qua một lần hand-back) |
| `includeDocuments` bị bỏ qua | Source chỉ lấy trang không đọc thư viện nào | `aSourceThatCollectsOnlyPagesReadsNoLibrary` |
| HTTP 410 retry với đúng link cũ | Đọc lại thư viện từ đầu | `aRejectedChangeLinkRestartsTheLibrary` |
| Pause cấp Source (từ `main`) không chặn SharePoint | `due()` và `current()` loại `PAUSED` như Google Drive | `aPausedSourceIsNeitherScheduledNorWrittenBy` |
| Segment URL bị giải mã hai lần (`C++`, `%25`) | Tách raw path rồi giải mã một lần, `+` giữ nguyên | `SharePointUrlTest.decodesEachFolderSegmentOnce` |
| Voice: slot TTS mất khi không đọc được key | Trả slot đúng một lần trên nhánh lỗi | `VoiceSynthesisServiceTest.aKeyThatCannotBeReadDoesNotKeepAStreamSlot` |

Merge `main` cùng lúc: `main` đã dùng `V79__source_pause_resume.sql`, nên migration của nhánh chuyển sang V80–V83. Database local đã chạy V79–V82 của nhánh phải tạo lại theo [chính sách early-project](../../../guidelines/persistence.md#early-project-schema-evolution).

Chưa chứng minh ở đây: chặng Document → Search, E2E trên app thật và nghiệm thu tenant thật (xem [plan](plan.md)).
