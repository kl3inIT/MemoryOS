# SharePoint connector — Tham chiếu Onyx

Design: [design.md](design.md). Nguồn: `onyx-dot-app/onyx` nhánh `main` tại `57156993936cabebebc385ce1df9a72da1ee5685` (15/09/2026), đọc ngày 16/09/2026 qua GitHub raw.

Tài liệu này ghi **hành vi quan sát được trong code Onyx**. Phần suy luận được ghi rõ là suy luận. Phần chưa đọc được nằm ở §12.

Viết tắt đường dẫn:
- **C** = `backend/onyx/connectors/sharepoint/connector.py`
- **CU** = `backend/onyx/connectors/sharepoint/connector_utils.py`
- **MU** = `backend/onyx/connectors/microsoft_utils/`
- **PU** = `backend/ee/onyx/external_permissions/sharepoint/permission_utils.py`

## 0. Quan sát thực tế trên Onyx Cloud — 16/09/2026

- **Tenant sai:** tenant "Default Directory" của tài khoản Azure cá nhân không có gói Microsoft 365. Admin consent thất bại với lỗi "không có subscription hoặc service principal cho SharePoint, Microsoft Graph", nên phải dùng tenant có gói Microsoft 365.
- **Cấu hình đã chạy được:**
  - tenant Microsoft 365 Business Standard dùng thử;
  - app Entra single-tenant;
  - Microsoft Graph `Sites.Read.All` loại Application, đã admin consent;
  - credential Client Secret;
  - một URL site tạo bằng tiếng Anh, access "Organization Public".
- **Kết quả:** trạng thái `Initial Indexing` rồi hoàn tất với 3 tài liệu: một file `.docx` và các trang của site.
- **Site tiếng Việt:** chưa thử. Theo code (§3), thư viện "Tài liệu" không khớp bảng tên đã bản địa hóa khi URL chỉ trỏ tới site.

## 1. Xác thực

- **Credential JSON:** `authentication_method`, `sp_client_id`, `sp_directory_id`, `sp_client_secret`, `sp_certificate_password`, `sp_private_key` (`C.SharepointConnector.load_credentials`).
- **`MicrosoftAuthMethod.parse`** (`MU/graph_auth.py`):
  - để trống là `client_secret`;
  - giá trị lạ trả `ConnectorValidationError("Unknown authentication method …")`;
  - thiếu ID trả "Client ID is required" / "Directory (tenant) ID is required".
- **`build_msal_app`:** `msal.ConfidentialClientApplication`, authority `{authority_host}/{directory_id}`.
  - **Client secret:** truyền secret làm client credential.
  - **Certificate:** `sp_private_key` là PFX base64.
    - `load_certificate_from_pfx` giải bằng `pkcs12.load_key_and_certificates`, đổi key sang PKCS8 PEM, truyền `{private_key, thumbprint=SHA1}`.
    - Thiếu key hoặc mật khẩu: "Private key and certificate password are required for certificate authentication".
- **Scope token:**
  - Graph: `{graph_api_host}/.default`.
  - SharePoint REST: `https://{tenant}.{sharepoint_domain_suffix}/.default` (`C.acquire_token_for_rest`).
- **REST chỉ nhận certificate:** `MicrosoftAuthMethod.supports_sharepoint_rest` chỉ đúng với certificate, vì SharePoint REST từ chối token app-only lấy bằng client secret.
- **Tenant domain** (`_resolve_tenant_domain`): nhãn đầu của hostname trong URL site đã cấu hình. Không cấu hình site thì lấy từ `GET /sites/root` → `siteCollection.hostname`.
- **REST `ClientContext`:** tạo lại khi đổi site hoặc sau 30 phút (`_REST_CTX_MAX_AGE_S`), đồng thời nạp lại credential.

### Sovereign cloud (`MU/graph_env.py`)

| Môi trường | Graph | Authority | SharePoint suffix |
| --- | --- | --- | --- |
| Global | graph.microsoft.com | login.microsoftonline.com | sharepoint.com |
| GCC High | graph.microsoft.us | login.microsoftonline.us | sharepoint.us |
| DoD | dod-graph.microsoft.us | login.microsoftonline.us | sharepoint.us |
| China | microsoftgraph.chinacloudapi.cn | login.chinacloudapi.cn | sharepoint.cn |
| Germany | graph.microsoft.de | login.microsoftonline.de | sharepoint.de |

