# MemoryOS — audit chất lượng code toàn repo (origin/main 645340f2, 2026-09-24)

Phạm vi: core (10 capability), connector bundle, api, worker, deploy.sh, web/src (trừ hey-api sinh tự động và components/ui).
Cách chấm: lượt A theo best practice chung, lượt B đối chiếu conventions.md / persistence.md / observability.md / ADR.
Nhãn: [VP] vi phạm quy ước repo · [MT] mâu thuẫn với quy ước (chủ repo quyết) · [TL] trung lập.
Đã tự kiểm lại trên code: A1, A2, A3, A4, A6, A7 — xác nhận đúng. Các mục khác do agent kiểm chứng.

## A. Lỗi thật lộ ra trong lúc audit (nên sửa trước)

| # | Chỗ | Vấn đề |
|---|---|---|
| A1 | web/src/features/meetings/*: invalidate `{queryKey: meetingsKey, exact: true}` | Key danh sách là `["meetings", actorId, version]` → exact không khớp gì; danh sách cũ tới 30 s sau khi tạo/xoá/kết thúc |
| A2 | web/src/features/mcp/mcp-servers-page.tsx, mcp-oauth-clients.tsx, mcp-server-editor.tsx | Gọi SDK không `throwOnError` → 4xx/5xx coi là thành công, editor tự đóng, list lỗi thành rỗng |
| A3 | core meeting/persistence/MeetingRepository.java:308,485 | Claim chỉ lấy `attempts < :max`, không có sweep `failAbandoned` → replica chết ở lần thử cuối: minutes RUNNING mãi, meeting TRANSCRIBING mãi, audio 500 MB không xoá |
| A4 | core meeting/MeetingService.java:307 | `@Transactional(readOnly)` gắn nhầm lên enum `TranscriptFormat`; `exportMinutes` chạy ~9 query ngoài transaction; overload 3 tham số không ai gọi |
| A5 | web meetings/meeting-page.tsx:483 + meeting-session.ts:53 | Recorder `failed` bị bỏ ngay → thông báo lỗi (MEETING_BUSY, TOO_LONG…) không bao giờ hiện |
| A6 | web routes/_authenticated.admin.tsx | `adminEntryPath` thiếu nhánh chat-history → người chỉ có CHAT_HISTORY_READ bị đưa vào /admin/audit → Access denied |
| A7 | core connector persistence/JdbcSourceQueryRepository.java:59-71 | Status/error_code của Source chỉ lấy từ `google_drive_sources` → Source SharePoint lỗi sync vẫn hiện ACTIVE |
| A8 | core connector DefaultSharePointSyncService.java:498 | Storage failure ném `StaleSyncException` làm goto → run bị SUPERSEDED, mất checkpoint |
| A9 | web sources/sources-page.tsx:247-285 | Filter thiếu SharePoint và PAUSED/PAUSING; option list đúng nằm trong source-status-presentation.ts mà không dùng |
| A10 | web sources/source-run-history.tsx, source-item-history.tsx | Nút phân trang disable theo `isFetching` của poll 1.5–5 s ([VP] quy ước isPlaceholderData) |
| A11 | web chat/chat-library-page.tsx:292 | `void change(...)` không catch → favourite lỗi là unhandled rejection |
| A12 | web meetings/meeting-page.tsx:1416 | onBlur save không huỷ timer 1.2 s → 2 save cùng revision → 409 giả |
| A13 | web groups/*: chỉ `beforeunload` | Bấm link trong app mất draft Group (agents đã dùng `useBlocker`) |
| A14 | api invitation/InvitationController.java:107 | `ResponseStatusException(BAD_REQUEST, e.getMessage())` → không có code, lộ message |

## B. Vấn đề hệ thống (xếp theo giá trị)

### Backend
1. **SharePoint được copy từ Google Drive** (connector): sync engine, attempt persistence, selection repo/processor, HTTP plumbing, audit helper đều có 2 bản và đã lệch nhau (pause, retry, logging). `DefaultConnectorSyncService` thực chất là engine Google nhưng tên chung. [TL] high.
2. **Send path của chat lặp đọc**: `persona()` 3 lần × 5 query, `effectiveCapabilities` ≥4 lần, provisioning `INSERT…ON CONFLICT` trên mọi read (catalog không có readOnly) — một phần giữ trong advisory lock. [TL]/[MT cho provisioning] high.
3. **Hiệu năng hot path**: `OpenSearchIndexService.ensureIndex()` 5–8 HTTP/document, `synchronized`; HEAD alias trên mọi search; `JdbcDocumentChunkRepository.scan` cursor dạng chuỗi nối → O(n²); authority snapshot IAM 2–4 lần/request; SharePoint liệt kê lại toàn bộ site mỗi slice 45 s; ~25 lock query/file khi sync. high.
4. **api thành nơi chứa capability code** [VP]: provider OpenAI (~1.600 dòng), Google OAuth client, logic admission đăng nhập, orchestration meeting nằm trong api; contract HTTP lộ kiểu persistence (`JdbcAgentRepository.AgentRef`, `JdbcChatHistoryRepository.Query`, `AiCostQueries.Split`…); 34 record lồng trong MeetingController (quy ước bắt `contract/`).
5. **Copy-paste không có nhà chung**: PDF text engine ×3 (meeting ×2, usage), SHA-256 hex ×9, LIKE-escape ×5, lease renewal ×6, CSV export ×3, file download ×10, HTTP client voice tạo mới mỗi call, 3 admin service image/web/voice gần như giống hệt, MCP credential/header ×3–5.
6. **Overload phình to** (chat): `ChatTurnService` 6 constructor, `JdbcChatRepository.finish` 5 overload 9–14 tham số nullable, nhiều cái không ai gọi [VP clean cutover].
7. **Meeting**: mọi thay đổi nhỏ (star, tick) trả về toàn bộ transcript (~9 query); ghi utterance từng dòng; đọc cả transcript chỉ để `isEmpty()`; audio 500 MB `readNBytes` vào heap API.
8. **Quy ước chưa được áp dụng đều** [VP]: ~700 dòng FQN inline (core+api+worker), log positional thay vì fluent `event`/`error_code` (~100 chỗ), `iam/audit` chứa SQL trong @Service, `chat/interpreter/JdbcInterpreterRepository` ngoài `persistence`, ~25 `static new ObjectMapper()` lẫn Jackson 2/3.
9. **Chuỗi failure code** trong `IllegalStateException("CHAT_…")` + allowlist dò cause 8 cấp; tool key là chuỗi rời.
10. **deploy.sh**: ~20 assertion `[[ ]]` fail im lặng, `set -E` không có trap ERR.

### Frontend
1. **Hai phong cách data-layer**: sources/groups/users dùng generated `*Options`/`*Mutation`; chat, meetings, agents, document-sets gọi SDK tay + `setPending` tay (~60 chỗ) → thiếu catch, bỏ qua MutationCache 401/403.
2. **CSRF header + throwOnError lặp ~285 chỗ** → thiếu là im lặng (A2). Một interceptor giải quyết.
3. **Query key viết tay, trùng**: `["chat-projects",…]` ×6, `["chat-personas"]`, `["document-sets"]` lệch nhau; `queryClient.invalidateQueries()` không key ở 6 chỗ.
4. **Không dùng registry shadcn** [VP]: ~20 file import `radix-ui` trực tiếp (ChatDialog z-40 không animation), picker Sources tự viết không hỗ trợ bàn phím, banner tự viết ×10 thay `Alert`, mobile nav tự dựng thay `Sheet`, chip ×3.
5. **Preview song song** [VP]: `chat-file-preview-modal` tự có pipeline load/dispatch riêng song song `preview/original-view` (document-viewer increment nói một kit chung).
6. **Re-render**: meeting page re-render toàn bộ ~20 lần/s khi ghi âm (level meter), transcript không virtualize; `ChatEditingContext` object mới mỗi render + O(n²) find trên action bar.
7. **Sources**: create-with-receipt ×2, "chờ operation rồi toast" ×5, upload pipeline ×2 (validation khác nhau), cursor paging ×4, poll 5 s liên tục khi không có việc.
8. **i18n**: `chatActionError` trả chuỗi Việt cứng (34 caller), `ui()` nhận dữ liệu người dùng, câu bị cắt đôi; 315 KB translations nạp một lần; copy giải thích dưới control (vi phạm sở thích của bạn) ở meetings, chat settings, models, usage.
9. **Admin pages khai báo ở 4 nơi** (union, sidebar, 2 ternary 15 tầng) → sinh ra A6.
10. `AppShell` mount lại theo từng trang → `collapsed` reset.

## C. Mục [MT] — bạn quyết
- Lazy provisioning catalog/persona trên mọi entry point vs provision lúc tạo tenant (cần event mới chat→IAM).
- Hai lifecycle upload/write trong objectstorage (spec cố ý tách).
- `useModelAction` tự viết thay `useMutation` (lý do: không giữ provider key trong mutation cache).
- Provider OpenAI trong api: dời về core/connector hoặc ghi nhận là ngoại lệ trong ARCHITECTURE.
- Interceptor CSRF toàn cục vs marker tường minh từng call.
- Mutation meeting trả response hẹp (đổi contract).

## D. Điểm mạnh (các agent đồng thuận)
Khoá/lease/fencing cẩn thận, tenant-scoped authorization nhất quán, SQL không lọt vào service (trừ iam/audit), typed failure code, không log payload, bảo mật layered (CSRF, JWT fail-closed), chat runtime/assistant-ui dùng đúng, quản lý tài nguyên audio/socket kỷ luật.
