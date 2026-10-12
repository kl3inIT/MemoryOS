# MEM-136 — Kế hoạch

Thiết kế: [design.md](design.md). Nhánh: `anhnd05122004/mem-136-sharepoint-auto-sync-dong-bo-quyen-sharepoint-theo-onyx-ee`.

**Quy ước:** mỗi bước kết thúc bằng test của chính nó xanh; `./gradlew clean check` và `pnpm --dir web check` xanh
trước khi mở pull request. Không đưa secret, certificate hay token vào repo, Linear, log hoặc lịch sử lệnh.

## 0. Trước khi viết code

- [x] Đọc issue, thiết kế Q13, tham chiếu Onyx, code Onyx EE (`06aa2b09`), connector spec, conventions, cách Drive và
      Lark làm đồng bộ quyền; ghi vào [design.md](design.md).
- [x] Chốt hai điểm ở [design §8](design.md#8-quyết-định) (12/10/2026).
- [ ] Người tạo issue xác nhận lịch group theo chu kỳ refresh thay vì 5 phút (design §6 #8).
- [ ] **Spike S0.11–S0.12 trên tenant dùng thử, trước 15/10/2026** (dùng file thông tin đăng nhập ngoài repo của
      [Giai đoạn 0](../../completed/sharepoint-connector/plan.md#giai-đoạn-0--spike-trên-tenant-thật)); ghi response đã
      che vào `verification.md` của increment này:
  - S0.11: token REST bằng certificate; `roleassignments` của list item tìm theo `listId` trên site tiếng Việt và
    của trang qua `ListItemAllFields`; loại principal, `LoginName`, `RoleTypeKind`; mã lỗi khi dùng client secret.
  - S0.12: role assignment cấp web, `sitegroups/getbyid({id})/users`, Graph thành viên Entra group với
    `GroupMember.Read.All`; mã lỗi khi thiếu quyền; UPN của khách và của người dùng nội bộ.
  - Lưu các response đã che làm fixture cho bước 1.

## 1. Gateway (`memoryos-integration-microsoft`)

- [x] `SharePointTokenSource` cấp token theo scope; token REST chỉ với certificate; test trên TLS như
      `MsalSharePointTokenSourceTest`.
- [x] `SharePointGateway.Session`: `sharepointIds` trên `DriveItem`; role assignments của item, trang và web; thành
      viên SharePoint group; thành viên Entra group; danh sách Entra group; scope của link chia sẻ.
- [x] Lời gọi REST trên `GraphTransport`: chỉ Tenant host, phân trang `odata.nextLink`, ngân sách và deadline.
- [x] `RestSharePointGatewayTest`: mỗi lời gọi mới trên wire (đường dẫn, header, phân trang, 401/403/404/429, host
      lạ bị từ chối, response quá cỡ).
- [x] Metric: bốn operation mới.
- [ ] Đối chiếu fixture với response thật của spike S0.11–S0.12; hiện fixture dựng theo tài liệu Microsoft và Onyx.

## 2. Diễn giải quyền và persistence (`memoryos-core`)

- [x] `SharePointAccess`: quy tắc §5.3, test đơn vị theo tham chiếu Onyx §7 (từng loại principal, Limited Access,
      public, group lồng nhau và vòng, `normalize_email`). Chưa đối chiếu từng case với `test_permission_utils.py`
      vì checkout Onyx không có trong worktree.
- [x] Migration `V155` (design §5.4).
- [x] `JdbcSharePointAclRepository` (snapshot, `SourceAclChanged` khi đổi) và `JdbcSharePointGroupRepository` (thế hệ,
      việc còn phải hỏi, thành viên, chuyển thế hệ hiệu lực); được kiểm qua `PostgresSharePointSyncTest` và
      `SourceSyncAccessTest`.

## 3. Đồng bộ

- [x] `SharePointSyncAdapter`: `permissionSync = true`; đọc quyền lúc nhận item trong lượt refresh của Source `SYNC`.
- [x] Pha group (`SharePointGroupSynchronizer`, theo thế hệ) và pha quyền (`next_permission_sync_at`, checkpoint theo
      item, bù `list_id` cho item cũ) trong lượt refresh.
- [x] `PostgresSharePointSyncTest`: quyền gắn lúc index; pha quyền cập nhật snapshot; item 404 mất quyền; pha
      group thay toàn bộ thành viên; lần đọc hỏng giữ thành viên cũ; Source không `SYNC` không đọc quyền.

## 4. Truy cập

- [x] `JdbcSourceDocumentRepository`: nhánh SharePoint của `SYNC_GRANTS` và `READER_TOKENS`.
- [x] `SYNC` cần credential certificate khi tạo, khi đổi truy cập (`SourceSyncAdapter.accessChanging`) và khi thay xác
      thực của credential. Probe quyền Entra được thay bằng lỗi của lượt đồng bộ (design §5.6).
- [x] Test: người có quyền trực tiếp, qua group đọc được; người khác thì không; rời group mất quyền mà không index
      lại; đổi quyền tài liệu phát `SourceAclChanged`; client secret bị từ chối.

## 5. API và web

- [x] Contract: `treatSharingLinksAsPublic` trong cấu hình; `PUT …/sharepoint/sharing-links`; `SharePointSourceApiTest`.
- [x] Sinh lại `openapi.yml` và client hey-api.
- [x] Web: Auto Sync ở bước Truy cập (chỉ khi credential dùng certificate, có giải thích khi không) và ở hộp thoại
      đổi chế độ, công tắc link chia sẻ, mã lỗi vi/en; test `sharepoint-panel.test.tsx`.
- [ ] `sharepoint-source-setup.spec.ts` (Playwright) cho lựa chọn Auto Sync.

## 6. Tài liệu và nghiệm thu

- [x] [ADR 0028](../../../decisions/0028-sharepoint-permissions-by-verified-email-and-groups-per-source.md) mở rộng
      ADR 0011 cho `ms_user:` / `ms_group:` và `normalize_email`.
- [x] Connector spec (bảng access modes, mục SharePoint permissions), `docs/tests/connector.md`,
      [runbook](../../../runbooks/sharepoint-auto-sync.md), ARCHITECTURE, roadmap.
- [x] `./gradlew clean check` xanh (12/10/2026).
- [ ] `pnpm --dir web check`: mọi bước xanh trừ một test, `chat-web.test.tsx` ("offers Web to a tool-capable model
      only"), vốn cũng đỏ trên `main` `22b72294` và không thuộc thay đổi này.
- [ ] Nghiệm thu trên tenant thật theo tiêu chí của issue; ghi vào `verification.md`.
- [ ] Sau khi merge: chuyển increment sang `completed/`, sửa link, đối chiếu roadmap.