- Graph host lạ, hoặc authority không khớp Graph host, bị `ConnectorValidationError` ngay trong `__init__`.
- `sharepoint_domain_suffix` do người dùng nhập bị ghi đè bằng suffix của môi trường, kèm log cảnh báo.

## 2. Cấu hình connector (`SharepointConnector.__init__`)

| Trường | Mặc định |
| --- | --- |
| `sites` | `[]` |
| `excluded_sites`, `excluded_paths` | `[]` (trim khoảng trắng) |
| `include_site_pages`, `include_site_documents` | `True` |
| `treat_sharing_link_as_public` | `False` |
| `authority_host`, `graph_api_host`, `sharepoint_domain_suffix` | commercial |
| `batch_size` | `INDEX_BATCH_SIZE` |

Khóa backend `exhaustive_ad_enumeration` chỉ được đọc bởi group sync (§7).

## 3. URL, site, thư viện, thư mục

- **Phân tích URL** (`_extract_site_and_drive_info`, `_strip_share_link_tokens`):
  - bỏ tiền tố share link (`/:f:/r/`…);
  - tìm đoạn `sites`/`teams`/`personal` đầu tiên; site URL là base cộng đoạn đó và đoạn kế tiếp;
  - đoạn tiếp theo (đã unquote) là `drive_name`, phần còn lại là `folder_path`.
  - Ví dụ: `…/sites/SampleSite/Shared%20Documents/Nested/Path` → drive "Shared Documents", folder "Nested/Path".
- **Không cấu hình site** (`fetch_sites`):
  - gọi Graph `/sites/getAllSites` và theo phân trang;
  - bỏ URL chứa chuỗi cố định `-my.sharepoint`;
  - áp glob `excluded_sites` (`fnmatch`, bỏ `/` cuối);
  - danh sách rỗng trả `RuntimeError("No sites found in the tenant")`.
- **Chọn thư viện** (`_resolve_drive`): khớp tên không phân biệt hoa thường, hoặc khớp `SHARED_DOCUMENTS_MAP[d.name] == drive_name` với map:

  | Tên drive | Tên thư viện |
  | --- | --- |
  | `Documents` | `Shared Documents` |
  | `Dokumente` | `Freigegebene Dokumente` |
  | `Documentos` | `Documentos compartidos` |

  Map này là lý do UI Onyx ghi chỉ hỗ trợ tiếng Anh, Đức, Tây Ban Nha.
- **OneDrive:**
  - ưu tiên `driveType` `personal`/`business`, sau đó tới các tên `onedrive`, `onedrive for business`, `documentlibrary`;
  - trên `/personal/`, khớp mơ hồ trả None.
- **Thư mục:**
  - có `folder_path` thì duyệt BFS từ `root:/{path}:/children` với `$top=200` (`MU/drive_items.iter_drive_items_paged`);
  - glob `excluded_paths` so với cả đường dẫn tương đối lẫn tên file (`_is_driveitem_excluded`).

## 4. Mô hình đồng bộ

- **Lớp:** `SharepointConnector(SlimConnector, SlimConnectorWithPermSync, CheckpointedConnectorWithPermSync[SharepointConnectorCheckpoint], Resolver)`.
- **Checkpoint:**
  - hàng đợi site và site hiện tại;
  - hàng đợi drive, drive hiện tại (tên, id, web URL);
  - `current_drive_delta_next_link`, `process_site_pages`;
  - `seen_hierarchy_node_raw_ids`, `seen_document_ids`;
  - `permission_cache`.
- **Các pha** (`_load_from_checkpoint`; trả checkpoint sau mỗi bước):
  1. Khởi tạo site.
  2. Liệt kê drive; bỏ qua nếu tắt tài liệu.
  3. Xử lý drive:
     - resolve drive;
     - không có folder: mỗi lần gọi xử lý một trang delta;
     - có folder: duyệt BFS hết folder trong một lần gọi.
  4. Sang drive kế.
  5. Xử lý trang site, rồi sang site kế.
- **Delta dạng lai** (`build_delta_start_url`, `fetch_one_delta_page`):
  - gọi `GET /drives/{id}/root/delta?$top=200&$select=…&token=<ISO start>`;
  - **không bao giờ lưu `@odata.deltaLink`**; mỗi lượt bắt đầu lại từ timestamp `start` của framework;
  - lọc thêm phía client theo `[start, end]` bằng thời điểm muộn hơn giữa `createdDateTime` và `lastModifiedDateTime`;
  - bỏ qua folder và bản ghi `deleted`;
  - khi gặp 410, lưu URL quét lại toàn bộ (không token) vào checkpoint;
  - `seen_document_ids` chống trùng trong một drive.
