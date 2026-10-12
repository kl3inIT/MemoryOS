# MEM-136 — SharePoint Auto Sync: đồng bộ quyền SharePoint theo Onyx EE

**Liên kết**

- Tracking: [MEM-136](https://linear.app/memory-os/issue/MEM-136). Kế hoạch: [plan.md](plan.md).
- Thiết kế gốc (Q13, 19/09/2026): [SharePoint connector §5.7.1](../../completed/sharepoint-connector/design.md#571-auto-sync-q13);
  tham chiếu Onyx: [onyx-sharepoint-reference.md §7–§8](../../completed/sharepoint-connector/onyx-sharepoint-reference.md#7-đồng-bộ-quyền-ee).
- Contract hiện hành: [connector spec — Source access modes](../../../specs/connector.md#source-access-modes),
  [ADR 0011](../../../decisions/0011-verified-email-source-permission-matching.md),
  [ADR 0019](../../../decisions/0019-provider-families-use-adapters-behind-a-registry.md),
  [ADR 0025](../../../decisions/0025-outbound-http-through-the-highest-level-client.md).

**Trạng thái:** thiết kế đã đối chiếu lại với `main` (`22b72294`, 12/10/2026); đang triển khai từ 12/10/2026. Hai
điểm ở [§8](#8-quyết-định) đã chốt; lịch group lệch chữ của issue và còn chờ người tạo issue xác nhận.

## 1. Kết quả

Source SharePoint có chế độ truy cập **Auto Sync (`SYNC`)**: người đọc tìm thấy một tài liệu trong Search, Chat,
citation và file gốc khi và chỉ khi SharePoint cho họ đọc tài liệu đó, trực tiếp, qua SharePoint group hoặc qua
Entra group. Public và Private không đổi.

## 2. Yêu cầu của issue (phải theo)

| # | Yêu cầu | Nguồn |
| --- | --- | --- |
| R1 | Làm theo Onyx EE; chỉ khác Onyx ở việc tìm list theo `sharepointIds.listId` thay vì tiêu đề thư viện | Issue, Q13 |
| R2 | Quyền đọc qua SharePoint REST `roleassignments`; REST chỉ nhận token app-only từ **certificate**; credential client secret bị từ chối khi bật Auto Sync, với thông báo rõ | Issue |
| R3 | Quyền Entra: SharePoint `Sites.FullControl.All` (hoặc full control từng site với `Sites.Selected`) và Graph `GroupMember.Read.All` | Issue |
| R4 | Doc sync: `$expand=Member,RoleDefinitionBindings`, bỏ Limited Access (`RoleTypeKind` 1/9); user → UPN bỏ `.onmicrosoft`; Entra group `{displayName}_{id}`; SharePoint group `{siteUrl}::{title}`; ghi id group kể cả group lồng nhau; `spo-grid-all-users` / `c:0(.s\|true` → public | Issue |
| R5 | Tùy chọn `treatSharingLinkAsPublic`, mặc định tắt | Issue |
| R6 | Group sync: role assignment cấp web của từng site → mở rộng đệ quy SharePoint group (REST) và Entra group (Graph, 404 bỏ qua) → group id → email thành viên trực tiếp; tùy chọn duyệt toàn bộ group Entra, mặc định tắt, dừng ở 100.000 | Issue |
| R7 | Lịch: doc sync 30 phút, group sync 5 phút, gắn quyền ngay lúc index đầu | Issue |
| R8 | Token: `ms_user:<email>`, `ms_group:<sourceId>:<groupId>`, `everyone`; người đọc đối chiếu bằng email đăng nhập đã xác minh (ADR 0011) | Issue |
| R9 | SharePoint mặc định `SYNC`; `SYNC` cần credential certificate | Issue |
| R10 | Web: chọn Auto Sync khi tạo và khi đổi truy cập (chỉ credential certificate), tùy chọn link chia sẻ, mã lỗi vi/en | Issue |
| R11 | ADR mở rộng ADR 0011; cập nhật connector spec và verification matrix | Issue |
| R12 | Spike S0.11–S0.12 trên tenant dùng thử (**hết hạn 15/10/2026**), ghi vào Spike ledger, không chứa secret | Issue |

## 3. Đã có sẵn trên `main` từ sau ngày viết issue

Issue viết ngày 19/09/2026. Từ đó connector được chuẩn hoá (MEM-118, MEM-226, MEM-247) và Lark (MEM-245) đã làm cùng
loại việc. Những việc sau của issue **không còn phải làm**:

| Việc trong issue | Hiện trạng | Bằng chứng |
| --- | --- | --- |
| `SourceAccessPolicy`: `SYNC` cho "provider có đồng bộ quyền" thay vì hard-code Drive | Đã có: `access(permissionSync, …)` đọc `SourceProviderCapabilities.permissionSync` của adapter | `connector/source/SourceAccessPolicy.java` |
| `SourceAclChanged` thay `GoogleDriveAclChanged` | Đã có, không gắn provider; `SearchProjectionMaintenance.aclChanged` xếp refresh `ACCESS` | `connector/SourceAclChanged.java` |
| `SYNC_GRANTS` / `READER_TOKENS` thành UNION theo connector | Đã là UNION Drive + Lark; thiếu nhánh SharePoint | `connector/source/persistence/JdbcSourceDocumentRepository.java` |
| `DefaultSharePointSyncService` | Không còn: SharePoint là `SharePointSyncAdapter` chạy trong `SourceSyncEngine` | `connector/sharepoint/SharePointSyncAdapter.java` |
| Provider bundle `sources/…/adapter/sharepoint` | Nay là module `memoryos-integration-microsoft`, package `io.memoryos.integration.microsoft`, trên Microsoft Graph SDK | ADR 0027 |

Việc còn lại là phần riêng của SharePoint: đọc quyền qua REST, lưu snapshot và group, hai loại lượt chạy, bật cờ
`permissionSync`, nhánh SQL, API và web.

## 4. Onyx làm gì (quan sát trong code)

Đọc tại checkout `.tmp/onyx` revision `06aa2b09` (04/09/2026); issue ghi `onyx@711a047`, chưa đối chiếu khác biệt
giữa hai revision.

- **Token REST** (`connector.py: acquire_token_for_rest`): `acquire_token_for_client` với scope
  `https://{tenant}.{suffix}/.default`. `MicrosoftAuthMethod.supports_sharepoint_rest` chỉ đúng với certificate.
- **Quyền một tài liệu** (`permission_utils.py: get_external_access_from_sharepoint`):
  1. nếu `treat_sharing_link_as_public`: `driveItem.permissions`, link scope `anonymous` hoặc `organization` → public;
  2. file: `web.lists.get_by_title(<tên drive>).items.get_by_id(listItemId)`; trang:
     `web.get_file_by_server_relative_url(path).listItemAllFields`, giữ nguyên percent-encoding;
  3. `role_assignments.expand(["Member","RoleDefinitionBindings"])`, trang 100;
  4. bỏ assignment mà mọi binding có `role_type_kind` ∈ {1, 9};
  5. member loại 1 → email từ `user_principal_name` bỏ `.onmicrosoft`; loại 4 → tên `{title}_{guid}`; loại 8 →
     `{siteUrl}::{title}`;
  6. `_resolve_document_groups`: BFS qua group lồng nhau, ghi **tên** mọi group gặp; gặp login public thì cả tài liệu
     public; cache mở rộng group trong checkpoint.
- **GUID của Entra group** (`_get_group_guid_from_identifier`): đã là GUID → dùng; claims token `c:0…|…|<guid>` →
  lấy GUID bằng regex; **nếu không có thì tìm theo `displayName`** qua Graph.
- **Group sync** (`get_sharepoint_external_groups`, mỗi site): `web.role_assignments` (bỏ Limited Access) → chỉ lấy
  group → `_get_groups_and_members_recursively(is_group_sync=True)`: SharePoint group → `site_groups.get_by_name(…).users`;
  Entra group → Graph `groups/{id}/members` (404 bỏ qua); login public → bỏ qua, không lưu. Kết quả: tên group →
  email thành viên **trực tiếp**. `exhaustive_ad_enumeration` duyệt thêm `GET /groups?$top=999`, dừng ở 100.000.
- **Lịch** (`sync_params.py`, `ee/configs/app_configs.py`): doc sync 1.800 giây, group sync 300 giây,
  `initial_index_should_sync=True`, `group_sync_is_cc_pair_agnostic=False`.
- **Doc sync** (`doc_sync.py` → `generic_doc_sync`): tài liệu có trong DB mà lượt đọc quyền không trả về nhận
  `ExternalAccess.empty()`.
- **Kiểm tra khi chọn SYNC** (`probe_role_assignments_permission`, `probe_group_members_permission`): từ chối client
  secret ngay; `GET {site}/_api/web/roleassignments?$top=1` trên tối đa 5 site, 401/403 → lỗi nêu
  `Sites.FullControl.All`; `GET /groups?$top=1&$select=id`, 401/403 → lỗi nêu `GroupMember.Read.All`; lỗi mạng không chặn.

Điểm yếu đã biết của Onyx, **giữ nguyên** vì issue yêu cầu theo Onyx: group sync chỉ bắt đầu từ role assignment cấp
web (O17), nên một Entra group chỉ được cấp quyền ở cấp file không có thành viên trừ khi bật duyệt toàn bộ group Entra;
`normalize_email` là heuristic (O11). Cả hai chỉ làm **thiếu** quyền, không làm lộ tài liệu.

## 5. Thiết kế

### 5.1 Token và HTTP

- `SharePointTokenSource` cấp token theo scope: Graph như hiện nay, và `https://{tenantHost}/.default` cho REST. Phiên
  provider giữ ứng dụng MSAL của mình tới khi đóng, nên token REST được lấy khi lần gọi REST đầu tiên cần đến. Credential
  client secret gọi REST thì thất bại `AUTHORIZATION` trước khi xin token hay gửi request; mã
  `SOURCE_SHAREPOINT_SYNC_REQUIRES_CERTIFICATE` do `core` trả khi chọn `SYNC`.
- REST trả 401 được xếp là `AUTHORIZATION`, không phải `AUTHENTICATION`: token của phiên do Entra vừa cấp, nên
  SharePoint từ chối nó là chuyện quyền của ứng dụng, và credential không bị đánh dấu cần cập nhật.
- Lời gọi REST đi trên chính `GraphTransport` (OkHttp đã mang bốn quy tắc transport và đã phục vụ tải file), cùng
  `Exchange`, ngân sách và deadline của một operation. Không thêm HTTP stack thứ hai. Host của mọi địa chỉ REST phải là
  Tenant SharePoint host của credential, như quy tắc tải file.
- `Accept: application/json;odata=nometadata`; phân trang theo `odata.nextLink`, chỉ theo link trên cùng host.

### 5.2 Port `SharePointGateway.Session` — bổ sung

| Phương thức | Lời gọi |
| --- | --- |
| `DriveItem` có thêm `sharepointIds` (`listId`, `listItemId`, `siteUrl`) | thêm `sharepointIds` vào `$select` của delta, children và item: không tốn lời gọi riêng |
| `itemRoleAssignments(tenantHost, webUrl, listId, listItemId)` | `GET {webUrl}/_api/web/lists(guid'{listId}')/items({listItemId})/roleassignments?$expand=Member,RoleDefinitionBindings&$top=100` |
| `pageRoleAssignments(tenantHost, webUrl, serverRelativeUrl)` | `GET {webUrl}/_api/web/GetFileByServerRelativeUrl('{path}')/ListItemAllFields/roleassignments?…` |
| `webRoleAssignments(tenantHost, webUrl)` | `GET {webUrl}/_api/web/roleassignments?…` |
| `siteGroupMembers(tenantHost, webUrl, groupId)` | `GET {webUrl}/_api/web/sitegroups/getbyid({id})/users` |
| `entraGroupMembers(groupId, link)` | Graph SDK `groups/{id}/members`, `$select=id,displayName,userPrincipalName` |
| `entraGroups(link)` | Graph SDK `groups`, `$select=id,displayName`, `$top=999` (chỉ khi bật duyệt toàn bộ) |
| `sharingLinkScopes(driveId, itemId)` | Graph SDK `drives/{d}/items/{i}/permissions` (chỉ khi bật tùy chọn link chia sẻ) |

Bốn lời gọi REST đọc hết mọi trang trong ngân sách của một operation; hai lời gọi group của Graph trả từng trang.
Một role assignment trả về là `(principal, limitedAccessOnly)`; principal có `type` (`USER`, `ENTRA_GROUP`,
`SHAREPOINT_GROUP`, `OTHER`), `id`, `loginName`, `title`, `userPrincipalName`. Quy tắc diễn giải (bỏ Limited Access,
chuẩn hoá email, tên group, public) nằm ở `core`, nơi test được bằng giá trị thật mà không cần HTTP.

`SharePointProviderMetrics.Operation` thêm `ROLE_ASSIGNMENTS`, `SITE_GROUP`, `ENTRA_GROUP`, `SHARING_LINKS`; outcome
không đổi.

### 5.3 Diễn giải quyền (`core`, `connector.sharepoint`)

Một lớp thuần `SharePointAccess` nhận role assignments và một hàm mở rộng group, trả `{public, userEmails, groupIds}`:

- bỏ assignment `limitedAccessOnly`;
- user: `userPrincipalName` bỏ chuỗi `.onmicrosoft`, viết thường; không có UPN thì bỏ;
- Entra group: id `{title}_{guid}` viết thường, `guid` là phần cuối của login name (sau dấu `|` cuối); phần cuối
  không phải GUID, kể cả claim của owners `{guid}_o`, thì bỏ group đó;
- id group dài quá 512 ký tự thì bỏ ở cả hai phía; một tài liệu đi qua tối đa 1.000 group;
- SharePoint group: id `{webUrl}::{title}` viết thường;
- BFS group lồng nhau, ghi id mọi group gặp, có cache trong một slice;
- login name chứa `c:0-.f|rolemanager|spo-grid-all-users/` hoặc `c:0(.s|true` → public.

### 5.4 Persistence — migration `V155`

| Bảng / cột | Nội dung |
| --- | --- |
| `sharepoint_acl_snapshots` | Khoá `(tenant_id, source_id, file_id)`, FK tới `sharepoint_items` (item bị quên thì snapshot đi theo); `observation_revision`; `access_public`; `user_emails` và `group_ids` (JSONB mảng đã sắp xếp); `status`, `error_code`, thời điểm lần thử và lần thành công. Cùng dạng với `lark_acl_snapshots`; provenance kiểu V75 không được thêm vì chưa có nơi nào đọc nó |
| `sharepoint_group_work` | Việc một lần đọc group còn phải hỏi Microsoft: site (`WEB`), group (`SITE_GROUP`, `ENTRA_GROUP`), toàn bộ directory (`DIRECTORY`); nhờ đó lần đọc tiếp tục qua nhiều slice |
| `sharepoint_group_runs` | Một lần đọc group của một Source, theo thế hệ như Google Group (ADR 0013): `generation`, trạng thái, thời điểm, việc còn phải đọc |
| `sharepoint_group_members` | `(tenant_id, source_id, generation, group_id, member_email)`; chỉ hàng của thế hệ đang hiệu lực được đọc |
| `sharepoint_sources` | `treat_sharing_links_as_public` (mặc định `false`), `next_permission_sync_at`, `active_group_generation` |
| `sharepoint_items` | `list_id`, `list_item_id`, `web_url`: từ `sharepointIds` lúc quan sát, để pha đọc quyền không gọi lại Graph; item có từ trước V155 được bù bằng một lời gọi `item` |
| `sharepoint_sync_runs` | thêm `phase` (`CONTENT`, `GROUPS`, `PERMISSIONS`, `FINISH`) và `permission_checkpoint`; `kind` không đổi |

Mọi bảng mới có `tenant_id` và xoá cascade theo Source. Migration chỉ thêm; release trước chạy được trên schema mới
vì cột mới đều có mặc định.

### 5.5 Đồng bộ (`SharePointSyncAdapter`)

- `capabilities()` trả `permissionSync = true`.
- **Gắn quyền lúc index** (R7): trong lượt refresh của Source `SYNC`, file hoặc trang mới hoặc đổi nội dung được đọc
  quyền ngay trước khi nhận nội dung, nên tài liệu không bao giờ tìm được trước khi biết ai đọc được nó.
- **Thứ tự pha của một lượt:** nội dung, rồi group, rồi quyền (khi đến hạn), rồi kết thúc. Group đi trước vì một site
  trả lời bằng một request, nên ứng dụng thiếu quyền bị phát hiện trước khi hỏi từng item.
- **Trang:** site của một trang lấy từ địa chỉ của nó, phần trước `/SitePages/`; địa chỉ server-relative giữ nguyên
  percent-encoding của Microsoft.
- **Pha quyền** của lượt refresh, khi `next_permission_sync_at` đã đến hạn (30 phút): duyệt `sharepoint_items` của
  Source theo `provider_file_id`, checkpoint theo item, đọc quyền từng item và ghi snapshot. Delta của SharePoint không
  báo thay đổi quyền, nên pha này là cách duy nhất thấy quyền đổi trên item không đổi nội dung. Item đã được hỏi
  trong chính lượt này (lúc nhận) thì bỏ qua. Item Microsoft trả 404 nhận snapshot rỗng (không ai đọc), đúng nghĩa "không
  còn trả về thì mất quyền". Lỗi của một item (403…) ghi `SOURCE_SHAREPOINT_AUTHORIZATION` lên snapshot và giữ kết
  quả thành công trước; lỗi kết nối, throttling và xác thực là lỗi của lượt, theo quy tắc engine.
- **Pha group** của lượt refresh, khi lần đọc trước bắt đầu hơn 5 phút trước (như `GoogleGroupSynchronizer`): với
  mỗi site trong phạm vi, đọc role assignment cấp web, mở rộng đệ quy, ghi thành viên vào một thế hệ mới; chỉ khi đọc
  xong mọi site thì `active_group_generation` chuyển sang thế hệ đó và các thế hệ cũ bị xoá. Lần đọc hỏng giữ nguyên
  thành viên đang hiệu lực. Site là các `web_url` khác nhau của item Source đang giữ. Group đã bị xoá (404) không có
  thành viên. Microsoft từ chối (401/403) thì cả lượt thất bại với `SOURCE_SHAREPOINT_SYNC_PERMISSIONS_DENIED`.
- Duyệt toàn bộ group Entra (R6) là cấu hình triển khai `memoryos.sharepoint.enumerate-directory-groups`, mặc định
  tắt, như biến môi trường của Onyx; khoảng nghỉ giữa hai lần đọc group là `memoryos.sharepoint.group-sync-interval`.
- Chỉ Source `SYNC` có hai pha này, và chúng không phải loại lượt riêng ([§8](#8-quyết-định)). Source chuyển sang
  `SYNC` được hẹn refresh ngay với pha quyền đến hạn; trước lần đọc đầu không ai đọc được tài liệu của nó (như Lark và
  Drive).
- Thay đổi snapshot phát `SourceAclChanged` trong cùng transaction; đổi thành viên group không ghi index.

### 5.6 Truy cập và đối chiếu người đọc

- `SYNC_GRANTS` thêm nhánh SharePoint: `ms_user:<email>` cho mỗi email, `ms_group:<sourceId>:<groupId>` cho mỗi
  group, `everyone` khi `access_public`.
- `READER_TOKENS` thêm `ms_user:<lower(email đã xác minh)>` và `ms_group:<sourceId>:<groupId>` của mọi group thuộc
  lượt đang hiệu lực của một Source `SYNC` có email đó là thành viên.
- `SYNC` cần credential certificate: kiểm khi tạo, khi đổi truy cập (`POST /api/sources/{id}/access`) và khi thay
  phương thức xác thực của credential đang có Source `SYNC`. Mã `SOURCE_SHAREPOINT_SYNC_REQUIRES_CERTIFICATE`.
- Không có bước probe riêng khi chọn `SYNC`. Ứng dụng thiếu `Sites.FullControl.All` hoặc `GroupMember.Read.All` làm
  lượt đồng bộ kế tiếp thất bại với `SOURCE_SHAREPOINT_SYNC_PERMISSIONS_DENIED`, Source hiển thị mã đó kèm quyền cần
  cấp. Một cơ chế cho cả lúc tạo, lúc đổi truy cập và lúc quyền bị thu hồi về sau, và việc đổi truy cập không gọi
  Microsoft trong transaction đang giữ khoá.
- Yêu cầu riêng của provider khi đổi truy cập đi qua `SourceSyncAdapter.accessChanging`, trong cùng transaction.

### 5.7 API và web

- `SharePointConfigurationResponse` thêm `treatSharingLinksAsPublic`; `SharePointCredentialResponse` đã có
  `authMethod`, đủ để web biết credential nào bật được Auto Sync.
- `PUT /api/sources/{sourceId}/sharepoint/sharing-links` đặt tùy chọn (global `SOURCES_MANAGE` độc quyền, có audit)
  và hẹn đọc lại quyền ngay. Tùy chọn mặc định tắt và chỉ đặt được sau khi tạo: Source được tạo bất đồng bộ, nên đặt
  lúc tạo sẽ phải đưa nó qua toàn bộ đường xác minh scope.
- Web: bước Truy cập có Auto Sync khi credential dùng certificate, và giải thích vì sao không có khi dùng client
  secret; hộp thoại đổi chế độ hiển thị có Auto Sync cho SharePoint và báo lỗi của server khi credential là client
  secret; công tắc "Coi link chia sẻ là công khai" trong cấu hình của Source `SYNC`; mã lỗi mới có vi/en.
- OpenAPI và client hey-api sinh lại trong cùng thay đổi.

## 6. Khác biệt so với Onyx

| # | Onyx | MemoryOS | Loại và lý do |
| --- | --- | --- | --- |
| 1 | Tìm list theo tiêu đề thư viện đảo từ tên drive | Tìm theo `sharepointIds.listId` | Issue (R1): tiêu đề hỏng với site tiếng Việt (lỗi O3) |
| 2 | Không tìm được GUID trong login name thì tìm Entra group theo `displayName` | Bỏ group đó, không ai đọc qua nó | **Cần chốt** ([§8](#8-điểm-cần-chủ-sở-hữu-chốt)): tên hiển thị không duy nhất, khớp nhầm group làm lộ tài liệu |
| 3 | SharePoint group theo `get_by_name(title)` | `getbyid(id)` | MemoryOS: id có sẵn trong role assignment, không phải mã hoá tiêu đề vào đường dẫn; kết quả như nhau |
| 4 | `ExternalAccess` gắn vào tài liệu trong index | Snapshot riêng + token suy ra bằng SQL, một quy tắc cho index và recheck | MemoryOS: contract MEM-105 |
| 5 | Group gắn theo cc-pair, có tiền tố `sharepoint_` | Token `ms_group:<sourceId>:<groupId>` | Issue (R8); cùng nghĩa |
| 6 | Cache mở rộng group nằm trong checkpoint của cả lượt | Cache trong bộ nhớ của một slice (45 giây) | MemoryOS: không lưu thêm trạng thái; tốn lời gọi hơn ở Source lớn |
| 7 | Retry bằng `sleep` trong luồng | Lỗi của lượt, engine lùi lịch theo `Retry-After` | MemoryOS: quy tắc engine |
| 8 | Doc sync và group sync là tác vụ riêng, 30 phút và 5 phút | Hai pha của lượt refresh; group đọc lại mỗi lượt refresh cách lần trước ít nhất 5 phút, tức theo chu kỳ đồng bộ của Source (mặc định 30 phút) | MemoryOS ([§8](#8-quyết-định)); **lệch R7, chờ người tạo issue xác nhận** |
| 9 | Probe quyền Entra khi chọn permission sync | Không probe; lượt đồng bộ thất bại với mã nêu quyền còn thiếu | MemoryOS: [§5.6](#56-truy-cập-và-đối-chiếu-người-đọc) |
| 10 | Claim owners của group (`{guid}_o`) được coi như group | Bỏ | MemoryOS: owners không phải thành viên; chỉ làm thiếu quyền |

## 7. Ngoài phạm vi

Phân quyền theo hierarchy node (site, thư viện, thư mục) của Onyx; sovereign cloud; hiển thị quyền đã thu thập cho
quản trị viên; liên kết danh tính Microsoft của người đọc ngoài email đã xác minh.

## 8. Quyết định

Chốt 12/10/2026 với người được giao issue.

1. **Đọc quyền và group là hai pha của lượt refresh, không phải loại lượt riêng.** Phương án "lượt `PERMISSIONS` và
   `GROUPS` là attempt riêng" bị loại vì engine hiện tại: `JdbcSourceSyncRepository.complete` đặt `next_sync_at` một
   chu kỳ sau mỗi attempt xong, nên một lượt `GROUPS` 5 phút đẩy refresh ra xa mãi; `due` bỏ qua Source đang có
   attempt, nên lần index đầu kéo dài vẫn chặn group; mỗi attempt mở phiên và xin token mới, 288 lần mỗi ngày cho mỗi
   Source. Google Drive và Lark đều đọc group trong lượt refresh. Cái giá: group cập nhật theo chu kỳ đồng bộ của
   Source thay vì cố định 5 phút (lệch R7). Nếu người tạo issue buộc 5 phút, engine phải biết loại attempt trước.
2. **Không tìm Entra group theo tên hiển thị** (khác biệt #2): tên hiển thị không duy nhất, khớp nhầm làm lộ tài
   liệu; bỏ group chỉ làm thiếu quyền.

## 9. Rủi ro và khoảng trống bằng chứng

- **Chưa có lời gọi REST nào được kiểm trên tenant thật.** Dạng URL, tên trường JSON (`Member.PrincipalType`,
  `LoginName`, `UserPrincipalName`, `RoleDefinitionBindings[].RoleTypeKind`) và mã lỗi khi dùng client secret lấy từ
  code Onyx và tài liệu Microsoft. Spike S0.11–S0.12 phải chạy trước 15/10/2026, khi tenant dùng thử hết hạn; sau
  ngày đó cần tenant khác.
- Chưa đối chiếu Onyx `06aa2b09` (đã đọc) với `711a047` (issue ghi). Checkout Onyx không có trong worktree này; quy
  tắc diễn giải đang lấy từ [tham chiếu Onyx §7](../../completed/sharepoint-connector/onyx-sharepoint-reference.md#7-đồng-bộ-quyền-ee).
- Throttling: lượt `PERMISSIONS` tốn một lời gọi REST mỗi item mỗi 30 phút; Source lớn có thể chạm giới hạn của
  tenant. Chỉ đo được trên corpus thật.
- Auto Sync mạnh ngang việc xác minh email của IdP (ADR 0011) và heuristic `normalize_email` (O11).
