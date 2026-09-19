# MEM-126 — SharePoint connector: kế hoạch

Design: [design.md](design.md). Tham chiếu: [Onyx](onyx-sharepoint-reference.md), [giao diện](ui-references.md). Tracking: [MEM-126](https://linear.app/memory-os/issue/MEM-126).

Trạng thái: **Giai đoạn 1–4 đã làm** (16/09/2026). Review PR #230 (19/09/2026) tìm ra 8 lỗi hành vi trong sync, URL và voice mà test lúc đó chưa bắt được; đã sửa kèm test tái hiện, xem [verification.md](verification.md). Còn lại: chặng Document → Search (`SharePointSourceIngestionTest`), E2E trên app thật, nghiệm thu tenant thật (Giai đoạn 5) và deep link webUrl trong citation (điểm mở).

## Nguyên tắc giao

- **PR đầu tiên vào `main` phải chạy được end-to-end:** tạo credential → tạo Source → đồng bộ → tài liệu tìm được trong Search/Chat → hiển thị trong phần Nguồn.
  - Giai đoạn 1–4 có thể là các commit riêng trên cùng nhánh.
  - Không merge API không có consumer, và không để provider chỉ cấu hình được mà không ingest được ([ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md)).
- **Mỗi thay đổi API** sinh lại `openapi.yml` và client hey-api trong cùng commit.
- **Gate chung:** `./gradlew clean check`, `pnpm check` và Playwright liên quan.
- **Bí mật:** không đưa secret, certificate, token hay `downloadUrl` vào repo, Linear, log hoặc lịch sử lệnh. Chỉ ghi nơi lưu và cách lấy.

## 0. Điều kiện bắt đầu

- [x] Người dùng chốt Q1–Q10 ([design §7](design.md#7-quyết-định)).
- [x] Tạo [MEM-126](https://linear.app/memory-os/issue/MEM-126) dưới MEM-118; nhánh `anhnd05122004/mem-126-sharepoint-connector-ket-noi-sharepoint-online-theo-logic` từ `main` `3ccafac7`.
- [x] Thêm increment vào `AGENTS.md` (Current active increments) và `docs/roadmap.md` (Active).
- [x] Người dùng chuẩn bị tenant và file thông tin đăng nhập cho spike ([Giai đoạn 0](#giai-đoạn-0--spike-trên-tenant-thật)).
- [ ] Trước giai đoạn 2: MEM-105 đã merge. Rebase và đối chiếu lại `SourceAccess`, luật SQL truy cập, pause/resume.
- [ ] Trước giai đoạn 4: MEM-106 đã merge. Rebase và đối chiếu lại bố cục Nguồn và danh sách component đã có.

## Giai đoạn 0 — Spike trên tenant thật

**Tenant:** Microsoft 365 Business Standard dùng thử, hết hạn **15/10/2026**.

**Người dùng chuẩn bị:**

1. Một site tạo bằng tiếng Việt, có vài file.
2. Upload một certificate lên app Entra (Certificates & secrets → Certificates).
3. File thông tin đăng nhập nằm ngoài repo, gồm Directory ID, Application ID, secret, đường dẫn `.pfx` và mật khẩu. Script spike đọc file này mà không in giá trị.

**Mã spike:** không commit code runtime. Chỉ dùng script trong scratch hoặc test tạm, rồi xóa.

**Ghi kết quả:** vào [Spike ledger](#spike-ledger), gồm response đã che, trường có hoặc không có, và mã lỗi.

**Đã chạy 16/09/2026.** S0.1 và S0.3–S0.9 đạt; S0.2 còn thiếu nhánh `Sites.Selected`; S0.10 chưa chạy. Hai phát hiện làm phát sinh [Q11 và Q12](design.md#7-quyết-định), đã chốt cùng ngày.

- [x] **S0.1** msal4j: lấy token Graph bằng client secret và bằng certificate (client assertion `x5t`). Ghi mã AADSTS cho các trường hợp: secret sai, certificate chưa upload lên app, tenant sai, chưa admin consent.
- [~] **S0.2** Quyền (nhánh `Sites.Read.All` đạt; nhánh `Sites.Selected` chưa kiểm, cần app riêng):
  - với `Sites.Read.All`: `GET /sites/root`, `/sites/getAllSites`;
  - app chỉ có `Sites.Selected`: `getAllSites` bị từ chối; site đã cấp `read` qua `POST /sites/{id}/permissions` đọc được.
- [x] **S0.3** Timestamp token và `children`, như Onyx:
  - `/drives/{id}/root/delta?token=<ISO>` sau khi thêm, sửa nội dung, đổi tên, di chuyển file trong thư viện, chuyển file vào/ra thư mục, xóa file, xóa thư mục có con;
  - ghi item nào xuất hiện, `createdDateTime`/`lastModifiedDateTime` có đổi khi di chuyển/đổi tên không, và các trường `file.hashes.quickXorHash`, `size`, `eTag`, `parentReference.id`, `deleted`;
  - so với BFS `children` trên thư mục.
- [x] **S0.4** 410: thử tái hiện với timestamp rất cũ hoặc URL sửa tay. Không tái hiện được thì ghi rõ, và chỉ kiểm bằng fixture theo tài liệu Graph.
- [x] **S0.5** `@microsoft.graph.downloadUrl`: host, thời hạn, có chứa `tempauth`; `/content` trả redirect về host nào.
- [x] **S0.6** Site **tiếng Việt**: tên drive, path của `drive.webUrl`; xác nhận khớp theo path hoạt động (Q3).
- [x] **S0.7** Trang site:
  - danh sách metadata;
  - `$expand=canvasLayout` từng trang;
  - ghi loại web part thực tế (text, standard, `searchablePlainTexts`);
  - trang tin tức; trang có bảng.
- [x] **S0.8** URL `/personal/` (OneDrive của một người trong tenant thử): resolve drive, delta (Q10).
- [x] **S0.9** Throttling: chỉ ghi nếu gặp 429/503 (header `Retry-After`). Không cố tình gây tải.
- [ ] **S0.10** Chạy lại luồng tương tự trên Onyx Cloud với site tiếng Việt và với credential certificate, để có bằng chứng baseline cho O2.

## Giai đoạn 1 — Credential Entra app

**Backend**

- [x] Migration: kind `SHAREPOINT_APP` trong CHECK `ck_credentials_kind`; bảng `sharepoint_credentials` (design §5.1).
- [x] Tách cipher AES-GCM dùng chung khỏi `GoogleDriveCredentialCipher` (Q7). Migrate caller Google trong cùng commit; dữ liệu Drive đã mã hóa vẫn giải được.
- [x] Cấu hình key `memoryos.sharepoint.credential-encryption-key` và `credential-key-version` cho API và worker; thiếu thì SharePoint fail closed. Cập nhật runbook biến môi trường và tên key Infisical, không kèm giá trị.
- [x] `gradle/libs.versions.toml`: thêm msal4j, pin version (Q2). Kiểm license và kích thước dependency.
- [x] Core `io.memoryos.connector`:
  - port `SharePointProvider`;
  - `SharePointCredentialService` / `DefaultSharePointCredentialService`: tạo, sửa, xóa, liệt kê, test; kiểm với provider ngoài transaction;
  - `SharePointCertificate`: PKCS12 → PKCS8 + X.509, RSA ≥ 2048, chưa hết hạn, ≤ 16 KiB;
  - `JdbcSharePointCredentialRepository`;
  - `SharePointException` với mã `SOURCE_SHAREPOINT_*`.
- [x] Provider bundle `provider/sharepoint`:
  - `MsalSharePointTokenSource`;
  - `RestSharePointProvider` (bước này chỉ có token + `/sites/root`), HTTP adapter có ngân sách, không redirect, header `User-Agent`;
  - phân loại AADSTS; `SharePointProviderProperties`; auto-configuration.
- [x] API `SharePointCredentialController` + DTO trong `api/.../source/contract/`; OpenAPI; hey-api.

**Test**

- [x] Unit:
  - PFX: hợp lệ, sai mật khẩu, không có key, nhiều key, RSA 1024, hết hạn, quá 16 KiB, không phải PKCS12;
  - GUID; phân loại AADSTS; `toString` đã che.
- [x] Provider HTTP (JDK `HttpServer`, như `RestGoogleDriveProviderTest`):
  - token endpoint nhận `client_secret` và `client_assertion` (kiểm `x5t`, `aud`, `iss`/`sub`);
  - lỗi AADSTS 400/401; 403 trên `/sites/root`;
  - không theo redirect; timeout.
- [x] PostgreSQL: mã hóa gắn AAD (đổi tenant/credential/purpose thì không giải được); revision/`If-Match`; cô lập Tenant; người quản lý theo phạm vi chỉ thấy credential của mình; xóa khi còn Source → conflict.
- [x] Test giải mã dữ liệu Drive hiện có sau khi tách cipher.
- [x] MVC/API: ma trận quyền; response không chứa secret hay key; body lỗi không lộ provider text; OpenAPI drift.

**Ghi chú thực hiện (16/09/2026)**

- Cipher chung là `CredentialCipher` (kind nằm trong AAD); `GoogleDriveCredentialCipher` bị xóa và mọi caller Drive chuyển sang cipher chung với purpose tường minh. `CredentialCipherTest` giải được một envelope Drive dựng theo công thức AAD cũ, nên dữ liệu đã mã hóa vẫn đọc được.
- GUID được phân giải ở controller (`SOURCE_SHAREPOINT_DIRECTORY_INVALID`), nên `Draft` mang `UUID` và service không phải phân tích chuỗi.
- msal4j **từ chối authority không phải https**, nên `MsalSharePointTokenSourceTest` phục vụ token endpoint qua TLS bằng certificate `localhost` có sẵn trong test resource. Test kiểm được cả `client_secret` lẫn `client_assertion`; msal4j gửi thumbprint ở `x5t#S256` (base64 chuẩn) chứ không phải `x5t`, và thêm `openid profile offline_access` vào scope.
- Quyền quản trị vẫn do application service kiểm; test MVC mock service và kiểm rằng từ chối của service thành `403`, còn ma trận quyền thật nằm ở `PostgresSharePointCredentialTest`.
- msal4j 1.21.0 là **MIT**. Phần thêm vào classpath khoảng **0,8 MiB** (msal4j, `azure-json`, `json-smart`); `nimbus-jose-jwt` và `oauth2-oidc-sdk` đã có sẵn theo Spring Security OAuth2.
- Fence Source khi thay secret/certificate vẫn thuộc [Giai đoạn 2](#giai-đoạn-2--source-xác-minh-phạm-vi-refresh-và-prune-thư-viện): chưa có Source SharePoint nào tồn tại ở bước này.

**Xong khi:** gate backend xanh. Chưa merge riêng giai đoạn này.

## Giai đoạn 2 — Source, xác minh phạm vi, refresh và prune thư viện

**Backend**

- [x] Migration:
  - `SHAREPOINT` trong `ck_connectors_type`;
  - `sharepoint_sources`, `sharepoint_roots`, `sharepoint_exclusions`;
  - `sharepoint_selection_operations` (+ entries);
  - `sharepoint_sync_runs`, `sharepoint_items` (design §5.9).
- [x] `SourceType.SHAREPOINT` và mọi switch exhaustive: summary, `SourcePermissions`, access resolver, provenance Search, cleanup, due scan.
- [x] `SharePointUrl`: tiền tố share link, `/sites|/teams|/personal`, đoạn thư viện/thư mục, host theo cloud. `SharePointGlob` cho site và path.
- [x] `SharePointSelectionPolicy` + endpoint policy.
- [x] Tạo Source và `PUT …/scope` → `202` + receipt. Workload `SHAREPOINT_SELECTION_VALIDATION`:
  - stream v1; routing; config exhaustive;
  - `SelectionValidationProcessor` phân nhánh;
  - resolve site → drive theo path `webUrl` → folder;
  - activation transaction; supersede proposal cũ; gỡ item ngoài phạm vi mới; đánh dấu root mới cần refresh từ epoch.
- [x] `DefaultSharePointSyncService`, lượt **refresh**:
  - cửa sổ `[cuối lượt thành công trước − 30 phút, bắt đầu lượt]` (lượt đầu và root mới từ epoch), lượt lỗi giữ nguyên mốc;
  - delta timestamp token cho thư viện, BFS `children` cho thư mục;
  - nhánh delta không lọc lại theo cửa sổ, nhánh BFS vẫn lọc [Q12]; bỏ thư mục; tombstone `deleted` → `REMOVE_ITEM` theo `provider_file_id` [Q11]; bỏ trùng; 410 → `Location`;
  - `excludedPaths` dựa trên đường dẫn dựng từ parent id;
  - `Retry-After` → `next_dispatch_at`; checkpoint theo drive/site; tối đa 16 bước mỗi lần giao.
  - `SourceSyncProcessor` phân nhánh theo `SourceType`.
- [x] Lượt **prune** (lưới an toàn cho những gì lượt refresh không thấy):
  - liệt kê đầy đủ chỉ metadata (delta không token, BFS, danh sách site);
  - hoàn tất toàn phạm vi mới tạo `REMOVE_ITEM`; không hoàn tất thì `SOURCE_SHAREPOINT_PRUNE_INCOMPLETE` và không gỡ gì;
  - due scan ưu tiên prune khi đến hạn; `pruneIntervalHours = 0` thì không bao giờ prune.
- [x] Acquisition:
  - `downloadUrl` kiểm host, fallback `/content` một redirect, stream có giới hạn 100 MiB;
  - `ObjectWriteService` stage/adopt; INDEX attempt có `source_sync_attempt_id`;
  - counter lượt chạy; `skipped` có mã.
- [ ] Run history: loại lượt REFRESH/PRUNE và cửa sổ trong projection (nullable cho Drive/FILE).
- [ ] Truy cập `PUBLIC`/`PRIVATE` theo contract MEM-105: nhánh `SHAREPOINT` trong luật SQL dùng chung của `JdbcSourceDocumentRepository`; `SourceAccessChanged`.
- [x] Đọc cấu hình; phân trang root; `schedule` (`syncIntervalMinutes`, `pruneIntervalHours`); `sync`; pause/resume theo contract hiện hành.
- [ ] Thay secret/certificate fence Source (credential revision). Xóa Source dọn theo thứ tự phụ thuộc.
- [ ] Metric `memoryos.connector.provider.request`; log `event`.

**Ghi chú thực hiện (16/09/2026)**

- **Còn mở trong giai đoạn này:** test worker full-context, test MVC cho các endpoint Source, metric/log theo [quy ước quan sát](../../../guidelines/observability.md), `SourceAccessChanged`, dọn Source khi xóa, và loại lượt REFRESH/PRUNE trong projection run history.
- **Truy cập:** MEM-105 chưa merge, nên Source SharePoint dùng đúng contract hiện tại (`PUBLIC` | `RESTRICTED`) và tài liệu SharePoint được đưa vào tập tìm kiếm theo Group grant sẵn có. Khi MEM-105 vào phải đối chiếu lại tên và ngữ nghĩa `PRIVATE`.
- **Lọc định dạng:** lượt refresh mới chặn theo kích thước (100 MiB). Lọc theo định dạng router extraction hỗ trợ nằm ở tầng ingestion chứ không ở `core`, nên file không đọc được sẽ hỏng ở lượt index chứ chưa bị `skipped` với mã riêng như design §5.3 mô tả. Cần thống nhất lại khi hợp nhất tài liệu ở [Giai đoạn 5](#giai-đoạn-5--hợp-nhất-tài-liệu-và-nghiệm-thu).
- **Trang site** (`includePages`) được lưu trong cấu hình nhưng chưa đồng bộ; đó là [Giai đoạn 3](#giai-đoạn-3--trang-site).
- **Hai lỗi nền tảng do test bắt được:** `source_sync_attempts` có khóa ngoại tới `google_drive_sources` nên Source SharePoint không thể có lượt chạy; và activation enqueue qua repository của Drive nên Source mới không bao giờ khởi động. Cả hai đã sửa.
- Bảng `source_sync_attempts` dùng chung: `DefaultConnectorSyncService` định tuyến lượt chạy theo `SourceType` của Source.
- Đường indexing dùng chung giờ định tuyến authority theo `SourceType`: SharePoint tự kiểm `scope_revision` và `credential_revision` thay vì bị gọi nhầm qua `GoogleDriveConnectionService`. Dispatch chỉ nhận scope của provider đã triển khai; terminal supersede tính lại trạng thái Source nên không còn kẹt `INDEXING`.

**Test**

- [x] Unit:
  - `SharePointUrl` với các loại URL thật (share link `/:f:/r/`, `/teams/`, `/personal/`, thư mục lồng, `%20`, host sai tenant, `http://`);
  - glob;
  - tính cửa sổ: lượt đầu, sau lượt thành công, sau lượt lỗi, root mới;
  - lọc theo cửa sổ ở nhánh BFS và bỏ trùng; dựng đường dẫn cho `excludedPaths`;
  - so phiên bản hash/eTag; phân loại lỗi.
- [x] Provider HTTP:
  - delta có `token` timestamp và không có token; `nextLink`; tombstone `deleted` sinh `REMOVE_ITEM`, gồm cả tombstone của từng file con khi xóa thư mục; item bị di chuyển có `lastModifiedDateTime` cũ hơn cửa sổ vẫn được giữ;
  - BFS `children` nhiều trang;
  - 410 kèm `Location`;
  - 429/503 `Retry-After`;
  - `downloadUrl` khác host → từ chối; redirect `/content` hợp lệ và không hợp lệ;
  - vượt 100 MiB giữa stream;
  - `getAllSites` phân trang và 403;
  - drive khớp theo path với tên tiếng Việt.
- [x] PostgreSQL:
  - activation nguyên tử; thất bại không tạo Source; supersede;
  - cửa sổ lưu và đọc lại đúng;
  - prune hoàn tất gỡ item vắng mặt; prune không hoàn tất (một site 403) không gỡ gì;
  - prune tắt; refresh gỡ theo tombstone và không gỡ gì khác;
  - fence khi đổi phạm vi hoặc credential;
  - counter idempotent khi giao lặp; cô lập Tenant.
- [ ] Full context worker: provider giả + PostgreSQL/Redis/MinIO fixture hiện có.
  - Tạo → refresh → Document → Search thấy tài liệu với PUBLIC và PRIVATE.
  - Refresh không có thay đổi → "No changes".
  - Xóa file trên provider giả → lượt refresh kế tiếp gỡ khỏi Search.
  - File biến mất mà delta không báo (site trả 403) → refresh giữ nguyên; prune gỡ.
- [ ] MVC/API: ma trận quyền (design §5.8), `If-Match`, `202` + receipt recovery, validation interval/prune interval, OpenAPI drift.

## Giai đoạn 3 — Trang site

- [x] Input format `SHAREPOINT_PAGE` trong `SourceInputDescriptor`.
- [x] Refresh: liệt kê metadata trang → lấy canvas cho trang có `lastModifiedDateTime` trong cửa sổ → snapshot JSON. Prune: gỡ trang không còn.
- [x] `SharePointPageSourceContentExtractor` (jsoup) đăng ký trong `SourceContentExtractorRouter`.
- [x] Bật/tắt `includePages` đi qua cập nhật phạm vi và fence.
      **Ghi chú thực hiện (16/09/2026)**

- Snapshot trang là JSON `memoryos-sharepoint-page-v1`: tiêu đề, `textAboveTitle`, mô tả, HTML của từng `textWebPart` và `searchablePlainTexts` của các web part khác. Bố cục và cấu hình web part không được lưu.
- Reader dùng jsoup giữ heading, paragraph, list item và table thành canonical block; trang rỗng vẫn ra tiêu đề.
- Trang **không có change log**, nên lượt refresh liệt kê rồi so version từng trang, và lượt prune gỡ trang không còn được xuất bản. Mỗi trang đọc canvas riêng nên trang hỏng chỉ ảnh hưởng chính nó.
- Migration mở `ck_item_versions_input` cho `SHAREPOINT_PAGE` (test bắt được: nếu không, trang được tải về nhưng không ghi được version).

- [x] **Test:**
  - fixture canvas từ S0.7 (đã che): heading, list, table, standard web part, trang rỗng, canvas lỗi;
  - provider 404/400;
  - PostgreSQL prune trang;
  - full context: trang xuất hiện trong Search với citation `webUrl`.

## Giai đoạn 4 — Giao diện (shadcn)

- [x] Đối chiếu component đã có sau MEM-106. Cài phần còn thiếu bằng `pnpm exec shadcn add` và chuyển về token/`ui()`; ghi deviation vào [ui-references §9](ui-references.md#9-control-shadcn).
      **Làm trước MEM-106**: MEM-106 chưa tổng quát hoá credential Drive, nên đã tự tách phần chung
      (`source-selection-operation.ts` cho receipt/khôi phục/poll; `SharePointCredentialSection` riêng).
      Khi MEM-106 merge phải đối chiếu lại component credential/access và gỡ phần trùng.
- [x] Catalog: thêm SharePoint vào `source-provider-catalog.ts`; provider mark SharePoint (`sharepoint-icon.tsx`).
- [x] Route `_authenticated.admin.sources.new.sharepoint.tsx`; trang tạo với setup rail Credential → Content → Access → Review (`create-sharepoint-source-page.tsx`).
- [x] Rà soát enterprise ngày 18/09/2026 theo Mobbin và Glean:
  - setup rộng với rail bước sticky; scope và visibility là radio-card có hệ quả quyền ngay tại lựa chọn;
  - credential dùng `Dialog` shadcn, tách hướng dẫn Entra khỏi identifiers/authentication;
  - review và trang chi tiết dùng `Card` theo capability, có hành động sửa tại chỗ.
- [x] Credential:
  - danh sách/picker theo mẫu Drive #4a, bản SharePoint riêng vì MEM-106 chưa tổng quát hoá;
  - hộp thoại `RadioGroup` card, secret, upload `.pfx` + mật khẩu (`sharepoint-credential-input.tsx`);
  - hướng dẫn Entra (bước đánh số, bảng quyền, nút copy) trong `sharepoint-entra-guide.tsx`;
    Test / Rename / Replace authentication / Delete bằng `ConfirmDialog` + `If-Match`.
  - Secret và PFX chỉ nằm trong `useRef`, `take()` trả một lần rồi xoá; không vào React Query,
    state sống sót sau submit, hay browser storage.
- [x] Phạm vi (`sharepoint-scope-fields.tsx` + `sharepoint-scope.ts`):
  - `RadioGroup` All sites / Specific sites;
  - `Textarea` URL có lỗi theo từng dòng (bản TS của `SharePointUrl.java`: sharing link `:f:/r`,
    view `Forms` → LIBRARY, `/sites|/teams|/personal`, phát hiện lồng nhau, khác host, trùng);
  - Nâng cao: hai `Switch` include và hai `Textarea` loại trừ.
- [x] Truy cập Public/Private + Group: `GroupAccessPicker` dùng lại.
- [x] Xem lại:
  - interval mặc định 30 phút; prune interval mặc định 168 giờ, 0 là tắt, kèm help giải thích
    (`sharepoint-schedule-fields.tsx`);
  - tạo → receipt 202 đang chờ → điều hướng sang trang chi tiết khi operation SUCCEEDED;
  - khôi phục `requestId` qua sessionStorage (`memoryos:sharepoint-selection:…`).
- [x] Chi tiết Source (`sharepoint-panel.tsx`):
  - panel Đồng bộ: interval, prune interval, last prune, pause, sync now;
  - Phạm vi: danh sách root, sửa inline (không `Sheet` — khớp pattern panel Drive);
  - Credential: phương thức, hạn certificate, host;
  - `Alert` theo mã lỗi; backend `SharePointException.rejected` giờ sinh mã riêng cho từng lý do
    Entra từ chối để giao diện dịch được;
  - run history hiện cột Kind (REFRESH/PRUNE) trong `source-run-history.tsx`.
- [x] Search/Chat: icon SharePoint qua `findSourceProvider` trong `document-source-icon.tsx`;
      `ProviderLink` nhận `provider` thay vì hardcode "Google Drive". Deep link SharePoint vẫn chờ
      quyết định webUrl (điểm mở đã ghi) — chưa bịa liên kết.
- [x] Nhãn tiếng Anh viết trong code, bản dịch vi trong catalog; `pnpm check:i18n` sạch.
- [x] **Test:**
  - Vitest: `sharepoint-scope.test.ts` (parse, coverage, per-line problems, policy, schedule),
    `sharepoint-credential-input.test.tsx` (take() một lần, unmount xoá, giới hạn .pfx/secret),
    `sharepoint-panel.test.tsx` (mock hey-api theo mẫu `google-drive-panel.test.tsx`).
  - Playwright `web/tests/e2e/sharepoint-source-setup.spec.ts`: luồng 4 bước với receipt 202 và
    lỗi địa chỉ theo dòng, mock API bằng `page.route`.
  - Playwright SharePoint: 2/2 đạt. Kiểm trực quan trên app fixture thật ở 1440 px và 390 px: review có ba card, rail bước responsive và không tràn ngang; chưa chụp đủ ma trận sáng/tối.

## Giai đoạn 4b — Auto Sync theo quyền SharePoint (Q13)

Thiết kế ở [design §5.7.1](design.md#571-auto-sync-q13). Làm như Google Drive, dùng lại hạ tầng MEM-88/MEM-105.

**Spike xác nhận trên tenant thật** (không quyết định cơ chế nữa vì đã theo Onyx; dùng tenant và file thông tin đăng nhập của Giai đoạn 0, ghi vào [Spike ledger](#spike-ledger)):

- [ ] **S0.11** Với credential certificate: REST `roleassignments` của list item tìm theo `listId` (site tiếng Việt), của trang qua `ListItemAllFields`; ghi loại principal, `LoginName`, `RoleTypeKind`. Credential client secret bị REST từ chối với mã nào.
- [ ] **S0.12** Group sync: role assignment cấp web, `sitegroups/{id}/users`, Graph thành viên Entra group với `GroupMember.Read.All`; mã lỗi khi thiếu quyền; UPN của khách (guest) và người dùng nội bộ sau `normalize_email`.

**Backend:**

- [ ] `SourceAccessPolicy`, `JdbcSourceRepository.updateAccess`: `SYNC` cho provider có đồng bộ quyền (Drive, SharePoint); SharePoint mặc định `SYNC`; `SYNC` cần credential certificate; scoped manager chọn `PRIVATE` hoặc `SYNC`.
- [ ] Port `SharePointProvider`: REST role assignment (item theo `listId`, trang), role assignment cấp web, `sitegroups` users, Graph thành viên Entra group; token REST từ certificate; record có `toString` đã che.
- [ ] Migration mới: `sharepoint_acl_snapshots` (`public`, `user_emails`, `group_ids`, provenance như V75/V76), `sharepoint_external_groups` + thành viên (theo Source, provenance của lượt group sync), cột `treat_sharing_link_as_public`, lịch `next_permission_sync_at` (30 phút) và `next_group_sync_at` (5 phút), loại lượt `PERMISSIONS`/`GROUPS`.
- [ ] `DefaultSharePointSyncService`: gắn quyền khi acquire; lượt `PERMISSIONS` đọc lại quyền mọi item đang giữ (item không trả về mất quyền); lượt `GROUPS` thay toàn bộ thành viên group của Source; đọc ngoài transaction, công bố trong `fenced`, lỗi ghi lên snapshot mà không chặn nội dung.
- [ ] `SourceAclChanged` thay `GoogleDriveAclChanged` (Drive phát cùng sự kiện).
- [ ] `JdbcSourceDocumentRepository`: `SYNC_GRANTS` thành UNION theo `connector_type` (`ms_user:`, `ms_group:<sourceId>:<groupId>`, `everyone`); `READER_TOKENS` thêm `ms_user:` và `ms_group:` từ email đã xác minh.
- [ ] Credential/Source validation: từ chối client secret cho Auto Sync; thử role assignment trên vài site, không chặn.
- [ ] ADR mở rộng ADR 0011.

**Frontend:** chọn Auto Sync khi tạo và khi đổi truy cập (chỉ với credential certificate, có giải thích); mô tả "Người có quyền trong SharePoint"; tùy chọn "Coi link chia sẻ là công khai"; mã lỗi mới có vi/en.

**Kiểm chứng** (mirror Drive và test của Onyx `test_permission_utils.py`, `test_sharepoint_permissions.py`): từng loại principal, Limited Access bị bỏ, Everyone → public, group lồng nhau, `normalize_email`; người đọc thuộc group qua group sync đọc được, rời group thì mất quyền mà không index lại; snapshot provenance, lỗi giữ snapshot cũ, cô lập Tenant; credential secret bị từ chối cho Auto Sync; đổi quyền chỉ refresh tài liệu Source `SYNC`.

## Giai đoạn 5 — Hợp nhất tài liệu và nghiệm thu

- [x] `docs/specs/connector.md`: phần SharePoint (credential, phạm vi, refresh/prune, lỗi, truy cập). Hướng dẫn thiết lập Entra nằm ở `README.md`.
- [x] `docs/specs/ingestion.md` (workload, stream riêng, activation); `docs/specs/search.md` (access). `docs/specs/document.md` không đổi: snapshot trang đi qua đúng đường native snapshot sẵn có.
- [x] `docs/tests/connector.md`: ma trận SharePoint có ngày và test tương ứng. `ARCHITECTURE.md`: connector SharePoint.
- [ ] Nghiệm thu thật trên tenant (trước 15/10/2026, hoặc tenant thay thế):
  - client secret và certificate;
  - site tiếng Anh và tiếng Việt;
  - thêm, sửa, đổi tên ở refresh; xóa ở prune (prune interval đặt 1 giờ khi nghiệm thu);
  - trang site; `/personal/`; throttling nếu gặp;
  - Search/Chat trả citation mở đúng SharePoint.
  - Ghi bằng chứng đã che vào `verification.md`.
- [ ] PR merge xong: chuyển increment sang `completed/`, đối chiếu roadmap và Linear.

## Ma trận kiểm chứng dự kiến

Đã chạy, xem [ma trận Connector](../../../tests/connector.md#mem-126-sharepoint-connector--2026-09-16).

| Contract                                                                                           | Boundary            | Test                                                                                                                               | Trạng thái                                                                                                            |
| -------------------------------------------------------------------------------------------------- | ------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| PFX, GUID, URL, glob, phân loại lỗi                                                                | Unit                | `SharePointCertificateTest`, `SharePointUrlTest`, `SharePointGlobTest`, `CredentialCipherTest`                                     | Xong                                                                                                                  |
| Token, delta timestamp, children, 410, host download, redirect, giới hạn byte, trang               | Provider HTTP giả   | `RestSharePointProviderTest`, `MsalSharePointTokenSourceTest`                                                                      | Xong                                                                                                                  |
| Mã hóa, revision, activation, cửa sổ, prune hoàn tất/không hoàn tất, fence, counter, cô lập Tenant | PostgreSQL + Flyway | `PostgresSharePointCredentialTest`, `PostgresSharePointSelectionTest`, `PostgresSharePointSyncTest`                                | Xong                                                                                                                  |
| Reader trang                                                                                       | Unit với fixture    | `SharePointPageExtractionTest`                                                                                                     | Xong                                                                                                                  |
| Quyền HTTP, `If-Match`, `202`, problem details, OpenAPI                                            | MVC/API             | `SharePointCredentialApiTest`, `SharePointSourceApiTest`, `OpenApiContractTest`                                                    | Xong                                                                                                                  |
| Ingest → Document → Search thấy tài liệu                                                           | Full context worker | `SharePointSourceIngestionTest`                                                                                                    | **Chưa**: acquisition và index attempt đã được kiểm ở `PostgresSharePointSyncTest`; còn thiếu chặng Document → Search |
| Luồng tạo, lỗi, responsive, bàn phím                                                               | Vitest + Playwright | `sharepoint-scope.test.ts`, `sharepoint-credential-input.test.tsx`, `sharepoint-panel.test.tsx`, `sharepoint-source-setup.spec.ts` | Xong (unit); E2E đã viết, chờ chạy trên app thật                                                                      |
| Kiến trúc                                                                                          | ArchUnit/Modulith   | `ProviderDependencyRulesTest`, `ModulithArchitectureTest`                                                                          | Chạy trong gate hiện có, chưa mở rộng riêng cho SharePoint                                                            |

## Spike ledger

Chạy **16/09/2026** trên tenant thử `memoryosvadan` (M365 Business Standard, hết hạn 15/10/2026), app Entra `Onyx Test`.
Quyền lúc chạy: `Sites.Read.All` và `Sites.ReadWrite.All` (Application). `Sites.ReadWrite.All` **chỉ để tạo dữ liệu thử**; connector chỉ cần `Sites.Read.All` ([design §5.1](design.md#51-credential)).
Script chạy ngoài repo, đọc credential từ file ngoài repo, không in giá trị. Dữ liệu thử đã dọn sau khi chạy.

| Mục                       | Kết quả                                   |
| ------------------------- | ----------------------------------------- |
| S0.1 Xác thực             | **Đạt**                                   |
| S0.2 Quyền và phạm vi     | **Một phần** — `Sites.Selected` chưa kiểm |
| S0.3 Delta theo timestamp | **Đạt, có 2 phát hiện ngược giả định**    |
| S0.4 410                  | **Đạt, tái hiện được**                    |
| S0.5 Tải nội dung         | **Đạt**                                   |
| S0.6 Site tiếng Việt      | **Đạt, xác nhận Q3**                      |
| S0.7 Trang site           | **Đạt**                                   |
| S0.8 URL `/personal/`     | **Đạt**                                   |
| S0.9 Throttling           | **Không gặp**                             |
| S0.10 Baseline Onyx Cloud | **Chưa chạy**                             |

### S0.1 — msal4j / client credentials

- Client secret và certificate (client assertion RS256 với `x5t`) đều lấy được token `Bearer`, `expires_in=3599`.
- Certificate tự ký RSA-2048 SHA-256, upload public key `.cer` lên app; private key chỉ nằm ở máy chạy spike.
- Mã lỗi ghi được, dùng cho phân loại lỗi credential ở [design §5.6](design.md#56-lỗi-và-bảo-mật-provider):

| Trường hợp                      | HTTP | Mã                                                                 |
| ------------------------------- | ---- | ------------------------------------------------------------------ |
| Secret sai                      | 401  | `AADSTS7000215` (thông điệp nhắc gửi nhầm Secret ID thay vì Value) |
| Tenant không hợp lệ             | 400  | `AADSTS900021`                                                     |
| Certificate chưa upload lên app | 401  | `AADSTS700027`                                                     |
| Sai mật khẩu PFX                | —    | lỗi cục bộ khi mở PKCS#12, chưa gọi mạng                           |

### S0.2 — quyền và phạm vi

- `GET /sites/root` → 200.
- `GET /sites/getAllSites` → 200, **8 site**, không phân trang ở tenant này. Trả về:
  - site OneDrive cá nhân, có cờ **`isPersonalSite: true`** → lọc bằng cờ này, chính xác hơn so host `{tenant}-my` [cập nhật cách sửa lỗi O8 trong design §5.3];
  - site `https://…/search` có **`name: null`** → code phải chịu được tên rỗng;
  - `contentTypeHub` và site gốc `/` cũng nằm trong danh sách.
- **Chưa kiểm:** app chỉ có `Sites.Selected`. Cần một app đăng ký riêng, và việc cấp quyền theo site (`POST /sites/{id}/permissions`) lại đòi `Sites.FullControl.All` cho app đi cấp. Ghi là khoảng trống bằng chứng.

### S0.3 — delta theo timestamp token

Thí nghiệm trên thư viện `Tài liệu` của site `MemoryOSVi`: tạo thư mục và file, sửa nội dung, đổi tên, di chuyển giữa hai thư mục, xóa file, xóa thư mục có 2 file con. Sau mỗi thao tác gọi `GET /drives/{id}/root/delta?token=<ISO-8601>` với mốc lấy ngay trước thao tác.

| Thao tác                    | Delta có trả item?                                | `lastModifiedDateTime`  |
| --------------------------- | ------------------------------------------------- | ----------------------- |
| Tạo file, tạo thư mục       | Có, kèm `root`                                    | mới                     |
| Sửa nội dung                | Có                                                | mới, `quickXorHash` đổi |
| Đổi tên                     | Có, tên mới                                       | **đổi**                 |
| Di chuyển sang thư mục khác | **Có**                                            | **không đổi**           |
| Xóa file                    | **Có, dạng tombstone**                            | không có                |
| Xóa thư mục có con          | **Có: tombstone cho cả thư mục và từng file con** | không có                |

**Phát hiện 1 — timestamp token vẫn trả bản ghi xóa.** Tombstone có `deleted: {state: "deleted"}`, `id`, `parentReference` (driveId, siteId, id thư mục cha), `cTag` với version `-1`, `size: 0`, `file.hashes.quickXorHash` toàn `A`; **không có `name`**. Xóa một thư mục có 2 file con trả đủ 3 tombstone. Nghĩa là **phát hiện xóa không bắt buộc phải chờ lượt prune**, khác giả định trong [tham chiếu Onyx](onyx-sharepoint-reference.md) và khác hệ quả đã ghi cho Q1.

**Phát hiện 2 — delta là change-log, không phải bộ lọc theo `lastModifiedDateTime`.** Kiểm định riêng: tạo file, **đợi 45 giây**, lấy mốc token, rồi di chuyển file. `lastModifiedDateTime` giữ nguyên giá trị **cũ hơn mốc token**, nhưng delta **vẫn trả** item với `parentReference.path` mới. Vậy bộ lọc phía client `max(createdDateTime, lastModifiedDateTime) ∈ [start, end]` trong [design §5.3](design.md#53-đồng-bộ-thư-viện-q1-giữ-mô-hình-onyx-q11-q12-sửa-theo-spike) **sẽ loại mất chính những item vừa được di chuyển vào phạm vi** — đúng rủi ro O4/O5, nhưng nguyên nhân nằm ở bộ lọc của Onyx, không nằm ở Graph.

**Lệch đồng hồ:** máy chạy spike chậm hơn server Graph **2,7 giây**. Mốc cửa sổ lấy theo giờ máy ứng dụng, nên độ chồng lấn phải lớn hơn lệch đồng hồ; 30 phút vẫn thừa sức.

### S0.4 — 410 `resyncRequired`

**Tái hiện được, và đo được ngưỡng.** Cùng một thư viện, token thời gian càng cũ càng bị từ chối:

| Mốc token                                 | Kết quả                                                  |
| ----------------------------------------- | -------------------------------------------------------- |
| −1 ngày → −60 ngày                        | 200, trả đủ item                                         |
| −61 ngày trở về trước (thử tới −730 ngày) | **410 `resyncRequired`**                                 |
| Chuỗi không phải thời gian (`not-a-date`) | 400 `invalidRequest`, "Provided sync token is malformed" |
| Chuỗi giống token nhưng sai               | 400 `invalidRequest`                                     |
| `token=latest`                            | 200, 0 item, có `@odata.deltaLink`                       |

Ngưỡng ~60 ngày chưa chắc là hằng số của dịch vụ, nhưng đủ để kết luận: **Source bị tạm dừng lâu sẽ buộc phải quét lại toàn bộ**. Nhánh 410 trong design là bắt buộc, không phải phòng xa.

### S0.5 — metadata và tải nội dung

- `file.hashes.quickXorHash` **có** trên thư viện SharePoint (không chỉ OneDrive) → giữ nguyên quy tắc phiên bản `quickXorHash` + `size`.
- Item trả cả `eTag` và `cTag` khi gọi trực tiếp `/items/{id}`.
- `@microsoft.graph.downloadUrl` trỏ **host tenant** `memoryosvadan.sharepoint.com`, query gồm `tempauth`, `UniqueId`, `ApiVersion`, `Translate` → xác nhận phép kiểm tra host và quy tắc không log `tempauth`.
- `GET /items/{id}/content` trả **302** về **cùng host tenant** → quy tắc "chấp nhận đúng một redirect, kiểm host đích" là đủ.

### S0.6 — site tiếng Việt (xác nhận Q3)

Site `MemoryOSVi` tạo với ngôn ngữ Tiếng Việt. Token app-only, không có ngữ cảnh ngôn ngữ người dùng:

```
drive.name   = 'Tài liệu'
drive.webUrl = https://memoryosvadan.sharepoint.com/sites/MemoryOSVi/Shared%20Documents
```

So với site tiếng Anh `Onyxtest`: `drive.name = 'Documents'`, cùng path `/Shared Documents`.

Kết luận: **khớp theo tên thư viện trượt trên site tiếng Việt, khớp theo path của `webUrl` trúng cả hai** → Q3 đúng, và bảng 3 ngôn ngữ của Onyx (O2) đúng là lỗi. Giao diện trình duyệt hiện "Documents" vì SharePoint dịch tên thư viện hệ thống theo ngôn ngữ hiển thị của người dùng; không dùng giao diện để kết luận.

### S0.7 — trang site

- `GET /sites/{id}/pages` trả danh sách; `?$expand=canvasLayout` trên `/microsoft.graph.sitePage` chạy trên **v1.0**.
- Trang mặc định (`Home.aspx`, `TopicHome.aspx`) có `canvasLayout.horizontalSections = []` → reader phải chịu được trang rỗng, và trang mặc định gần như không có nội dung để index.
- Trang tự tạo có `textWebPart`: `innerHtml` trả **nguyên văn HTML kể cả bảng**, giữ tiếng Việt:
  `<p>Đoạn văn bản…</p><table><tbody><tr><th>Cột A</th>…</table>`
  → reader cần chuyển HTML sang text và giữ được bảng.
- Trang có `standardWebPart` (Quick links): đọc trên v1.0 trả `data.title` và `data.serverProcessedContent.searchablePlainTexts` dạng `[{key, value}]` → đúng như Onyx khai thác.
- `titleArea` trả `title`, `textAboveTitle`, `authorByline`.
- Ghi chú phụ: **tạo** trang có `standardWebPart` chỉ chạy trên endpoint beta (v1.0 trả 400 "Parsing JSON Light resource sets…"). Không ảnh hưởng connector vì connector chỉ đọc.

### S0.8 — URL `/personal/` (Q10)

- `GET /sites/{tenant}-my.sharepoint.com:/personal/{upn_thay_@_và_._bằng_}` → 200.
- Site cá nhân có **2 drive**: `PersonalCacheLibrary` (phải bỏ) và `OneDrive`.
- Drive chính tên **`OneDrive`**, `driveType: business`, `webUrl` kết thúc bằng **`/Documents`**, không phải `/Shared Documents`.
  → luật khớp thư viện theo path phải có nhánh riêng cho site cá nhân; khớp theo tên cũng trượt ở đây.
- `GET /drives/{id}/root/delta` không token chạy bình thường và có `@odata.deltaLink`.
- `isPersonalSite` chỉ xuất hiện trong kết quả `getAllSites`, **không** có khi resolve site trực tiếp.

### S0.9 — throttling

Không gặp 429 hay 503 trong khoảng 90 request của spike. Không chủ động gây tải. Ngân sách request vẫn chỉ kiểm được bằng fixture.

### S0.10 — baseline Onyx Cloud

Chưa chạy. Cần thêm một connector SharePoint trên Onyx Cloud trỏ tới `https://memoryosvadan.sharepoint.com/sites/MemoryOSVi` để xác nhận Onyx **không index được** thư viện `Tài liệu` (bằng chứng ngoài cho O2), và thử credential dạng certificate.

### Hệ quả với thiết kế

1. **Q1 cần chốt lại một điểm** ([design §7](design.md#7-quyết-định), Q11): timestamp token vẫn trả tombstone, nên lượt refresh có thể gỡ tài liệu bị xóa ngay trong lượt, thay vì chờ tới 7 ngày.
2. **Bỏ bộ lọc cửa sổ phía client cho nhánh delta** (Q12): bộ lọc này loại mất item bị di chuyển. Nhánh BFS `children` cho root là thư mục vẫn cần lọc.
3. Lọc site cá nhân bằng `isPersonalSite`, và chịu được `name: null`.
4. Luật khớp thư viện theo path thêm nhánh `/Documents` cho site `/personal/`.
5. Nhánh 410 là bắt buộc; ghi ngưỡng quan sát được (~60 ngày) vào tài liệu vận hành.