- **Trang site** (`_fetch_site_pages`):
  - mỗi lượt liệt kê toàn bộ `GET /sites/{siteId}/pages/microsoft.graph.sitePage?$expand=canvasLayout`, rồi lọc theo `lastModifiedDateTime`;
  - 404 thì dừng;
  - 400 `invalidRequest` (canvas hỏng) thì chuyển sang expand từng trang; trang hỏng chỉ lấy metadata.
- **Reindex có mục tiêu** (`reindex`): lấy lại tài liệu lỗi theo `document_link`.
  - Link `/sitepages/` → `_reindex_site_page`.
  - Link khác → `GET /drives/{d}/items/{id}` trên từng thư viện của site.

## 5. Tài liệu sinh ra

### File trong thư viện (`_convert_driveitem_to_document_with_permissions`)

| Trường | Giá trị |
| --- | --- |
| `id` | driveItem id trần (chỉ duy nhất trong một drive) |
| `semantic_identifier` | tên file |
| `doc_created_at` / `doc_updated_at` | `createdDateTime` / `lastModifiedDateTime` |
| `primary_owners` | `lastModifiedBy.user` |
| `metadata` | `{"drive": drive_name}` |
| section link | `webUrl` (Office file có dạng `_layouts/15/Doc.aspx`) |

**Lọc** (`_process_drive_item`):
- extension phải thuộc `ALL_ALLOWED_EXTENSIONS`;
- bỏ file thiếu mime hoặc mime nằm trong `EXCLUDED_IMAGE_TYPES`;
- ngưỡng `SHAREPOINT_CONNECTOR_SIZE_THRESHOLD` 20 MB, kiểm tra ở ba chỗ: `size`, probe HEAD/Range và khi stream có giới hạn.

**Tải và extraction:**
- tải `@microsoft.graph.downloadUrl` trước, rồi mới tới `/drives/{d}/items/{i}/content`;
- ảnh → `store_image_and_create_section`;
- bảng → `extract_and_stage_tabular_file`;
- còn lại → `extract_text_and_images`.

### Trang site (`_convert_sitepage_to_document`)

| Trường | Giá trị |
| --- | --- |
| `id` | page id |
| `semantic_identifier` | tên không kèm `.aspx` |
| section | một `TextSection(link=webUrl)` |
| `doc_updated_at` | `lastModified` hoặc `created` |
| `primary_owners` | `createdBy.user` |

- **Nội dung:** `# title`, mô tả, `textWebPart.innerHtml` (bỏ tag bằng regex), và `standardWebPart.serverProcessedContent.searchablePlainTexts` kèm title/description.
- **Metadata** `{"site": site_name}`, nhưng `site_name` lấy từ `site_descriptor.drive_name` nên thường rỗng.

### Hierarchy node

- SITE: id là URL site.
- DRIVE: id là web URL của drive.
- FOLDER: id dạng `{site}/{drive}/{path}`.

## 6. Pruning, retry, lỗi, validation

**Pruning** (`retrieve_all_slim_docs`):
- sinh SlimDocument theo lô 1000 và không gọi SharePoint REST;
- lỗi từng item/trang chỉ log rồi bỏ qua.

**Retry** (`MU/graph_client.py`):
- `graph_api_get_json`: 5 lần với 429/500/502/503/504, tôn trọng `Retry-After`, lấy token mới mỗi lần.
- `sleep_and_retry` cho truy vấn office365 SDK: 3 lần với 429/503.
- Backoff 5 s → 10 s → 20 s, trần 30 s, có jitter. Retry là `sleep` ngay trong luồng.
- Stream tải file retry lỗi mạng 3 lần.
- URL tải bị che trong log (`scrub_url_credentials`), vì chứa token `tempauth`.
- **Không có retry:** `sites.get_by_url`, `drives.get().execute_query()`, `get_all_sites`.

**Lỗi theo lượt:**
- `_create_document_failure` → "SharePoint document '<name>': …".
- `_create_entity_failure` → "Failed to access site", "Failed to access drive '…' in site '…'", "Failed to fetch delta page for drive", "Failed to fetch site pages".
- Lỗi chứa "403 Client Error", "404 Client Error" hoặc "invalid_client" được ném lại.
- `PER_SITE_GRAPH_FAILURE_STATUSES={403,404,410,423}` được định nghĩa và test nhưng không dùng.

