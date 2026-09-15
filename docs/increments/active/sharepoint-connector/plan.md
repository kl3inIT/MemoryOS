# MEM-126 — SharePoint connector: kế hoạch

Design: [design.md](design.md). Tham chiếu: [Onyx](onyx-sharepoint-reference.md), [giao diện](ui-references.md). Tracking: [MEM-126](https://linear.app/memory-os/issue/MEM-126).

Trạng thái: **đã chốt hướng, chưa bắt đầu triển khai** (16/09/2026). Chưa chạy spike nào; chưa có code.

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
- [ ] Người dùng chuẩn bị tenant và file thông tin đăng nhập cho spike ([Giai đoạn 0](#giai-đoạn-0--spike-trên-tenant-thật)).
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

- [ ] **S0.1** msal4j: lấy token Graph bằng client secret và bằng certificate (client assertion `x5t`). Ghi mã AADSTS cho các trường hợp: secret sai, certificate chưa upload lên app, tenant sai, chưa admin consent.
- [ ] **S0.2** Quyền:
  - với `Sites.Read.All`: `GET /sites/root`, `/sites/getAllSites`;
  - app chỉ có `Sites.Selected`: `getAllSites` bị từ chối; site đã cấp `read` qua `POST /sites/{id}/permissions` đọc được.
- [ ] **S0.3** Timestamp token và `children`, như Onyx:
  - `/drives/{id}/root/delta?token=<ISO>` sau khi thêm, sửa nội dung, đổi tên, di chuyển file trong thư viện, chuyển file vào/ra thư mục, xóa file, xóa thư mục có con;
  - ghi item nào xuất hiện, `createdDateTime`/`lastModifiedDateTime` có đổi khi di chuyển/đổi tên không, và các trường `file.hashes.quickXorHash`, `size`, `eTag`, `parentReference.id`, `deleted`;
  - so với BFS `children` trên thư mục.
- [ ] **S0.4** 410: thử tái hiện với timestamp rất cũ hoặc URL sửa tay. Không tái hiện được thì ghi rõ, và chỉ kiểm bằng fixture theo tài liệu Graph.
- [ ] **S0.5** `@microsoft.graph.downloadUrl`: host, thời hạn, có chứa `tempauth`; `/content` trả redirect về host nào.
- [ ] **S0.6** Site **tiếng Việt**: tên drive, path của `drive.webUrl`; xác nhận khớp theo path hoạt động (Q3).
- [ ] **S0.7** Trang site:
  - danh sách metadata;
  - `$expand=canvasLayout` từng trang;
  - ghi loại web part thực tế (text, standard, `searchablePlainTexts`);
  - trang tin tức; trang có bảng.
- [ ] **S0.8** URL `/personal/` (OneDrive của một người trong tenant thử): resolve drive, delta (Q10).
- [ ] **S0.9** Throttling: chỉ ghi nếu gặp 429/503 (header `Retry-After`). Không cố tình gây tải.
- [ ] **S0.10** Chạy lại luồng tương tự trên Onyx Cloud với site tiếng Việt và với credential certificate, để có bằng chứng baseline cho O2.

## Giai đoạn 1 — Credential Entra app

**Backend**
- [ ] Migration: kind `SHAREPOINT_APP` trong CHECK `ck_credentials_kind`; bảng `sharepoint_credentials` (design §5.1).
- [ ] Tách cipher AES-GCM dùng chung khỏi `GoogleDriveCredentialCipher` (Q7). Migrate caller Google trong cùng commit; dữ liệu Drive đã mã hóa vẫn giải được.
- [ ] Cấu hình key `memoryos.sharepoint.credential-encryption-key` và `credential-key-version` cho API và worker; thiếu thì SharePoint fail closed. Cập nhật runbook biến môi trường và tên key Infisical, không kèm giá trị.
- [ ] `gradle/libs.versions.toml`: thêm msal4j, pin version (Q2). Kiểm license và kích thước dependency.
- [ ] Core `io.memoryos.connector`:
  - port `SharePointProvider`;
  - `SharePointCredentialService` / `DefaultSharePointCredentialService`: tạo, sửa, xóa, liệt kê, test; kiểm với provider ngoài transaction;
  - `SharePointCertificate`: PKCS12 → PKCS8 + X.509, RSA ≥ 2048, chưa hết hạn, ≤ 16 KiB;
  - `JdbcSharePointCredentialRepository`;
  - `SharePointException` với mã `SOURCE_SHAREPOINT_*`.
- [ ] Provider bundle `provider/sharepoint`:
  - `MsalSharePointTokenSource`;
  - `RestSharePointProvider` (bước này chỉ có token + `/sites/root`), HTTP adapter có ngân sách, không redirect, header `User-Agent`;
  - phân loại AADSTS; `SharePointProviderProperties`; auto-configuration.
- [ ] API `SharePointCredentialController` + DTO trong `api/.../source/contract/`; OpenAPI; hey-api.

**Test**
- [ ] Unit:
  - PFX: hợp lệ, sai mật khẩu, không có key, nhiều key, RSA 1024, hết hạn, quá 16 KiB, không phải PKCS12;
  - GUID; phân loại AADSTS; `toString` đã che.
- [ ] Provider HTTP (JDK `HttpServer`, như `RestGoogleDriveProviderTest`):
  - token endpoint nhận `client_secret` và `client_assertion` (kiểm `x5t`, `aud`, `iss`/`sub`);
  - lỗi AADSTS 400/401; 403 trên `/sites/root`;
  - không theo redirect; timeout.
- [ ] PostgreSQL: mã hóa gắn AAD (đổi tenant/credential/purpose thì không giải được); revision/`If-Match`; cô lập Tenant; người quản lý theo phạm vi chỉ thấy credential của mình; xóa khi còn Source → conflict.
- [ ] Test giải mã dữ liệu Drive hiện có sau khi tách cipher.
- [ ] MVC/API: ma trận quyền; response không chứa secret hay key; body lỗi không lộ provider text; OpenAPI drift.

**Xong khi:** gate backend xanh. Chưa merge riêng giai đoạn này.

## Giai đoạn 2 — Source, xác minh phạm vi, refresh và prune thư viện

**Backend**
- [ ] Migration:
  - `SHAREPOINT` trong `ck_connectors_type`;
  - `sharepoint_sources`, `sharepoint_roots`, `sharepoint_exclusions`;
  - `sharepoint_selection_operations` (+ entries);
  - `sharepoint_sync_runs`, `sharepoint_items` (design §5.9).
- [ ] `SourceType.SHAREPOINT` và mọi switch exhaustive: summary, `SourcePermissions`, access resolver, provenance Search, cleanup, due scan.
- [ ] `SharePointUrl`: tiền tố share link, `/sites|/teams|/personal`, đoạn thư viện/thư mục, host theo cloud. `SharePointGlob` cho site và path.
- [ ] `SharePointSelectionPolicy` + endpoint policy.
- [ ] Tạo Source và `PUT …/scope` → `202` + receipt. Workload `SHAREPOINT_SELECTION_VALIDATION`:
  - stream v1; routing; config exhaustive;
  - `SelectionValidationProcessor` phân nhánh;
  - resolve site → drive theo path `webUrl` → folder;
  - activation transaction; supersede proposal cũ; gỡ item ngoài phạm vi mới; đánh dấu root mới cần refresh từ epoch.
- [ ] `DefaultSharePointSyncService`, lượt **refresh**:
  - cửa sổ `[cuối lượt thành công trước − 30 phút, bắt đầu lượt]` (lượt đầu và root mới từ epoch), lượt lỗi giữ nguyên mốc;
  - delta timestamp token cho thư viện, BFS `children` cho thư mục;
  - lọc theo cửa sổ; bỏ thư mục và `deleted`; bỏ trùng; 410 → `Location`;
  - `excludedPaths` dựa trên đường dẫn dựng từ parent id;
  - `Retry-After` → `next_dispatch_at`; checkpoint theo drive/site; tối đa 16 bước mỗi lần giao.
  - `SourceSyncProcessor` phân nhánh theo `SourceType`.
- [ ] Lượt **prune**:
  - liệt kê đầy đủ chỉ metadata (delta không token, BFS, danh sách site);
  - hoàn tất toàn phạm vi mới tạo `REMOVE_ITEM`; không hoàn tất thì `SOURCE_SHAREPOINT_PRUNE_INCOMPLETE` và không gỡ gì;
  - due scan ưu tiên prune khi đến hạn; `pruneIntervalHours = 0` thì không bao giờ prune.
- [ ] Acquisition:
  - `downloadUrl` kiểm host, fallback `/content` một redirect, stream có giới hạn 100 MiB;
  - `ObjectWriteService` stage/adopt; INDEX attempt có `source_sync_attempt_id`;
  - counter lượt chạy; `skipped` có mã.
- [ ] Run history: loại lượt REFRESH/PRUNE và cửa sổ trong projection (nullable cho Drive/FILE).
- [ ] Truy cập `PUBLIC`/`PRIVATE` theo contract MEM-105: nhánh `SHAREPOINT` trong luật SQL dùng chung của `JdbcSourceDocumentRepository`; `SourceAccessChanged`.
- [ ] Đọc cấu hình; phân trang root; `schedule` (`syncIntervalMinutes`, `pruneIntervalHours`); `sync`; pause/resume theo contract hiện hành.
- [ ] Thay secret/certificate fence Source (credential revision). Xóa Source dọn theo thứ tự phụ thuộc.
- [ ] Metric `memoryos.connector.provider.request`; log `event`.

**Test**
- [ ] Unit:
  - `SharePointUrl` với các loại URL thật (share link `/:f:/r/`, `/teams/`, `/personal/`, thư mục lồng, `%20`, host sai tenant, `http://`);
  - glob;
  - tính cửa sổ: lượt đầu, sau lượt thành công, sau lượt lỗi, root mới;
  - lọc theo cửa sổ và bỏ trùng; dựng đường dẫn cho `excludedPaths`;
  - so phiên bản hash/eTag; phân loại lỗi.
- [ ] Provider HTTP:
  - delta có `token` timestamp và không có token; `nextLink`; bản ghi `deleted` bị bỏ;
  - BFS `children` nhiều trang;
  - 410 kèm `Location`;
  - 429/503 `Retry-After`;
  - `downloadUrl` khác host → từ chối; redirect `/content` hợp lệ và không hợp lệ;
  - vượt 100 MiB giữa stream;
  - `getAllSites` phân trang và 403;
  - drive khớp theo path với tên tiếng Việt.
- [ ] PostgreSQL:
  - activation nguyên tử; thất bại không tạo Source; supersede;
  - cửa sổ lưu và đọc lại đúng;
  - prune hoàn tất gỡ item vắng mặt; prune không hoàn tất (một site 403) không gỡ gì;
  - prune tắt; refresh không bao giờ gỡ;
  - fence khi đổi phạm vi hoặc credential;
  - counter idempotent khi giao lặp; cô lập Tenant.
- [ ] Full context worker: provider giả + PostgreSQL/Redis/MinIO fixture hiện có.
  - Tạo → refresh → Document → Search thấy tài liệu với PUBLIC và PRIVATE.
  - Refresh không có thay đổi → "No changes".
  - Xóa file trên provider giả → refresh vẫn giữ; prune gỡ.
- [ ] MVC/API: ma trận quyền (design §5.8), `If-Match`, `202` + receipt recovery, validation interval/prune interval, OpenAPI drift.

## Giai đoạn 3 — Trang site

- [ ] Input format `SHAREPOINT_PAGE` trong `SourceInputDescriptor`.
- [ ] Refresh: liệt kê metadata trang → lấy canvas cho trang có `lastModifiedDateTime` trong cửa sổ → snapshot JSON. Prune: gỡ trang không còn.
- [ ] `SharePointPageSourceContentExtractor` (jsoup) đăng ký trong `SourceContentExtractorRouter`.
- [ ] Bật/tắt `includePages` đi qua cập nhật phạm vi và fence.
- [ ] **Test:**
  - fixture canvas từ S0.7 (đã che): heading, list, table, standard web part, trang rỗng, canvas lỗi;
  - provider 404/400;
  - PostgreSQL prune trang;
  - full context: trang xuất hiện trong Search với citation `webUrl`.

## Giai đoạn 4 — Giao diện (shadcn)

- [ ] Đối chiếu component đã có sau MEM-106. Cài phần còn thiếu bằng `pnpm exec shadcn add` và chuyển về token/`ui()`; ghi deviation vào [ui-references §8](ui-references.md#8-control-shadcn).
- [ ] Catalog: thêm SharePoint vào `source-provider-catalog.ts`; provider mark SharePoint (kiểm quyền dùng logo).
- [ ] Route `_authenticated.admin.sources.new.sharepoint.tsx`; trang tạo với setup rail Credential → Phạm vi → Truy cập → Xem lại.
- [ ] Credential:
  - danh sách/picker theo MEM-106 #4a. Dùng lại component credential của Drive nếu MEM-106 đã tổng quát hóa; nếu chưa, tách phần chung (lúc này có hai caller);
  - hộp thoại `RadioGroup` card, secret, upload `.pfx` + mật khẩu, tóm tắt certificate;
  - hướng dẫn Entra (bước đánh số, bảng quyền, nút copy); Test; sửa; xóa bằng `ConfirmDialog`.
  - Secret và PFX không được vào biến mutation của React Query hay browser storage; xóa khỏi state khi submit hoặc unmount (như JSON OAuth của Drive).
- [ ] Phạm vi:
  - `RadioGroup` All sites / Site cụ thể;
  - `Textarea` URL có lỗi theo từng dòng;
  - Nâng cao: hai `Switch` include và hai `Textarea` loại trừ.
- [ ] Truy cập Public/Private + Group: dùng lại bước của MEM-106.
- [ ] Xem lại:
  - interval mặc định 30 phút; prune interval mặc định 168 giờ, 0 là tắt, kèm help giải thích tài liệu đã xóa còn tìm thấy tới lượt prune;
  - tạo → receipt đang chờ → trang chi tiết.
- [ ] Chi tiết Source:
  - panel Đồng bộ: interval, prune interval, lượt refresh/prune gần nhất, pause, sync now;
  - Phạm vi: danh sách root, sửa trong `Sheet`;
  - Credential: phương thức, hạn certificate;
  - `Alert` theo mã lỗi;
  - run history hiện loại lượt.
- [ ] Search/Chat: icon SharePoint và deep link trong citation.
- [ ] Nhãn tiếng Anh viết trong code, bản dịch vi trong catalog; `pnpm check:i18n`.
- [ ] **Test:**
  - Vitest:
    - validate URL theo dòng;
    - đổi phương thức xóa secret của phương thức kia;
    - tóm tắt certificate;
    - validate interval/prune interval;
    - render mã lỗi → copy đã dịch;
    - control bị ẩn khi `can()` từ chối.
  - Playwright `web/tests/e2e/sharepoint-source-setup.spec.ts`, mock API bằng `page.route`:
    - tạo credential (test thất bại rồi thành công);
    - tạo Source, receipt đang chờ, chi tiết;
    - alert thiếu quyền, throttling, prune không hoàn tất;
    - viewport 390px; chỉ dùng bàn phím.
  - Ảnh chụp desktop/mobile, sáng/tối qua Orca, đặt cạnh tham chiếu Mobbin (theo cách nghiệm thu của MEM-106).

## Giai đoạn 5 — Hợp nhất tài liệu và nghiệm thu

- [ ] `docs/specs/connector.md`: phần SharePoint (credential, phạm vi, refresh/prune, lỗi, truy cập, hướng dẫn thiết lập Entra với bảng quyền).
- [ ] `docs/specs/ingestion.md` (workload, stream, ngân sách); `docs/specs/search.md` (access); `docs/specs/document.md` nếu format trang ảnh hưởng artifact.
- [ ] `docs/tests/connector.md`: ma trận SharePoint có ngày và test tương ứng. `ARCHITECTURE.md`: provider `sharepoint`, workload mới.
- [ ] Nghiệm thu thật trên tenant (trước 15/10/2026, hoặc tenant thay thế):
  - client secret và certificate;
  - site tiếng Anh và tiếng Việt;
  - thêm, sửa, đổi tên ở refresh; xóa ở prune (prune interval đặt 1 giờ khi nghiệm thu);
  - trang site; `/personal/`; throttling nếu gặp;
  - Search/Chat trả citation mở đúng SharePoint.
  - Ghi bằng chứng đã che vào `verification.md`.
- [ ] PR merge xong: chuyển increment sang `completed/`, đối chiếu roadmap và Linear.

## Ma trận kiểm chứng dự kiến

| Contract | Boundary | Test dự kiến |
| --- | --- | --- |
| PFX, GUID, URL, glob, cửa sổ refresh, lọc, phân loại lỗi | Unit | `SharePointCertificateTest`, `SharePointUrlTest`, `SharePointRefreshWindowTest` |
| Token, delta timestamp, BFS, 410, `Retry-After`, host download, redirect, giới hạn byte | Provider HTTP giả | `RestSharePointProviderTest` |
| Mã hóa, revision, activation, cửa sổ, prune hoàn tất/không hoàn tất, fence, counter, cô lập Tenant | PostgreSQL + Flyway | `PostgresSharePointCredentialTest`, `PostgresSharePointSelectionTest`, `PostgresSharePointSyncTest` |
| Workload, routing, ingest → Document → Search, xóa sau prune | Full context worker | `SharePointSourceIngestionTest` |
| Quyền HTTP, `If-Match`, `202`, problem details, OpenAPI | MVC/API | `SharePointSourceApiIntegrationTest`, `OpenApiContractTest` |
| Reader trang | Unit với fixture | `SharePointPageExtractionTest` |
| Luồng tạo, lỗi, responsive, bàn phím | Vitest + Playwright | `sharepoint-*.test.tsx`, `sharepoint-source-setup.spec.ts` |
| Kiến trúc | ArchUnit/Modulith | `ProviderDependencyRulesTest`, `ModulithArchitectureTest` (mở rộng) |

## Spike ledger

Chưa chạy.
