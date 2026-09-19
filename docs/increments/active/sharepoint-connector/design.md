# MEM-126 — SharePoint connector theo logic Onyx

**Liên kết**

- Tracking: [MEM-126](https://linear.app/memory-os/issue/MEM-126), issue con của [MEM-118](https://linear.app/memory-os/issue/MEM-118).
- Tham chiếu hành vi: [onyx-sharepoint-reference.md](onyx-sharepoint-reference.md). Tham chiếu giao diện: [ui-references.md](ui-references.md). Kế hoạch: [plan.md](plan.md).
- Liên quan:
  - [Google Drive ingestion](../google-drive-structured-ingestion/design.md), [connector spec](../../../specs/connector.md), [ingestion spec](../../../specs/ingestion.md);
  - [ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md), [ADR 0006](../../../decisions/0006-shared-connector-bundle-and-jdbc-source-persistence.md);
  - [MEM-105](https://linear.app/memory-os/issue/MEM-105), [MEM-106](https://linear.app/memory-os/issue/MEM-106).

**Trạng thái:** kế hoạch đã chốt hướng, chưa triển khai (16/09/2026). Các quyết định nằm ở §7.

**Baseline:** Onyx SharePoint connector, `onyx-dot-app/onyx@5715699`. Ngày 16/09/2026 người dùng yêu cầu:

- xây tính năng kết nối SharePoint theo logic của Onyx;
- nghiên cứu giao diện qua Mobbin;
- dựng giao diện bằng component shadcn.

## 1. Kết quả

MemoryOS kết nối được SharePoint Online với hành vi tương đương Onyx:

- **Credential Entra app:**
  - thuộc Tenant, dùng lại cho nhiều Source;
  - xác thực bằng Client Secret hoặc Certificate (`.pfx`);
  - MemoryOS kiểm tra với Microsoft trước khi lưu.
- **Source SharePoint:**
  - phạm vi là tất cả site app đọc được, hoặc danh sách URL site/thư viện/thư mục;
  - loại trừ site và đường dẫn theo glob;
  - bật/tắt riêng tài liệu thư viện và trang site (`.aspx`).
- **Đồng bộ theo mô hình Onyx:**
  - lượt đầu lấy toàn bộ;
  - mỗi lượt refresh lấy thay đổi theo cửa sổ thời gian kể từ lượt thành công trước;
  - file hoặc trang đã bị xóa được dọn ở lượt prune theo Prune Frequency;
  - tuân thủ throttling của Microsoft.
- **Tài liệu** đi qua pipeline hiện có: object storage → INGESTION → current Document → Search/Chat. Citation mở đúng file hoặc trang gốc [MEM-118].
- **Truy cập:** Public/Private như FILE và Auto Sync theo quyền SharePoint như Google Drive, theo contract của MEM-105 [Q13].
- **Giao diện** trong phần Nguồn:
  - chọn loại nguồn, tạo nguồn qua các bước, trang chi tiết, lịch sử;
  - lỗi có hướng xử lý;
  - dùng control shadcn, có vi/en.

## 2. Hiện trạng và phụ thuộc

### MemoryOS (`origin/main` `3ccafac7`)

- **Loại nguồn:** `SourceType` chỉ có `FILE`, `GOOGLE_DRIVE`. Không có code hay tài liệu nào về Microsoft Graph, Entra hoặc MSAL; SharePoint chỉ xuất hiện trên landing page.
- **Không có điểm plug-in provider chung.** Google Drive có service, bảng, workload, route API và trang web riêng.
  - Service: `DefaultGoogleDriveSourceService`, `DefaultConnectorSyncService`.
  - Bảng: `google_drive_*`.
  - Workload: `GOOGLE_DRIVE_SELECTION_VALIDATION`.
  - Route API: `/api/sources/{id}/google-drive/*`.
  - Trang web: `web/src/features/sources/google-drive-*`.
- **Dùng lại nguyên vẹn:**
  - acquisition → `ObjectWriteService` → input version → INDEX attempt có `source_sync_attempt_id` → `DefaultIngestionCoordinator` → Document;
  - `source_sync_attempts`, counter, safe error, retention;
  - PG → relay → Redis → `WorkLeases`, db-scheduler `dueSourceSyncTask`;
  - `SourceSyncProcessor`, `SourceContentExtractorRouter`;
  - Source–Group, `SourcePermissions`/`can()`, `TablePagination`, `ConfirmDialog`, notification provider.
- **AES-GCM** `GoogleDriveCredentialCipher` là thuật toán sẽ dùng chung (Q7).
- **Thư viện có sẵn:** Tika 4.0.0, jsoup 1.23.1, commons-compress. Chưa có msal4j.

### Phụ thuộc chưa merge (16/09/2026)

| Nhánh / issue                                               | SharePoint cần gì                                                                     | Giai đoạn phụ thuộc                     |
| ----------------------------------------------------------- | ------------------------------------------------------------------------------------- | --------------------------------------- |
| MEM-105 `nhuxuanviet/mem-105-source-access-modes` (PR #166) | `SourceAccess` PUBLIC/PRIVATE cho nguồn không phải FILE; luật SQL truy cập dùng chung | 2                                       |
| MEM-106 (PR #173)                                           | Màn Nguồn trên shadcn, bản đồ 14 màn, component registry còn thiếu                    | 4                                       |
| Source pause/resume (trên nhánh MEM-105)                    | Pause/Resume dùng chung                                                               | 2 (dùng contract có sẵn lúc triển khai) |

- **Vì sao giai đoạn 2 phải chờ MEM-105:** trên main, Drive `RESTRICTED` bị loại khỏi Search/Chat. SharePoint lên trước MEM-105 thì tài liệu index xong vẫn không tìm được. Giai đoạn 0–1 không phụ thuộc gì.
- **MEM-88** (ACL Google Drive) không còn là phụ thuộc, vì Auto Sync ngoài phạm vi.
- **Số migration** cấp lúc triển khai. Main hiện tới V62; nhánh MEM-88/MEM-105 còn giữ V54–V56 và sẽ đổi số khi merge.

## 3. Quy tắc parity

- Giữ khái niệm, luồng, cấu hình, nội dung lỗi và nhãn UI của Onyx (dịch vi/en) như trong [tham chiếu](onyx-sharepoint-reference.md).
- Chỉ khác Onyx khi thuộc một trong các loại dưới đây. Mọi khác biệt được liệt kê ở §6.
  - **[MemoryOS]:** contract hoặc stack hiện có bắt buộc.
  - **[MEM-118]:** issue yêu cầu.
  - **[Sửa lỗi On]:** lỗi Onyx ở [tham chiếu §11](onyx-sharepoint-reference.md#11-lỗi-và-điểm-yếu-đã-xác-định).
  - **[Qn]:** quyết định đã chốt ở §7.
- **Đổi tên:**

| Onyx                                                                                                                                        | MemoryOS                                                                                                                                                                              |
| ------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `DocumentSource.SHAREPOINT`, `SharepointConnector`                                                                                          | `SourceType.SHAREPOINT`; `DefaultSharePointSourceService`, `DefaultSharePointSyncService`; provider `RestSharePointProvider`                                                          |
| credential JSON `sp_client_id`, `sp_directory_id`, `sp_client_secret`, `sp_private_key`, `sp_certificate_password`, `authentication_method` | bảng `sharepoint_credentials`: `client_id`, `directory_id`, secret đã mã hóa, private key + certificate đã mã hóa (không lưu mật khẩu), `auth_method` `CLIENT_SECRET` / `CERTIFICATE` |
| `sites`, `excluded_sites`, `excluded_paths`                                                                                                 | `siteUrls` (root), `excludedSites`, `excludedPaths`                                                                                                                                   |
| `include_site_documents`, `include_site_pages`                                                                                              | `includeDocuments`, `includePages`                                                                                                                                                    |
| `authority_host`, `graph_api_host`, `sharepoint_domain_suffix`                                                                              | enum `cloud`; host cố định theo từng cloud                                                                                                                                            |
| `refresh_freq` (mặc định 30 phút)                                                                                                           | `syncIntervalMinutes` (mặc định 30)                                                                                                                                                   |
| `prune_freq` (`DEFAULT_PRUNING_FREQ` = 7 ngày; 0 là tắt)                                                                                    | `pruneIntervalHours` (mặc định 168; 0 là tắt)                                                                                                                                         |
| `POLL_CONNECTOR_OFFSET` = 30 phút                                                                                                           | độ chồng lấn cửa sổ refresh, property triển khai `memoryos.sharepoint.poll-overlap=PT30M`                                                                                             |
| `poll_range_start` / `poll_range_end` của index attempt                                                                                     | cửa sổ refresh gắn với lượt đồng bộ (§5.3)                                                                                                                                            |
| `indexing_start`                                                                                                                            | không có (tiền lệ Drive)                                                                                                                                                              |
| access `private` / `public`                                                                                                                 | `PRIVATE` / `PUBLIC` (MEM-105)                                                                                                                                                        |
| access `sync`, `treat_sharing_link_as_public`                                                                                               | không có: Auto Sync ngoài phạm vi (Q4)                                                                                                                                                |
| `ConnectorFailure`                                                                                                                          | `source_run_errors` với mã `SOURCE_SHAREPOINT_*`                                                                                                                                      |
| hierarchy node SITE/DRIVE/FOLDER                                                                                                            | không có: không có consumer (ADR 0002)                                                                                                                                                |
| `reindex()` theo link, gọi lại provider                                                                                                     | Reindex item trên snapshot đã lưu (contract hiện có)                                                                                                                                  |

## 4. Domain story và thuật ngữ

### Domain story

1. Quản trị viên Microsoft 365 đăng ký app trong Entra, thêm quyền Graph và bấm admin consent.
2. Người có `SOURCES_MANAGE` trong MemoryOS tạo credential từ Application ID, Directory ID và secret hoặc certificate. MemoryOS lấy thử token rồi mới lưu.
3. Họ tạo Source: chọn credential, phạm vi, chế độ truy cập và Group.
4. Worker xác minh site/thư viện/thư mục, rồi kích hoạt Source cùng lượt đồng bộ đầu (lấy toàn bộ).
5. Theo Automatic interval, worker chạy lượt refresh: lấy các file và trang có thời điểm tạo/sửa trong cửa sổ kể từ lượt thành công trước, tải nội dung vào object storage. INGESTION xuất Document.
6. Theo Prune Frequency, worker chạy lượt prune: liệt kê đầy đủ phạm vi (chỉ metadata) và gỡ các item không còn.
7. Thành viên tìm thấy tài liệu theo chế độ truy cập; citation mở trang SharePoint.
8. Khi secret hết hạn hoặc app mất quyền, lượt đồng bộ ghi lỗi có mã, Source hiện cảnh báo và quản trị viên cập nhật credential.

**Tình huống lỗi và phục hồi cần thiết kế:**

- chưa admin consent;
- tenant không có Microsoft 365;
- site ngôn ngữ khác tiếng Anh;
- bị throttling (429/503);
- delta trả 410;
- lượt prune liệt kê không đầy đủ;
- file vượt giới hạn hoặc định dạng không hỗ trợ;
- secret/certificate hết hạn;
- site bị xóa hoặc app bị gỡ quyền trên site (`Sites.Selected`).

### Thuật ngữ

| Thuật ngữ             | Nghĩa trong increment                                                                                    | Quan hệ                                                                         |
| --------------------- | -------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| Entra app             | App registration trong tenant Microsoft của khách hàng                                                   | Được một hoặc nhiều credential tham chiếu                                       |
| Credential SharePoint | Credential thuộc Tenant MemoryOS, giữ Application ID, Directory ID và secret hoặc certificate            | 1 credential → n Source                                                         |
| Microsoft tenant host | `{tenant}.sharepoint.com` và `{tenant}-my.sharepoint.com` theo cloud                                     | Suy từ URL site hoặc `/sites/root`; mọi URL và mọi download phải thuộc host này |
| Site                  | Site collection hoặc subsite (`/sites/`, `/teams/`, `/personal/`)                                        | 1 site → n thư viện                                                             |
| Thư viện              | Document library, tức Graph `drive`                                                                      | Được liệt kê theo delta ở root                                                  |
| Thư mục               | `driveItem` có facet `folder`                                                                            | Root có thể là thư mục                                                          |
| File                  | `driveItem` có facet `file`                                                                              | → Item → Document                                                               |
| Trang site            | `sitePage` trong thư viện Site Pages                                                                     | → Item → Document                                                               |
| Root                  | URL site/thư viện/thư mục đã được xác minh, lưu bằng ID ổn định                                          | Thuộc một Source                                                                |
| Lượt refresh          | Lượt `SOURCE_SYNC` lấy item mới hoặc đã sửa trong một cửa sổ thời gian                                   | Không xóa item                                                                  |
| Cửa sổ refresh        | `[cuối cửa sổ của lượt refresh thành công trước − 30 phút, lúc bắt đầu lượt]`; lượt đầu bắt đầu từ epoch | Thuộc một lượt refresh                                                          |
| Lượt prune            | Lượt `SOURCE_SYNC` liệt kê đầy đủ phạm vi, chỉ metadata, gỡ item không còn                               | Chỉ xóa khi liệt kê hoàn tất                                                    |

Item, Document, Source và Pair giữ nghĩa trong [connector spec](../../../specs/connector.md).

## 5. Thiết kế

### 5.1 Credential

**Dữ liệu** (`sharepoint_credentials`, JDBC):

- `credential_id` (FK `credentials`, kind mới `SHAREPOINT_APP`), `tenant_id`, `owner_actor_id`, `name`;
- `directory_id`, `client_id`: GUID. Directory ID chỉ nhận GUID, không nhận tên miền, để pin đúng tenant [MemoryOS].
- `cloud`: `GLOBAL` (Q9);
- `auth_method`;
- `CLIENT_SECRET`: ciphertext, nonce, key version của secret (≤ 256 ký tự);
- `CERTIFICATE`: PKCS#8 private key đã mã hóa, certificate DER, thumbprint SHA-1, `not_after`;
- `tenant_host` (nullable, ghi khi đã resolve), `status` (`ACTIVE`, `NEEDS_UPDATE`), `credential_revision`, thời điểm tạo/cập nhật.

**Certificate** (giữ như Onyx; Onyx dùng nó chủ yếu cho permission sync, nhưng index cũng chạy được):

- Nhận PFX base64 ≤ 16 KiB cùng mật khẩu. Server giải bằng `KeyStore` PKCS12, yêu cầu:
  - đúng một private key có certificate;
  - RSA ≥ 2048 bit;
  - chưa hết hạn.
- Chỉ lưu key và certificate đã mã hóa. **PFX gốc và mật khẩu không được lưu** [MemoryOS: Onyx lưu cả hai].
- Response trả `thumbprint`, `notAfter` và `authMethod`; không bao giờ trả key hay secret. Chỉ trả `secretConfigured`.

**Mã hóa** (Q7):

- Tách thuật toán AES-GCM đang có của `GoogleDriveCredentialCipher` thành cipher dùng chung. AAD gắn format, tenant, credential, kind, key version và purpose.
- Key cấu hình riêng: `memoryos.sharepoint.credential-encryption-key` và `credential-key-version`.
- Thiếu cấu hình thì chỉ SharePoint fail closed; FILE và Drive không bị ảnh hưởng.

**Token** (provider bundle, msal4j, Q2):

- `ConfidentialClientApplication`, authority `https://login.microsoftonline.com/{directoryId}`, scope `https://graph.microsoft.com/.default`.
- Token chỉ nằm trong bộ nhớ của một session provider, không dùng chung giữa Tenant.
- Increment này không dùng SharePoint REST; REST chỉ cần cho Auto Sync.

**Kiểm tra credential** [MemoryOS, như voice/web]:

- **Luồng lưu:** tạo mới hoặc thay secret/certificate thì kiểm bản nháp với Microsoft **ngoài transaction** trước. Microsoft từ chối thì không lưu gì.
- **`POST …/{credentialId}/test`** kiểm credential đã lưu:
  1. lấy token;
  2. gọi `GET /sites/root?$select=siteCollection,webUrl`.
- **Kết quả phân biệt ba trường hợp:**
  - token hỏng: mã AADSTS đã phân loại;
  - token tốt nhưng không đọc được toàn tenant (403): hợp lệ nếu dùng `Sites.Selected`, nhưng chế độ "Tất cả site" không dùng được;
  - đọc được `/sites/root`: ghi `tenant_host`.
- **Giới hạn:** deadline 15 s, tối đa 2 kiểm tra đồng thời mỗi tiến trình API.

**Vòng đời:**

- tạo; đổi tên; thay secret/certificate (`If-Match` revision); xóa khi không còn Source gắn với credential;
- không có "revoke" vì app-only không có grant để thu hồi, giống Onyx;
- thay secret/certificate tăng `credential_revision` và fence mọi Source dùng credential đó, như Drive.

**Quyền:** giống credential Drive. Global `SOURCES_MANAGE` thấy mọi credential. Người quản lý theo phạm vi chỉ thấy và dùng credential mình tạo.

### 5.2 Source và phạm vi

**Tạo:** `POST /api/sources/sharepoint` trả `202 {sourceId, operation}`. Body:

- `requestId`, `name`, `credentialId`;
- `scopeMode` (`ALL_SITES` | `SPECIFIC`), `siteUrls[]`;
- `excludedSites[]`, `excludedPaths[]`;
- `includeDocuments`, `includePages`;
- `syncIntervalMinutes`, `pruneIntervalHours`;
- `access` (`PUBLIC` | `PRIVATE`), `groupIds`.

**Kiểm tra đồng bộ ngay trong API** (không gọi Microsoft):

- phải bật ít nhất một trong `includeDocuments`/`includePages`; copy của Onyx;
- `SPECIFIC` cần ít nhất 1 URL; `ALL_SITES` gửi `siteUrls: []`;
- URL:
  - bắt đầu `https://`;
  - host là `{tenant}.sharepoint.com` hoặc `{tenant}-my.sharepoint.com` của cloud;
  - path chứa `/sites/`, `/teams/` hoặc `/personal/`;
  - tiền tố share link bị bỏ, như Onyx;
  - mọi URL cùng một tenant host.
- không có root nào là tổ tiên hay con cháu của root khác, theo quy tắc Drive [MemoryOS];
- glob: tối đa 100 mỗi loại, mỗi mẫu ≤ 512 ký tự;
- `syncIntervalMinutes`: 1–2147483647; `pruneIntervalHours`: 0 (tắt) hoặc 1–8760;
- quota theo `SharePointSelectionPolicy`: mặc định 1.000 root và 3 MiB request, như Drive.

**Xác minh bất đồng bộ** (workload `SHAREPOINT_SELECTION_VALIDATION`; gọi provider ngoài transaction; checkpoint và retry như Drive):

- **Site:** `GET /sites/{host}:/{server-relative-path}`, lưu `siteId`.
- **Thư viện:** liệt kê `GET /sites/{siteId}/drives`. Đoạn URL sau site được so với path của `drive.webUrl`, là URL nội bộ của thư viện, không so tên hiển thị [Sửa lỗi O2, Q3; spike S0.6].
  - Site `/personal/`: thư viện chính tên `OneDrive` và path là `/Documents`, không phải `/Shared Documents`; drive `PersonalCacheLibrary` của site đó bị bỏ [spike S0.8].
- **Thư mục:** `GET /drives/{driveId}/root:/{path}`, lưu `itemId`.
- **`ALL_SITES`:** thử trang đầu `/sites/getAllSites`. Bị 403 thì trả `SOURCE_SHAREPOINT_ALL_SITES_FORBIDDEN`; hướng dẫn cấp `Sites.Read.All` hoặc chuyển sang Site cụ thể.
- **Activation transaction:** kiểm lại Tenant, credential revision, quyền và claim, rồi tạo Source, root và lượt đồng bộ đầu một cách nguyên tử. Mọi thất bại để lại receipt truy vấn được, không để lại Source tạo dở.

**Sửa phạm vi:**

- Mode chọn một lần lúc tạo, theo tiền lệ Drive.
- Root, loại trừ và cờ include sửa qua `PUT …/sharepoint/scope` với `If-Match` scope revision. Request đi qua cùng luồng xác minh bất đồng bộ và fence công việc cũ.
- Sau khi đổi phạm vi thành công:
  - lượt refresh kế tiếp bắt đầu lại từ epoch cho các root mới thêm, để lấy đủ nội dung cũ của chúng;
  - item nằm ngoài phạm vi mới bị gỡ ngay trong activation transaction, như scope shrink của Drive.

### 5.3 Đồng bộ thư viện (Q1 giữ mô hình Onyx; Q11, Q12 sửa theo spike)

**Tập drive của một lượt:**

- `SPECIFIC`:
  - root là site → mọi document library của site, như Onyx;
  - root là thư viện hoặc thư mục → drive chứa root đó.
- `ALL_SITES`:
  - mỗi lượt đọc lại `getAllSites` có phân trang, nên site mới tự vào phạm vi;
  - bỏ site OneDrive theo cờ `isPersonalSite` của `getAllSites`, thay cho so host `{tenant}-my` [Sửa lỗi O8; spike S0.2];
  - `getAllSites` có thể trả site không tên (`name: null`, ví dụ site `/search`), nên tên rỗng phải xử lý được [spike S0.2];
  - áp `excludedSites`, rồi lấy drive của từng site.

**Lượt refresh** (theo Automatic interval, và khi bấm Synchronize now):

- **Cửa sổ** theo `connector_document_extraction` của Onyx:
  - `start` = cuối cửa sổ của lượt refresh **thành công** gần nhất trừ độ chồng lấn 30 phút;
  - lượt đầu, hoặc root mới thêm, bắt đầu từ epoch;
  - `end` = thời điểm bắt đầu lượt;
  - lượt bị lỗi không làm lùi hay tiến `start`; lượt sau thử lại từ cùng mốc;
  - cửa sổ được lưu cùng lượt đồng bộ.
- **Root là site hoặc thư viện:**
  - gọi `GET /drives/{id}/root/delta?$top=200&$select=…&token=<start ISO-8601>`; lượt bắt đầu từ epoch thì không gửi `token`. Graph chỉ nhận timestamp token trên OneDrive for Business và SharePoint, đúng trường hợp này;
  - theo `@odata.nextLink`; **không lưu `@odata.deltaLink`**, như Onyx;
  - **không lọc lại theo cửa sổ** [Q12]: delta là change-log, không phải bộ lọc theo `lastModifiedDateTime`; item bị di chuyển được trả về tuy `lastModifiedDateTime` không đổi (spike S0.3). Cửa sổ chỉ dùng để dựng `token`;
  - bỏ thư mục; **bản ghi `deleted` sinh `REMOVE_ITEM`** theo `provider_file_id` [Q11]: tombstone chỉ có `id` và `parentReference`, không có `name`; xóa một thư mục thì Graph trả thêm tombstone cho từng file con (spike S0.3);
  - bỏ trùng theo item id trong một drive của một lượt;
  - HTTP 410: bắt đầu lại drive đó bằng URL trong header `Location` (quét toàn bộ) và vẫn lọc theo cửa sổ.
- **Root là thư mục:** duyệt BFS `GET /drives/{d}/items/{folderId}/children?$top=200` toàn bộ thư mục mỗi lượt, **vẫn lọc theo cửa sổ** vì `children` không phải change-log [Q12]. Hệ quả: file được chuyển vào thư mục root chỉ được lấy ở lượt prune hoặc khi có thay đổi khác (§9).
- **`excludedPaths`:** so với đường dẫn tương đối và tên file, như Onyx. Delta không trả `parentReference.path`, nên đường dẫn dựng từ `parentReference.id` → thư mục đã thấy; thư mục chưa biết thì đọc metadata thư mục đó (có giới hạn số lời gọi mỗi bước).
- **Lượt refresh gỡ item ngay khi delta trả tombstone** [Q11]. Những gì delta không thấy — app mất quyền đọc site, item bị chuyển ra khỏi thư mục root, item rơi khỏi phạm vi sau 410 — vẫn chờ lượt prune.

**Lượt prune** (theo Prune Frequency; `0` là tắt, như Onyx) — lưới an toàn cho những gì lượt refresh không thấy [Q11]:

- Liệt kê đầy đủ phạm vi hiện hành, **chỉ metadata, không tải nội dung**:
  - thư viện: delta không token;
  - thư mục root: BFS;
  - trang site: danh sách trang.
- Liệt kê hoàn tất cho toàn phạm vi thì mới gỡ các item không được thấy: tạo `REMOVE_ITEM`, bộ đếm `removed`.
- Liệt kê không hoàn tất thì **không gỡ gì**: ví dụ một site trả 403/404, hết ngân sách, hoặc đang bị throttling. Lượt được ghi `completed with errors` kèm mã lỗi, lượt prune sau thử lại [MemoryOS: Onyx bỏ qua site lỗi trong slim listing, nên có thể xóa nhầm tài liệu của site đó].
- Item bị loại bởi `excludedSites`/`excludedPaths` mới cũng được gỡ ở lượt này.
- Refresh và prune của cùng một Source không chạy song song: cùng một claim `SOURCE_SYNC` theo Source. Nếu cả hai đến hạn, prune chạy trước.

**Phiên bản nội dung:**

- Delta trên SharePoint không trả `cTag`, nên phiên bản so sánh là `file.hashes.quickXorHash` + `size`.
- Không có hash thì dùng `eTag` + `lastModifiedDateTime`, và giữ quy tắc hiện có "bytes và tên giống thì không tạo version mới" (spike S0.3).
- Đổi tên làm đổi tên file, nên tạo version mới theo quy tắc hiện có.

**Lọc trước khi tải:**

- chỉ file có định dạng mà router extraction hiện có hỗ trợ;
- kích thước ≤ 100 MiB, giới hạn FILE/Drive hiện hành [MemoryOS; Onyx 20 MB].
- File khác được ghi `skipped` với mã `SOURCE_SHAREPOINT_UNSUPPORTED_FORMAT` hoặc `SOURCE_SHAREPOINT_TOO_LARGE`, không coi là thành công rỗng.

**Tải nội dung:**

- `GET /drives/{d}/items/{i}?$select=id,name,size,file,eTag,@microsoft.graph.downloadUrl`.
- Chỉ tải `downloadUrl` khi host đúng tenant host; không theo redirect; stream có giới hạn byte và thời gian.
- Không lấy được `downloadUrl` thì gọi `/content`: chấp nhận đúng một redirect, và host đích qua cùng phép kiểm tra.
- `downloadUrl` chứa `tempauth`: không log, không lưu, không đưa vào lỗi [Onyx `scrub_url_credentials`].
- Ghi qua `ObjectWriteService.stage` → adopt → INDEX, giống hệt Drive.

**Throttling và retry** [Sửa lỗi O9, O14]:

- Mọi lời gọi Graph đi qua một HTTP adapter chung với các giới hạn: connect 3 s, mỗi request 30 s, số request và thời gian có giới hạn cho mỗi bước.
- 429/503: đọc `Retry-After`, ghi `next_dispatch_at = now + Retry-After` (có trần), trả claim thay vì sleep trong worker.
- 5xx/lỗi mạng: backoff 1 s rồi 30 s như sync Drive; thất bại vượt ngân sách thì ghi lỗi của lượt.
- Header `User-Agent: ISV|MemoryOS|SharePointConnector/<version>` theo hướng dẫn chống throttling của Microsoft [MemoryOS].

**Lịch sử lượt chạy:**

- Dùng lại `source_sync_attempts` và các counter hiện có. Thêm loại lượt `REFRESH` / `PRUNE` cùng cửa sổ refresh, để UI phân biệt và để tính `start`.
- `removed` xuất hiện ở cả lượt refresh (theo tombstone) và lượt prune (theo đối chiếu đầy đủ). `No changes` của lượt refresh nghĩa là không có thay đổi nào trong cửa sổ, không có nghĩa phạm vi đã được đối chiếu đầy đủ.

### 5.4 Trang site

- Chỉ chạy khi `includePages` bật.
- **Lượt refresh:**
  1. liệt kê metadata `GET /sites/{siteId}/pages/microsoft.graph.sitePage?$select=id,name,title,webUrl,eTag,lastModifiedDateTime` có phân trang;
  2. với trang có `lastModifiedDateTime` trong cửa sổ, gọi `GET /sites/{siteId}/pages/{id}/microsoft.graph.sitePage?$expand=canvasLayout`.
- **Lượt prune:** gỡ trang không còn trong danh sách đầy đủ.
- **So với Onyx:** Onyx expand canvas cho toàn danh sách rồi mới dùng fallback từng trang khi gặp lỗi 400. MemoryOS luôn lấy canvas từng trang, nên trang hỏng chỉ ảnh hưởng chính nó [MemoryOS].
- **Snapshot JSON:** title, description, web part text (HTML), `searchablePlainTexts` của standard web part, `webUrl`. Lưu vào object storage với input format mới `SHAREPOINT_PAGE`.
- **Reader** `SharePointPageSourceContentExtractor` (provider bundle): dùng jsoup có sẵn để giữ heading, paragraph, list và table thành canonical blocks [Sửa lỗi O13]. Metadata site dùng đúng tên site [Sửa lỗi O7].
- Canvas lỗi được ghi `SOURCE_SHAREPOINT_PAGE_UNREADABLE`, chỉ ảnh hưởng trang đó.

### 5.5 Định danh, citation, extraction

- **Item identity:**
  - file: `provider_file_id = drive:{driveId}:{itemId}`;
  - trang: `page:{siteId}:{pageId}` [Sửa lỗi O6].
  - Hai Source cùng trỏ vào một file vẫn có Item và Document riêng, như Drive.
- **Citation:** `source_url = webUrl`; Office file dùng dạng `Doc.aspx`. Search/Chat hiện icon SharePoint và deep link, theo cùng renderer của MEM-87 [MEM-118].
- **Extraction:** binary (PDF/DOCX/PPTX/XLSX/CSV/TXT/MD) đi đường router hiện có; trang đi reader ở §5.4. Không có OCR mới, không xử lý ảnh độc lập, không extraction riêng cho SharePoint.

### 5.6 Lỗi và bảo mật provider

**Mã lỗi an toàn**, với stage theo `SourceRunErrorStage`:

| Mã                                                                                    | Khi nào                                                | Hành động gợi ý                                         |
| ------------------------------------------------------------------------------------- | ------------------------------------------------------ | ------------------------------------------------------- |
| `SOURCE_SHAREPOINT_AUTH_INVALID_SECRET` / `_SECRET_EXPIRED` / `_CERTIFICATE_REJECTED` | AADSTS7000215 / 7000222 / 700027 và các mã tương đương | Cập nhật credential                                     |
| `SOURCE_SHAREPOINT_CONSENT_MISSING`                                                   | AADSTS65001, hoặc 403 do app chưa có role              | Admin consent                                           |
| `SOURCE_SHAREPOINT_PERMISSION_MISSING`                                                | 403 trên site/drive                                    | Cấp `Sites.Read.All` hoặc cấp site qua `Sites.Selected` |
| `SOURCE_SHAREPOINT_ALL_SITES_FORBIDDEN`                                               | `getAllSites` bị 403                                   | Cấp `Sites.Read.All` hoặc chọn Site cụ thể              |
| `SOURCE_SHAREPOINT_SITE_NOT_FOUND` / `_LIBRARY_NOT_FOUND` / `_FOLDER_NOT_FOUND`       | Xác minh hoặc đồng bộ gặp 404/410 trên tài nguyên      | Sửa phạm vi                                             |
| `SOURCE_SHAREPOINT_HOST_MISMATCH`                                                     | URL hoặc download ngoài tenant host                    | Sửa URL                                                 |
| `SOURCE_SHAREPOINT_THROTTLED`                                                         | Hết ngân sách retry vì 429/503                         | Tự thử lại ở lượt sau                                   |
| `SOURCE_SHAREPOINT_PRUNE_INCOMPLETE`                                                  | Lượt prune không liệt kê được toàn phạm vi             | Xem lỗi đi kèm; lượt sau thử lại                        |
| `SOURCE_SHAREPOINT_UNSUPPORTED_FORMAT` / `_TOO_LARGE` / `_PAGE_UNREADABLE`            | Theo từng file hoặc trang                              | Không cần làm gì                                        |

- Mã AADSTS là mã công khai của Microsoft, được lưu và hiện trong "Chi tiết kỹ thuật". Response và log không chứa secret, token, `downloadUrl` hay nội dung PFX.
- **Endpoint provider cố định theo cloud.** URL người dùng nhập chỉ dùng để tách host và path, rồi dựng lời gọi Graph `sites/{host}:/{path}`. MemoryOS không fetch URL tùy ý.
- Ghi header `request-id`/`client-request-id` của Graph vào log lỗi để hỗ trợ; đây là ID công khai, không phải bí mật.

### 5.7 Truy cập tài liệu

- SharePoint có `PUBLIC`, `PRIVATE` và **`SYNC` (Auto Sync) như Google Drive** [Q13, thay Q4]. Mặc định `SYNC` giống Drive; `PUBLIC` cần global `SOURCES_MANAGE`; scoped manager được chọn `PRIVATE` hoặc `SYNC`.
- `JdbcSourceDocumentRepository` thêm nhánh `SHAREPOINT` vào đúng luật SQL dùng chung.
- Kiểm tra "Auto Sync chỉ cho Google Drive" trong `SourceAccessPolicy` và `JdbcSourceRepository.updateAccess` đổi thành "provider có đồng bộ quyền" (Drive, SharePoint), không thêm một loại hard-code thứ hai.

#### 5.7.1 Auto Sync (Q13)

Làm theo Onyx EE (`onyx@711a047`: `ee/onyx/external_permissions/sharepoint/permission_utils.py`, `doc_sync.py`, `group_sync.py`, `sync_params.py`; `onyx/connectors/sharepoint/connector.py`). Phía MemoryOS dùng lại hạ tầng MEM-88/MEM-105: `SourceDocumentAccessResolver`, `READ_SCOPE`, `DocumentAccess`, trường `access_public`/`access_control_list` và refresh ACCESS tại chỗ của OpenSearch, `enqueueDocumentAccess`.

**Credential (như Onyx):**

- Quyền đọc qua **SharePoint REST**, và REST chỉ nhận token app-only từ **certificate**. Chọn Auto Sync với credential client secret bị từ chối, với thông báo như Onyx ("Recreate the credential with Certificate Authentication, or turn permission sync off").
- Quyền Entra cần: SharePoint `Sites.FullControl.All` (hoặc full control từng site với `Sites.Selected`) và Graph `GroupMember.Read.All`.
- Khi kiểm tra Source Auto Sync, thử đọc role assignment trên tối đa vài site; lỗi thì báo nhưng không chặn, như Onyx.

**Quyền của một tài liệu (doc sync):**

- File: lấy list item của file rồi đọc `roleassignments` với `$expand=Member,RoleDefinitionBindings`, 100 mục mỗi trang. Trang site: `GetFileByServerRelativeUrl(path)/ListItemAllFields`, giữ nguyên percent-encoding của path như Onyx.
  - Onyx tìm list theo **tiêu đề** thư viện (lỗi O3 với site không phải tiếng Anh); MemoryOS tìm theo `sharepointIds.listId`, cùng lý do như Q3.
- Bỏ assignment chỉ có Limited Access (`RoleTypeKind` 1 hoặc 9).
- Principal:
  - **User** (loại 1): email là `UserPrincipalName` sau khi bỏ `.onmicrosoft` (`normalize_email` của Onyx), viết thường.
  - **Entra group** (loại 4) và **SharePoint group** (loại 8): ghi **id group**, không ghi thành viên. Entra: `{displayName}_{groupId}`; SharePoint: `{siteUrl}::{title}`; viết thường. Mở rộng các group lồng bên trong và ghi luôn id của chúng (`_resolve_document_groups`), có cache trong một lượt.
  - Login name chứa `c:0-.f|rolemanager|spo-grid-all-users/` (Everyone except external users) hoặc `c:0(.s|true` (Everyone): tài liệu **public**.
- Tùy chọn của Source `treatSharingLinkAsPublic` (mặc định tắt, như Onyx): link chia sẻ `anonymous`/`organization` làm tài liệu public.
- Kết quả mỗi tài liệu là `{public, userEmails, groupIds}`, lưu thành snapshot có provenance như Drive (V75/V76).

**Thành viên group (group sync):**

- Mỗi site trong phạm vi: đọc role assignment **cấp web**, lấy các group (bỏ Limited Access), mở rộng đệ quy: SharePoint group qua REST `sitegroups`, Entra group qua Graph; group Entra không còn (404) thì bỏ qua. Kết quả: mỗi group → **email thành viên trực tiếp**. Group public không được lưu.
- Tùy chọn triển khai `exhaustiveAdEnumeration` (mặc định tắt, như Onyx): duyệt thêm mọi group Entra trong tenant, dừng ở 100.000 group.

**Lịch (như `sync_params.py`):**

- Doc sync mỗi **30 phút**: đọc lại quyền của mọi tài liệu Source đang giữ; tài liệu không còn trả về thì mất quyền. Lượt đầu cũng gắn quyền khi index (`initial_index_should_sync`).
- Group sync mỗi **5 phút**.
- Hai lịch này tách khỏi refresh/prune: thêm loại lượt `PERMISSIONS` và `GROUPS` với `next_*_at` riêng, không đổi hành vi refresh/prune.

**Đối chiếu với người đọc (MemoryOS):**

- Grant của tài liệu thành token: `ms_user:<email>`, `ms_group:<sourceId>:<groupId>`, hoặc `everyone` khi public. Group gắn theo Source vì Onyx đồng bộ group theo từng cc-pair (`group_sync_is_cc_pair_agnostic=False`).
- Token của người đọc (`READER_TOKENS`) thêm `ms_user:` từ email đăng nhập đã xác minh (ADR 0011), và `ms_group:` của mọi group SharePoint có email đó trong danh sách thành viên. Như Onyx, đổi thành viên group không phải index lại tài liệu; đổi quyền tài liệu thì refresh ACCESS tại chỗ qua sự kiện `SourceAclChanged` (tổng quát hóa `GoogleDriveAclChanged`).
- Giữ nguyên `google_user:`/`google_domain:` của Drive để không phải sửa index đã có.

**Lỗi:** không đọc được quyền (thiếu quyền Entra, 403) thì ghi `SOURCE_SHAREPOINT_AUTHORIZATION` lên snapshot; tài liệu chưa có snapshot thành công thì không ai đọc được, nhưng việc lấy nội dung không bị chặn (như Drive).

**ADR:** mở rộng ADR 0011 cho `ms_user:`/`ms_group:` và việc đối chiếu email sau `normalize_email`, ghi khi đã bắt đầu triển khai.

### 5.8 API

Theo tiền lệ Drive; `operationId`, tag "Sources"/"Credentials". Mọi lệnh ghi dùng mutation guard hiện có.

| Endpoint                                                     | Quyền                                                 | Ghi chú                                                                                                                                                              |
| ------------------------------------------------------------ | ----------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /api/credentials/sharepoint`                            | global `SOURCES_MANAGE`, hoặc chủ sở hữu theo phạm vi | Metadata, `secretConfigured`, `thumbprint`, `notAfter`, `status`, `credentialRevision`, số Source gắn                                                                |
| `POST /api/credentials/sharepoint`                           | như trên                                              | Kiểm với Microsoft trước khi lưu; body không vào log                                                                                                                 |
| `PUT /api/credentials/sharepoint/{credentialId}`             | như trên + `If-Match`                                 | Đổi tên; secret/certificate dạng KEEP/REPLACE                                                                                                                        |
| `POST /api/credentials/sharepoint/{credentialId}/test`       | như trên                                              | Deadline 15 s                                                                                                                                                        |
| `DELETE /api/credentials/sharepoint/{credentialId}`          | như trên + `If-Match`                                 | Chỉ khi không còn Source gắn                                                                                                                                         |
| `POST /api/sources/sharepoint`                               | như tạo Source Drive                                  | `202 {sourceId, operation}`                                                                                                                                          |
| `GET /api/sources/sharepoint/selection-policy`               | global `SOURCES_MANAGE`                               | Quota                                                                                                                                                                |
| `GET /api/sources/sharepoint/selection-requests/{requestId}` | người khởi tạo                                        | Khôi phục receipt bị mất                                                                                                                                             |
| `GET /api/sources/{sourceId}/sharepoint`                     | `SOURCES_READ`                                        | Mode, cờ, số root, credential, interval, prune interval, lượt refresh/prune gần nhất, schedule/scope revision, lỗi hiện tại, `pendingSelectionOperation`; `no-store` |
| `GET /api/sources/{sourceId}/sharepoint/roots`               | `SOURCES_READ`                                        | Cursor, size mặc định 25, tối đa 100                                                                                                                                 |
| `PUT /api/sources/{sourceId}/sharepoint/scope`               | global `SOURCES_MANAGE` + `If-Match`                  | `202`, xác minh bất đồng bộ                                                                                                                                          |
| `PUT /api/sources/{sourceId}/sharepoint/schedule`            | quyền vận hành theo phạm vi + `If-Match`              | `{syncIntervalMinutes, pruneIntervalHours}`                                                                                                                          |
| `POST /api/sources/{sourceId}/sharepoint/sync`               | quyền vận hành theo phạm vi                           | `202` `SYNC_SOURCE`, lượt refresh                                                                                                                                    |

- **Endpoint chung dùng lại:** summary, items, runs (thêm loại lượt), index-attempts, access (MEM-105), groups, delete, source-operations, pause/resume (theo contract hiện hành lúc triển khai).
- **Contract:** OpenAPI và client hey-api được sinh lại trong cùng thay đổi.
- **Lỗi:** RFC 9457 với mã `SOURCE_SHAREPOINT_*`, cộng các mã Source hiện có.

### 5.9 Persistence

Tên dưới đây là dự kiến; SQL nằm trong repository `JdbcSharePoint*` của `core/connector/persistence`.

| Bảng / cột                                    | Nội dung                                                                                                                                                                               |
| --------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `credentials` / `connectors` (CHECK)          | kind `SHAREPOINT_APP`, connector type `SHAREPOINT`                                                                                                                                     |
| `sharepoint_credentials`                      | §5.1                                                                                                                                                                                   |
| `sharepoint_sources`                          | `pair_id`, `scope_mode`, `include_documents`, `include_pages`, `sync_interval_minutes`, `prune_interval_hours`, `schedule_revision`, `scope_revision`, `next_sync_at`, `next_prune_at` |
| `sharepoint_roots`                            | `source_id`, `kind` SITE/LIBRARY/FOLDER, `url`, `site_id`, `drive_id`, `item_id`, `display_name`, `position`, `full_refresh_pending` (root mới thêm cần refresh từ epoch)              |
| `sharepoint_exclusions`                       | `source_id`, `kind` SITE/PATH, `pattern`, `position`                                                                                                                                   |
| `sharepoint_selection_operations` (+ entries) | Intent, receipt và checkpoint xác minh, như Drive V30                                                                                                                                  |
| `sharepoint_sync_runs`                        | `source_sync_attempt_id`, `kind` REFRESH/PRUNE, `window_start`, `window_end`, tiến độ drive/site trong lượt (checkpoint)                                                               |
| `sharepoint_items`                            | `source_id`, `provider_file_id`, `kind` FILE/PAGE, `content_version`, `e_tag`, `last_seen_prune_run`                                                                                   |

- `provider_file_id` là drive item id; lượt refresh khớp tombstone của delta theo cột này [Q11], nên cần unique index theo `(tenant_id, source_id, provider_file_id)`.

- Mọi bảng có `tenant_id`, xóa cascade theo Source.
- Chỉ thêm index khi có truy vấn dùng tới.
- Không sửa migration đã áp dụng.
- Loại lượt được đưa vào projection run history chung dưới dạng trường nullable; Drive và FILE để null.

### 5.10 Worker và ingestion

- **`SourceType.SHAREPOINT`** đi vào mọi switch exhaustive: summary, permissions, access resolver, provenance, cleanup, due scan. Due scan chọn prune nếu `next_prune_at` đến hạn, nếu không thì refresh.
- **Workload mới** `SHAREPOINT_SELECTION_VALIDATION`: stream `…:v1`, routing và cấu hình exhaustive theo [ingestion spec](../../../specs/ingestion.md).
- **`SOURCE_SYNC`** giữ nguyên. `SourceSyncProcessor` phân nhánh theo `SourceType` sang `DefaultSharePointSyncService`: tối đa 16 bước mỗi lần giao, có deadline, claim fence bằng scope và credential revision; checkpoint theo drive/site trong `sharepoint_sync_runs`.
- **`SourceInputDescriptor`** thêm format `SHAREPOINT_PAGE`; router có reader tương ứng.
- **Provider bundle** `connector/src/main/java/io/memoryos/provider/sharepoint/`:
  - `RestSharePointProvider`, `MsalSharePointTokenSource`;
  - `SharePointPageSourceContentExtractor`;
  - `SharePointProviderProperties`, auto-configuration.
  - Port `SharePointProvider` nằm trong `core/connector`. `ProviderDependencyRulesTest` giữ hướng phụ thuộc.

### 5.11 Giao diện

Chi tiết và lý do nằm ở [ui-references.md](ui-references.md).

- **Catalog:** thêm SharePoint vào `source-provider-catalog.ts`, dùng provider mark có quyền sử dụng.
- **Route** `/admin/sources/new/sharepoint`, setup rail Credential → Phạm vi → Truy cập → Xem lại.
  - Bước "Connector" của Onyx được tách thành Phạm vi và Truy cập, theo MEM-106.
  - Bố cục rộng; rail bước ở bề mặt riêng và sticky trên desktop, chuyển thành hàng ngang trên mobile.
  - Phạm vi và visibility dùng radio-card có mô tả cùng hệ quả quyền tại lựa chọn.
  - Bước "Advanced" của Onyx còn Refresh Frequency và Prune Frequency (không có Indexing Start Date), đặt ở bước Xem lại.
- **Credential:** danh sách credential theo MEM-106 #4a; `Dialog` tạo/sửa chia hướng dẫn Entra và identifiers/authentication; kiểm tra kết nối trước khi lưu.
- **Xem lại:** ba `Card` Credential, Content, Name and access, mỗi card có hành động sửa quay về đúng bước.
- **Chi tiết Source:** các capability Credential, Saved scope và Schedule nằm trong `Card`; cảnh báo có hướng xử lý nằm trước vùng bị ảnh hưởng.
  - Đồng bộ: interval, prune interval, lượt refresh/prune gần nhất;
  - Phạm vi: root, sửa inline theo pattern panel Drive;
  - Credential: phương thức, hạn certificate;
  - `Alert` theo mã lỗi §5.6.
- **Chung:**
  - Search/Chat có icon và deep link SharePoint;
  - vi/en qua `appText`, `pnpm check:i18n`;
  - control ẩn theo `can(resource, key)`.

### 5.12 Quan sát

- **Metric:** `memoryos.connector.provider.request` với tag `provider=sharepoint`, `operation` (`token|sites|drives|delta|children|download|pages`), `outcome` (`success|throttled|client_error|server_error|timeout`). Không gắn tenant, Source hay URL làm tag.
- **Log:** dùng `event` key theo [observability](../../../guidelines/observability.md). Không log token, secret, `downloadUrl`, PFX, tên file hay nội dung.
- **Record** chứa bí mật có `toString` đã che.

## 6. Khác biệt so với Onyx

| #   | Onyx                                                                   | MemoryOS                                                                                        | Loại                      |
| --- | ---------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- | ------------------------- |
| 1   | Credential JSON không có ràng buộc Tenant; lưu cả PFX lẫn mật khẩu     | AES-GCM gắn Tenant/credential; PFX giải một lần, chỉ lưu key và certificate, không lưu mật khẩu | MemoryOS                  |
| 2   | Không kiểm credential khi tạo; lỗi chỉ lộ khi index                    | Lấy token trước khi lưu; có nút Test                                                            | MemoryOS                  |
| 3   | Host authority/Graph/suffix nhập tự do; còn Germany                    | Enum `cloud`, host cố định; chỉ `GLOBAL`                                                        | MemoryOS, Sửa lỗi O15, Q9 |
| 4   | Khớp thư viện theo tên + map 3 ngôn ngữ                                | Khớp theo path của `drive.webUrl`                                                               | Sửa lỗi O2, Q3            |
| 5   | Slim listing bỏ qua site lỗi, nên có thể xóa nhầm tài liệu của site đó | Prune chỉ gỡ khi liệt kê toàn phạm vi hoàn tất                                                  | MemoryOS                  |
| 6   | Document id = driveItem id                                             | `drive:{driveId}:{itemId}`, `page:{siteId}:{pageId}`                                            | Sửa lỗi O6                |
| 7   | Expand canvas mọi trang; regex bỏ HTML; metadata site sai              | Metadata cho mọi trang, canvas cho trang trong cửa sổ; reader jsoup giữ cấu trúc; đúng tên site | MemoryOS, Sửa lỗi O7, O13 |
| 8   | Ngưỡng 20 MB                                                           | 100 MiB như FILE/Drive; định dạng theo router                                                   | MemoryOS                  |
| 9   | Retry bằng sleep trong luồng; một số lời gọi SDK không retry           | `Retry-After` → `next_dispatch_at`; mọi lời gọi qua một adapter có ngân sách                    | MemoryOS, Sửa lỗi O9, O14 |
| 10  | Lọc OneDrive bằng `-my.sharepoint`                                     | So với host tenant theo cloud                                                                   | Sửa lỗi O8                |
| 11  | Có Indexing Start Date                                                 | Không có                                                                                        | MemoryOS (tiền lệ Drive)  |
| 12  | Hierarchy node                                                         | Không có                                                                                        | MemoryOS (ADR 0002)       |
| 13  | Reindex theo link, gọi lại provider                                    | Reindex trên snapshot đã lưu                                                                    | MemoryOS                  |
| 14  | Access cần gói Business; có Auto Sync                                  | Public/Private/Auto Sync như Onyx; tìm list theo `listId` thay vì tiêu đề                      | MemoryOS (MEM-105), Q13   |
| 15  | Mô tả phương thức xác thực không hiện                                  | Hiện trong card `RadioGroup`                                                                    | Sửa lỗi O12               |
| 16  | Form sinh động theo cấu hình                                           | Các bước shadcn theo MEM-106 và Mobbin                                                          | MemoryOS                  |
| 17  | Không có User-Agent riêng                                              | `ISV\|MemoryOS\|SharePointConnector/<version>`                                                  | MemoryOS                  |
| 18  | Toast hiện lỗi thô                                                     | Mã lỗi đã dịch; AADSTS nằm trong chi tiết kỹ thuật                                              | MemoryOS                  |
| 19  | Docs và code mâu thuẫn về quyền client secret                          | Bảng quyền đúng theo code trong hướng dẫn và connector spec                                     | Sửa lỗi O1                |
| 20  | Cửa sổ poll gắn với index attempt                                      | Cửa sổ gắn với lượt `SOURCE_SYNC`, kèm loại REFRESH/PRUNE trong run history                     | MemoryOS                  |

Giữ nguyên hành vi Onyx (Q1), dù đã xác định là điểm yếu: timestamp token không lưu `deltaLink` (O4); root thư mục duyệt BFS `children` (O5); xóa và chuyển ra khỏi phạm vi chỉ phản ánh ở lượt prune.

## 7. Quyết định

Người dùng chốt Q1–Q10 ngày 16/09/2026, và chốt Q11–Q12 cùng ngày sau khi Giai đoạn 0 đo trên tenant thật. Các câu ghi "theo khuyến nghị" được đề xuất và người dùng không phản đối.

| #   | Quyết định                                                                                                                                                                  | Hệ quả                                                                                                                                                                                                                    |
| --- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Q1  | **Giữ mô hình theo dõi thay đổi của Onyx**: refresh theo timestamp token (chồng lấn 30 phút), không lưu `deltaLink`; prune theo Prune Frequency (mặc định 7 ngày, 0 là tắt) | Có thêm cấu hình prune interval (khác tiền lệ Drive). Không có bảng cursor. Việc xóa được xử lý theo Q11, không chờ prune                                                                                                 |
| Q2  | Gọi Graph bằng HTTP adapter JDK + msal4j (theo khuyến nghị)                                                                                                                 | Cùng mẫu `RestGoogleDriveProvider`; tự map vài resource JSON                                                                                                                                                              |
| Q3  | **Khớp thư viện theo path của URL**                                                                                                                                         | Hỗ trợ site tiếng Việt; xác nhận bằng spike S0.6                                                                                                                                                                          |
| Q4  | ~~**Chưa làm Auto Sync**~~ — **thay bằng Q13**                                                                                                                                                      | Không có REST SharePoint, role assignment, mở rộng group, `SYNC`, link chia sẻ công khai. MEM-88 không còn là phụ thuộc                                                                                                   |
| Q5  | Bỏ: thuộc Auto Sync                                                                                                                                                         | —                                                                                                                                                                                                                         |
| Q6  | Automatic interval mặc định 30 phút (theo khuyến nghị, như Onyx)                                                                                                            | —                                                                                                                                                                                                                         |
| Q7  | Cipher AES-GCM dùng chung, key SharePoint riêng (theo khuyến nghị)                                                                                                          | Refactor nhỏ; test giải mã dữ liệu Drive cũ                                                                                                                                                                               |
| Q8  | **Tạo [MEM-126](https://linear.app/memory-os/issue/MEM-126)** dưới MEM-118, nhánh `anhnd05122004/mem-126-sharepoint-connector-ket-noi-sharepoint-online-theo-logic`         | Đã làm                                                                                                                                                                                                                    |
| Q9  | Chỉ cloud `GLOBAL` (theo khuyến nghị)                                                                                                                                       | Enum đã chừa chỗ cho cloud khác                                                                                                                                                                                           |
| Q10 | Nhận URL `/personal/`, không đưa OneDrive vào `ALL_SITES` (theo khuyến nghị)                                                                                                | Đã kiểm ở [spike S0.8](plan.md#s08--url-personal-q10): thư viện chính tên `OneDrive`, path `/Documents`; lọc `ALL_SITES` theo cờ `isPersonalSite`                                                                         |
| Q11 | **Lượt refresh gỡ tài liệu ngay theo tombstone của delta**; prune trở thành lưới an toàn                                                                                    | Phải lưu `provider_file_id` để khớp tombstone, vì tombstone không có `name`. Tài liệu đã xóa chỉ còn tìm được tối đa một chu kỳ refresh thay vì 7 ngày. Bằng chứng: [spike S0.3](plan.md#s03--delta-theo-timestamp-token) |
| Q12 | **Bỏ bộ lọc cửa sổ phía client ở nhánh delta**; nhánh BFS `children` vẫn lọc                                                                                                | Sửa lỗi O4/O5: item được di chuyển vào phạm vi được index ngay thay vì chờ tới khi có người sửa nội dung. Cửa sổ chỉ còn dùng để dựng token                                                                               |
| Q13 | **Làm Auto Sync theo quyền SharePoint như Google Drive** (người dùng chốt 19/09/2026, thay Q4) | §5.7.1, **làm theo Onyx**: REST `roleassignments` với credential certificate; doc sync 30 phút và group sync 5 phút; group gắn theo id, thành viên đồng bộ riêng. Mặc định `SYNC` như Drive |

## 8. Ngoài phạm vi

- OneDrive toàn tenant, Teams, SharePoint list (không phải thư viện), Outlook: các nguồn riêng trong MEM-118.
- OAuth delegated theo người dùng; tự động cấp `Sites.Selected` từ MemoryOS (quản trị viên tự cấp qua Graph).
- Trình duyệt cây site/thư mục; ghi ngược lên SharePoint; webhook/subscription Graph.
- Indexing Start Date, hierarchy node, nút prune thủ công.
- Sovereign cloud (Q9); Germany cloud.
- MemoryOS tự sinh certificate để người dùng tải public key lên Entra. Phương án này tránh upload private key; ghi lại để cân nhắc sau.
- OCR, xử lý ảnh, extraction riêng cho SharePoint ngoài reader trang.

## 9. Rủi ro và khoảng trống bằng chứng

- **Xóa được phản ánh trong vòng một chu kỳ refresh** (mặc định 30 phút) nhờ tombstone [Q11], sớm hơn Onyx. Vẫn phải chờ lượt prune (mặc định tối đa 7 ngày) ở các trường hợp delta không thấy: app bị gỡ quyền đọc site, file bị chuyển ra khỏi thư mục root, item rơi khỏi phạm vi sau khi 410 buộc quét lại.
  - Với nội dung nhạy cảm, quản trị viên có thể giảm prune interval hoặc gỡ item/Source trong MemoryOS.
  - Cần ghi rõ trong hướng dẫn và trong help của trường Prune Frequency.
- **Bỏ sót item bị di chuyển** (O4, O5) — đã đo ở S0.3:
  - Graph **có** trả item bị di chuyển qua timestamp token, nên nguyên nhân bỏ sót nằm ở bộ lọc phía client của Onyx, không nằm ở API; Q12 bỏ bộ lọc đó;
  - nhánh BFS `children` cho root là thư mục vẫn bỏ sót thật, vì không có change-log và `lastModifiedDateTime` không đổi khi di chuyển;
  - lệch đồng hồ giữa máy ứng dụng và server Graph đo được 2,7 giây; độ chồng lấn 30 phút thừa sức che.
- **Tenant thử nghiệm hết hạn ngày 15/10/2026:** spike và nghiệm thu thật phải xong trước ngày đó, hoặc cần tenant khác (M365 Developer Program qua Visual Studio, hoặc tenant công ty). Không ghi secret vào repo hay Linear.
- **Đã xác minh trên tenant thật** ([Spike ledger](plan.md#spike-ledger), 16/09/2026): `quickXorHash`, hành vi timestamp token với xóa và di chuyển, 410 (`resyncRequired` với token cũ hơn ~60 ngày), host của `downloadUrl` và redirect của `/content`, `drive.webUrl` của site tiếng Việt, web part `textWebPart`/`standardWebPart` trong canvas.
- **Còn là khoảng trống bằng chứng:**
  - hành vi `Sites.Selected` với `getAllSites` — cần một app riêng, và việc cấp quyền theo site lại đòi `Sites.FullControl.All`;
  - ngưỡng ~60 ngày của timestamp token có thể khác theo tenant;
  - baseline trên Onyx Cloud với site tiếng Việt (S0.10) chưa chạy.
- **Throttling:** giới hạn SharePoint phụ thuộc tenant và không có header RateLimit. Lượt prune liệt kê toàn phạm vi là lượt tốn request nhất. Ngân sách chỉ kiểm được bằng fixture; số đo thật cần corpus lớn.
- **Phụ thuộc chưa merge:** MEM-105/MEM-106 có thể đổi contract. Kế hoạch bám theo nhánh hiện tại và phải đối chiếu lại khi các nhánh merge.
- **Provider mark SharePoint:** phải kiểm tra quyền sử dụng logo Microsoft trước khi đưa vào repository.