**`validate_connector_settings`:**
- "At least one content type must be enabled…".
- URL phải bắt đầu `https://` và chứa `/sites/`, `/teams/` hoặc `/personal/`; nếu không: "Site URLs must be full Sharepoint/OneDrive URLs…".
- Kiểm SSRF (`validate_outbound_http_url`).
- Host phải là `{tenant}.{suffix}` hoặc `{tenant}-my.{suffix}` (`_validate_site_url_host`), để token REST không bị gửi sang tenant khác.

**Khi access là SYNC** (`backend/ee/onyx/connectors/perm_sync_valid.py:validate_sharepoint_perm_sync`):
- `probe_role_assignments_permission`:
  - từ chối credential client secret ngay;
  - gọi song song `GET {site}/_api/web/roleassignments?$top=1` trên tối đa 5 site;
  - 401/403 → "…grant 'Sites.FullControl.All'… If using 'Sites.Selected', ensure the app has been explicitly granted full-control on each affected site collection";
  - lỗi mạng không chặn; không có site thì bỏ qua.
- `probe_group_members_permission`: `GET /groups?$top=1&$select=id`; 401/403 → "…grant 'GroupMember.Read.All'…".
- API tạo connector trả HTTP 400 `"Connector validation error: " + msg`.

## 7. Đồng bộ quyền (EE)

**Lịch** (`backend/ee/onyx/external_permissions/sync_params.py`, `backend/ee/onyx/configs/app_configs.py`):
- `initial_index_should_sync=True`;
- doc sync mỗi 30 phút;
- group sync mỗi 5 phút.

**Doc sync** (`doc_sync.py` → `generic_doc_sync`):
- `retrieve_all_slim_docs_perm_sync` trả `DocExternalAccess`;
- tài liệu có trong DB nhưng không được trả về nhận `ExternalAccess.empty()`;
- quyền cũng được gắn ngay khi index (`load_from_checkpoint_with_perm_sync`).

**Lấy quyền cho từng tài liệu** (`PU.get_external_access_from_sharepoint`):
- **File:**
  1. nếu `treat_sharing_link_as_public` bật, link scope `anonymous`/`organization` biến tài liệu thành public;
  2. lấy `sharepointIds.listItemId`;
  3. gọi REST `web.lists.get_by_title(<tên drive đảo lại>).items.get_by_id`.
- **Trang:** `web.get_file_by_server_relative_url(path).listItemAllFields`.
- **Role assignment:** `$expand=Member,RoleDefinitionBindings`, 100 mỗi trang; bỏ assignment chỉ có Limited Access (`role_type_kind` 1 hoặc 9).

**Principal:**

| Loại | Xử lý |
| --- | --- |
| 1 User | email từ `user_principal_name` sau khi bỏ `.onmicrosoft` (`normalize_email`) |
| 4 Entra group | mở rộng qua Graph `groups/{id}/members`, BFS; 404 bỏ qua |
| 8 SharePoint group | mở rộng qua REST `site_groups.get_by_name(...).users` |
| 3 Anonymous | có hằng số, không xử lý |

**Định danh group:**
- Entra: `"{displayName}_{groupId}"`.
- SharePoint: `"{siteUrl}::{title}"`.
- Cả hai viết thường, có tiền tố `sharepoint_` khi `add_prefix`.
- Cache theo `"{principalType}:{identity}"`.

**Public:** login name chứa `c:0-.f|rolemanager|spo-grid-all-users/` (Everyone except external users) hoặc `c:0(.s|true` (Everyone) → `is_public=True`.

**Group sync** (`PU.get_sharepoint_external_groups`):
- đọc role assignment **cấp web** của từng site rồi mở rộng group;
- `exhaustive_ad_enumeration` duyệt thêm mọi group trong tenant (`$top=999`, dừng ở 100.000 group).

## 8. Quyền Entra

**Theo code:**
- **Index:** Graph `Sites.Read.All`.
- **Permission sync:**
  - credential certificate;
  - SharePoint `Sites.FullControl.All` (Application), hoặc full control trên từng site nếu dùng `Sites.Selected`;
  - Graph `GroupMember.Read.All` (hoặc `Group.Read.All` / `Directory.Read.All`).

