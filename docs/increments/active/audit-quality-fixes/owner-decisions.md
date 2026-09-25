# Cần chủ repo duyệt

Những mục dưới đây **chưa làm** trong increment này. Mỗi mục ghi lý do cần duyệt và đề xuất. Đánh dấu `[x] làm` / `[ ] bỏ` / ghi chú rồi giao lại.
Nguồn: [audit.md](audit.md) (audit toàn repo trên origin/main 645340f2, 2026-09-24).

## 1. Quyết định thiết kế (best practice chung mâu thuẫn với lựa chọn hiện tại)

- [x] **Provisioning catalog/persona của Chat.** Duyệt 2026-09-24 (best practice: provision khi tạo Tenant). Đã làm trên nhánh `dathip04/chat-provision-on-tenant`: IAM phát `TenantBootstrapped` trong transaction bootstrap, Chat provision catalog, flow và agent mặc định bằng `@EventListener` cùng transaction; lần khởi động lại provision Tenant cũ; các method đọc của catalog là `readOnly`. Xem [Chat defaults provisioned with the Tenant](../chat-provision-on-tenant/design.md).
- [x] **Provider OpenAI (~1.600 dòng) nằm trong `api`.** Duyệt 2026-09-24: chuyển về `core` tại `chat.catalog.openai`, không đổi hành vi, xem [OpenAI provider in core](../openai-provider-in-core/design.md). Quy ước: api/worker chỉ là composition root. Hệ quả: minutes và transcription của meeting buộc chạy trên API.
- [ ] **Cùng loại, chưa làm:** `GoogleDriveAccountClient` và `GoogleDriveOAuthProperties` (đọc tay `memoryos.google-drive.*` trùng `GoogleDriveProviderProperties`), logic admission trong `ActorSessionLoginSuccessHandler`, orchestration trong `MeetingController.minutes/publish`.
- [x] **Interceptor CSRF + `throwOnError` toàn cục cho web client.** ~285 call site tự thêm `headers: sameOriginMutationHeaders, throwOnError: true`; quên là lỗi bị nuốt im lặng (đã gặp ở MCP). Đề xuất: một `client.interceptors.request.use` thêm `X-MemoryOS-CSRF` cho non-GET và `throwOnError: true` trong `client.setConfig`; trước đó sửa 3 caller tự đọc `result.error` (`chat-file-picker.tsx:198`, `chat-projects-page.tsx:217`, `search-page.tsx:188`). Đổi lại: mất marker tường minh ở từng call.
  - Duyệt 2026-09-24; **đã làm** trên nhánh `dathip04/web-client-defaults`. Generator đặt `throwOnError: true` (SDK mặc định kiểu ném lỗi) và bỏ tham số header `X-MemoryOS-CSRF` khỏi type sinh ra; interceptor trong `web/src/lib/api.ts` thêm header cho mọi method trừ GET/HEAD/OPTIONS, khớp `BrowserMutationConfiguration`. Hai caller đọc `result.error` chuyển sang bắt `ApiError` qua `isNotFound`; `search-page.tsx` thật ra đọc kết quả `useQuery`, không phải SDK, nên không đổi. `sameOriginMutationHeaders` còn giữ cho `fetch("/logout")`. Quy ước ở [conventions](../../../conventions.md#published-api-contracts).
- [x] **Mutation nhỏ của meeting trả về toàn bộ transcript** (star, bookmark, tick item, đặt tên speaker…: ~9 query + serialize mọi utterance). Đề xuất response hẹp — **đổi contract OpenAPI** và web client. Duyệt 2026-09-24; đã làm ở nhánh `dathip04/meeting-narrow-responses` — bảng response ở [spec](../../../specs/meeting.md#what-a-change-answers). Tạo, kết thúc, chạy lại minutes, recording, `accept-all` và `revert-all` vẫn trả cả meeting. Mở rộng 2026-09-25 ở nhánh `dathip04/meeting-correction-responses`: hiệu chỉnh transcript từng dòng cũng hẹp — `accept`, `revert` và sửa một từ bằng tay trả `{utterance, correction}`, `keep` trả đúng correction; cùng nhánh sửa chỗ lệch cũ là tick và sửa item minutes nhận id của topic trong khi xoá thì từ chối — giờ cả ba đều trả 404 cho topic.
- [x] **`useModelAction` tự viết thay `useMutation`** (lý do trong file: không giữ provider key trong mutation cache). Có thể thay bằng `useMutation` với `gcTime: 0`. Giữ hay đổi?
  - Duyệt 2026-09-24: đổi theo best practice (`useMutation`). **Đã làm** trên nhánh `dathip04/models-use-mutation`.
  - Mỗi thao tác catalog là một `useMutation` riêng (`web/src/features/models/model-mutation.ts`): `mutationKey` chung, `gcTime: 0`; body và key đọc từ closure/ref lúc chạy, variable duy nhất là `AbortSignal`; lỗi được thay bằng `ApiError` chỉ giữ status + problem code và thông điệp an toàn, nên 401/403 đi qua `MutationCache.onError` toàn cục và 409 vẫn nhận ra. Một thao tác mỗi trang bằng `useIsMutating` (khoá control), không dùng `scope` vì scope xếp hàng và vẫn chạy một write đã huỷ hoặc đã cũ revision. Huỷ/unmount abort và settle ngay; `pagehide` của `ModelsPage` giữ lại (unmount cả trang, bỏ draft và key) và chạy `flushSync`.
  - Khoảng trống so với thư viện: generated `*Mutation()` đưa cả request `Options` (có body chứa key) vào `variables`, và `mutationFn` của TanStack v5 không nhận `AbortSignal`, nên không dùng generated mutation options ở đây.
- [x] **Hai lifecycle upload/write trong `objectstorage`** (spec cố ý tách). Giữ hay gộp phần reserve/key trùng? — **Duyệt 2026-09-24** (best practice: bỏ bản lai). **Đã làm** trên nhánh `dathip04/library-copy-server-write` ([library copies](../library-copy-server-write/design.md)): bản sao trong thư viện Chat đi qua `ObjectWriteService`, `chat_user_file` tham chiếu stored object (V127), `ObjectUploadService.write` đã bị xoá; hai lifecycle vẫn tách vì upload từ trình duyệt cần PUT có chữ ký, resume và verify.
- [x] **Session lock striped của Chat giữ qua `mcp.open`** (network, OAuth refresh) → session khác cùng stripe bị chặn. Đề xuất thả lock sau khi reservation commit; cần kiểm tra concurrency kỹ. **Duyệt 2026-09-24; đã làm** trên nhánh `dathip04/chat-send-lock-scope`: lock chỉ giữ tới khi đăng ký run, `mcp.open` chạy ngoài lock — xem [Chat send lock scope](../chat-send-lock-scope/design.md).

## 2. Tái cấu trúc thư mục kiểu `iam` (Lane 2)

Mô hình `iam`: gốc module gần như trống; mỗi sub-capability là sub-package có type public + `persistence/` riêng, expose bằng `@NamedInterface`.

- [x] **Bước tiền đề:** (xong 2026-09-25, bước 0 của [ADR 0015](../../../decisions/0015-capability-module-map.md)) gỡ các import persistence từ `api`/`worker` (`chat.persistence` ×10, `chat.history.persistence`, `usage.persistence`, `ingestion.persistence`; ví dụ `JdbcAgentRepository.AgentRef`, `JdbcChatHistoryRepository.Query`, `AiCostQueries.Split` đang là body HTTP). Chủ repo quyết định 2026-09-24: **không thêm ArchUnit**; `ModulithArchitectureTest` chỉ kiểm core nên ranh giới này giữ bằng review. **Có thể đổi schema OpenAPI.**
- [ ] **`connector`** (75 file ở gốc, `application/` 25, `persistence/` 27, Google và SharePoint trộn lẫn) → `source/`, `googledrive/`, `sharepoint/`, `sync/` (engine trung lập), mỗi cái có `persistence/`.
- [ ] **`chat`** (47 file ở gốc, `persistence/` 45 file dùng chung) → `session/`, `library/`, `persona/`, `project/`, `settings/`; `catalog/voice/image/web/interpreter` nhận `persistence/` riêng.
- [ ] Giữ nguyên các module nhỏ (`mcp`, `meeting`, `usage`, `retrieval`, `objectstorage`, `document`, `ingestion`) theo "prefer fewer modules".
- [ ] **Web:** `features/chat` (141 file phẳng) và `features/sources` (81 file) chia thư mục con theo sub-feature.
- Ràng buộc: giữ đúng 10 module (chỉ thêm sub-package, không cần ADR); mỗi PR một module, chỉ `git mv` + sửa import, không đổi logic; `openapi.yml` phải không đổi. Nên làm khi MEM-126 (SharePoint), MEM-92 (meeting) và các nhánh chat đang mở đã merge.

## 3. Gộp code trùng nằm trong increment đang chạy (Lane 3)

- [ ] **Sync engine Google/SharePoint** (fencing, settle, acquire/adopt/remove, attempt persistence, selection repo/processor, HTTP plumbing, audit helper) — hai bản đã lệch nhau về pause, retry, logging. Đụng MEM-126.
- [ ] **`PdfText`** dùng chung cho `MeetingTranscriptPdf`, `MeetingMinutesPdf`, `UsageReportPdf` (font, `safe()`, wrap). Đụng MEM-92.
- [ ] **Ba admin service image/web/voice connection** gần như giống hệt.
- [ ] **Hot path OpenSearch:** `ensureIndex()` 5–8 HTTP/document và `synchronized`; HEAD alias trên mọi search. Cache "đã đảm bảo" theo process sẽ không nhận ra index bị xoá từ ngoài — chấp nhận không?
- [ ] **Send path của Chat:** gộp `persona()` (3 lần × 5 query), `effectiveCapabilities` (≥4 lần), đọc file từng cái trong lock.
- [ ] **SharePoint liệt kê lại toàn bộ site mỗi slice 45 s**; ~25 lock query/file khi sync; Google walk cha cho từng file.
- [ ] **Cursor `scan` dạng chuỗi nối** trong `JdbcDocumentChunkRepository` → row-value cursor (đổi format cursor đang lưu).
- [ ] **Web data-layer:** chuyển chat/meetings/agents/document-sets sang generated `*Options`/`*Mutation` + `queryOptions` factory; preview của chat dùng kit `preview/`; meeting page tách subscription level meter và virtualize transcript; Sources gộp create-with-receipt, "chờ operation rồi toast", upload pipeline, cursor paging; admin pages về một bảng khai báo; `AppShell` thành layout route; i18n nạp lười ngôn ngữ không dùng.

## 4. Sửa quy ước dạng cơ học — đúng nhưng hoãn vì xung đột nhánh

Không cần duyệt về nội dung, chỉ cần chọn **thời điểm**: diff rất rộng, xung đột với các worktree đang mở (`mem192b`, `embedding`, `undated`, `deployfix`, `mem-134`, meeting, SharePoint).

- [ ] FQN inline → import (~700 dòng: core ~420, api ~90, worker ~30…).
- [ ] Log positional → fluent `event`/`error_code` (~100 chỗ).
- [ ] Bỏ ~71 header `Cache-Control: no-store` và ~15 `nosniff` thừa (Spring Security đã gửi).
- [ ] Chuyển record lồng trong controller (`MeetingController` 34, `ChatLibraryController` 13, `ChatEventStream` 13…) sang `contract/` — cần kiểm tên schema OpenAPI không đổi.
- [ ] `ApiProblem` khai báo `additionalProperties: false` nhưng handler thêm `scope`, `group`, `resetsAt`, `retryAfterSeconds`, `usedBy` — sửa schema sẽ đổi `openapi.yml` và client sinh ra.
- [x] `iam/audit` chứa SQL trong `@Service` → chuyển sang `iam/audit/persistence`. (Xong 2026-09-25, bước 1 của [ADR 0015](../../../decisions/0015-capability-module-map.md): audit thành module riêng, SQL nằm ở `audit/persistence`.)
- [ ] `IllegalStateException("CHAT_…")` + allowlist dò cause → exception có kiểu.

## 5. Hạ tầng triển khai

- [ ] `infrastructure/deployment/deploy.sh`: ~20 assertion `[[ ]]` fail im lặng, `set -E` không có `trap … ERR`. Đề xuất thêm trap in dòng và lệnh lỗi. Chạm bề mặt deploy nên chờ bạn duyệt.

## 6. Phát sinh trong lúc sửa

- [ ] **Storage failure: Google và SharePoint xử lý khác nhau.** Google đánh dấu một node lỗi rồi đi tiếp; SharePoint retry cả attempt (đã sửa để không mất checkpoint, nhưng vẫn khác Google). Thống nhất theo bên nào?
- [ ] **`VoiceSynthesisService.HttpSpeech`** vẫn tạo `HttpClient` mỗi lần vì `close()` gọi `shutdownNow()` để huỷ giọng đọc đang phát. Muốn dùng client chung thì phải huỷ theo request (`CompletableFuture.cancel`).
- [ ] **Ô ghi chú của meeting** đã bỏ placeholder "…chỉ mình bạn xem được", giờ không còn gợi ý nào. Giữ "Ghi trong lúc họp." làm placeholder không?
- [ ] **`INVITATION_QUERY_INVALID`** đã có bản dịch trên web nhưng hiện chưa màn hình nào gọi `listInvitations`, nên chưa hiện ra ở đâu.
- [ ] **Bytes của recording có thể sót nếu tiến trình chết giữa hai bước** (CodeRabbit PR #363). `failAudio`/`failAbandonedAudio` commit FAILED trước, rồi mới `retire` upload `MEETING_AUDIO`. Nếu process dừng ở giữa, upload vẫn `ADOPTED` và `cleanupAbandoned()` không chọn nó. Khoảng hở này có sẵn trên main ở đường thất bại thường và đường ghi xong (`MeetingRecordingService` :127, :156, :190); sweep mới chỉ đi theo đúng mẫu đó. Đề xuất: marker cleanup bền vững hoặc sweep định kỳ các upload `ADOPTED` của meeting đã FAILED/đã có transcript — là thay đổi thiết kế nhỏ của object storage + meeting.