**Theo docs** (`docs.onyx.app/admins/connectors/official/sharepoint/{client-secret,certificate}`):
- **Client secret:** Graph `Sites.Read.All` cộng SharePoint `Sites.FullControl.All` "để đọc role assignment khi pruning". Mâu thuẫn với code (§11 O1).
- **Certificate có permission sync:**
  - Graph Application: `Directory.Read.All`, `Group.Read.All`, `GroupMember.Read.All`, `Member.Read.Hidden`, `User.Read.All`;
  - Graph Delegated: `User.Read`;
  - SharePoint Application: `Sites.FullControl.All`, `User.Read.All`.
- **`Sites.Selected`:** cấp qua Graph `POST /sites/{id}/permissions`, role `read` để index hoặc `fullcontrol` để đồng bộ quyền.

## 9. Giao diện

### Trường cấu hình (`web/src/lib/connectors/connectors.tsx`)

**`sites`:** list, nhãn "Sites". Mô tả:
- "If no sites are specified, all sites in your organization will be indexed (Sites.Read.All permission required)."
- "Specifying '…/sites/support' for example only indexes this site."
- "Specifying '…/sites/support/subfolder' for example only indexes this folder."
- "Specifying sites currently works for SharePoint instances using English, Spanish, or German…"

**Advanced:**

| Key | Nhãn | Mặc định | Mô tả |
| --- | --- | --- | --- |
| `include_site_documents` | "Index Documents" | true | "Index documents of all SharePoint libraries or folders defined above." |
| `include_site_pages` | "Index ASPX Sites" | true | "Index aspx-pages of all SharePoint sites defined above, even if a library or folder is specified." |
| `treat_sharing_link_as_public` | "Treat sharing links as public?" | false | "When enabled, documents with a sharing link (anonymous or organization-wide) are treated as public… When disabled, only users and groups with explicit role assignments can see the document." |
| `excluded_sites` | "Excluded Sites" | – | "Site URLs or glob patterns to exclude from indexing…" |
| `excluded_paths` | "Excluded Paths" | – | "Glob patterns for file paths to exclude… Examples: '*.tmp', '~$*', 'Archive/*'." |
| `authority_host` | "Authority Host" | https://login.microsoftonline.com | GCC High/DoD dùng `.us` |
| `graph_api_host` | "Graph API Host" | https://graph.microsoft.com | GCC High/DoD dùng `graph.microsoft.us` |
| `sharepoint_domain_suffix` | "SharePoint Domain Suffix" | sharepoint.com | GCC High dùng `sharepoint.us` |

### Credential (`web/src/lib/connectors/credentials.ts`, `CredentialFieldsRenderer.tsx`)

- **Hai tab:**
  - "Client Secret": client ID, client secret, directory ID; `disablePermSync: true`.
  - "Certificate Authentication": client ID, directory ID, certificate password, private key.
- **Hành vi tab:** đổi tab xóa trường của phương thức kia. Mô tả phương thức chỉ hiện khi phương thức không có trường nào, nên thực tế không bao giờ hiện.
- **Nhãn:** "SharePoint Client ID", "SharePoint Client Secret", "SharePoint Directory ID", "SharePoint Certificate Password", "SharePoint Private Key".
- **Upload private key:**
  - chỉ nhận `.pfx` ≤ 10 KB: "Please upload a .pfx file containing the private key for SharePoint. The file size must be under 10KB.";
  - gửi multipart tới `POST /api/manage/credential/private-key`;
  - backend `process_sharepoint_private_key_file` kiểm PKCS#12 rồi lưu base64.

### Luồng tạo (`AddConnectorPage.tsx`)

- **Các bước:** "Credential" → "Connector" → "Advanced (optional)".
- **Bước Connector:**
  - "Connector Name", các trường chính.
  - "Document Access" với ba lựa chọn:
    - "Private: Only users who have explicitly been given access to this connector (through the User Groups page)…"
    - "Public: Everyone with an account on Onyx…"
    - "Auto Sync Permissions: We will automatically sync permissions from the source…"
  - Auto Sync bị tắt với client secret: "Current credential auth method doesn't support Auto Sync Permissions…".
  - Mặc định Auto Sync khi dùng được; mọi lựa chọn access cần gói Business.
- **Bước Advanced:**
  - "Prune Frequency (hours)", 0 là tắt;
  - "Refresh Frequency (minutes)", mặc định 30;
  - "Indexing Start Date".
- **Nút:** "Previous", "Continue", "Advanced", "Create Connector". Lỗi hiện qua toast "Error: {detail}".

## 10. Test

**Daily với tenant thật** (`backend/tests/daily/connectors/sharepoint/test_sharepoint_connector.py`):
- toàn tenant; chỉ tài liệu; chỉ trang; `excluded_sites`;
- folder cụ thể, root, thư viện khác;
- owners, `doc_updated_at` UTC, link `Doc.aspx`;
- poll theo cửa sổ thời gian;
- text trang CollabHome/Home;
- hierarchy node;
- tập external group ID;
- tenant domain;
- reindex drive item và site page.

**Unit** (`backend/tests/unit/onyx/connectors/sharepoint/`):
- delta checkpoint: một trang mỗi chu kỳ, resume sau serialize, BFS một lần, 410 lưu URL resync, chống trùng, cùng id khác drive;
- denylist; khớp drive bản địa hóa; OneDrive `driveType`;
- fetch trang: 404, 400 fallback;
- resilience; load credentials; cache REST context;
- host validation;
- slim và probe validation; URL parsing; hierarchy.

**EE** (`test_permission_utils.py`):
- `sharepointIds`;
- cache group theo site, không ghi khi 404;
- vòng group; public group;
- Limited Access;
- exhaustive Entra;
- ma trận `treat_sharing_link_as_public`.

## 11. Lỗi và điểm yếu đã xác định

| # | Quan sát | Bằng chứng |
| --- | --- | --- |
| O1 | Docs yêu cầu SharePoint `Sites.FullControl.All` cho client secret "để pruning", nhưng pruning không gọi REST và REST từ chối token client secret | §6, §8 |
| O2 | Chỉ khớp thư viện mặc định theo 3 ngôn ngữ; site ngôn ngữ khác (ví dụ tiếng Việt "Tài liệu") không resolve khi URL chỉ tới site | `SHARED_DOCUMENTS_MAP` |
| O3 | Permission sync lấy list theo title đảo từ tên drive, cùng lỗi bản địa hóa với O2 | `get_by_title` trong PU |
| O4 | Không lưu `@odata.deltaLink`: dùng timestamp token cộng lọc phía client; xóa chỉ phát hiện ở lượt prune riêng | `build_delta_start_url` |
| O5 | Root là thư mục thì duyệt BFS `children`. Graph ghi rằng chỉ delta mới bảo đảm đọc đủ khi có ghi đồng thời | [driveItem delta](https://learn.microsoft.com/en-us/graph/api/driveitem-delta?view=graph-rest-1.0) |
| O6 | Document id là driveItem id trần, chỉ duy nhất trong một drive | §5 |
| O7 | Metadata `site` của trang lấy từ `drive_name` nên thường rỗng | `_convert_sitepage_to_document` |
| O8 | Lọc OneDrive bằng chuỗi cố định `-my.sharepoint`, không theo suffix của sovereign cloud | `fetch_sites` |
| O9 | Các lời gọi office365 SDK tìm site/drive/all sites không retry | §6 |
| O10 | `PER_SITE_GRAPH_FAILURE_STATUSES` có định nghĩa và test nhưng không dùng | §6 |
| O11 | `normalize_email` bỏ `.onmicrosoft` khỏi UPN: ánh xạ heuristic có thể khớp nhầm người | PU |
| O12 | Mô tả phương thức xác thực trong UI không bao giờ hiển thị | `CredentialFieldsRenderer` |
| O13 | HTML web part được chuyển thành text bằng regex, mất heading, list và table | `_convert_sitepage_to_document` |
| O14 | Retry `sleep` ngay trong luồng worker, tối đa 30 s mỗi lần | `MU/graph_client.py` |
| O15 | Host nhập tự do rồi mới đối chiếu; bảng vẫn có Germany, trong khi Microsoft Cloud Deutschland đã đóng ngày 29/10/2021 | `graph_env.py` |
| O16 | Principal Anonymous (type 3) được định nghĩa nhưng không xử lý | PU |
| O17 | Group sync chỉ đọc role assignment cấp web, không đọc cấp thư viện/item | `get_sharepoint_external_groups` |

## 12. Khoảng trống bằng chứng

- Chưa xác minh cách `extract_ids_from_runnable_connector` chọn biến thể có permission sync.
- Chưa tìm thấy chỗ đường slim doc sync thêm tiền tố `sharepoint_` cho tên group.
- Chưa đối chiếu giá trị prune mặc định phía backend (UI dự phòng 600 giờ).
- Chưa chạy test Onyx. Test daily cần tenant nội bộ của Onyx.
- Onyx Cloud chỉ được quan sát qua một luồng client secret (§0). Chưa thử certificate, Auto Sync hay site ngôn ngữ khác.
