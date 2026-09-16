# Bàn giao Giai đoạn 4 — giao diện SharePoint (MEM-126)

Ghi ngày 16/09/2026, giữa chừng Giai đoạn 4. Tài liệu này là trạng thái bàn giao cho phiên sau;
khi giai đoạn hoàn tất thì gộp phần còn giá trị vào [plan.md](plan.md) và xoá tệp này.

## Bối cảnh

- Worktree: `C:/Users/Gigabyte/orca/workspaces/MemoryOS/Sharepoint`, nhánh
  `anhnd05122004/mem-126-sharepoint-connector-ket-noi-sharepoint-online-theo-logic`, **chưa push**.
- Giai đoạn 1–3 và 5 (tài liệu) đã xong, `./gradlew clean check` toàn repo đã xanh trước khi bắt đầu
  Giai đoạn 4.
- Người dùng yêu cầu làm Giai đoạn 4 **ngay, không chờ MEM-106**; kế hoạch đã dự phòng lối này
  ("nếu MEM-106 chưa tổng quát hoá thì tự tách phần chung của credential Drive").

### Hai việc nền đã làm trong phiên này

1. **Đánh số lại migration** (commit `b23ede27`): `main` đã dùng tới V68, nên SharePoint chuyển
   `V64/V65` → `V69__add_sharepoint_credentials.sql`, `V70__add_sharepoint_sources.sql`.
   Không có tài liệu/mã nào tham chiếu số hiệu.
2. **Nhánh chạy chung** `anhnd` ở `D:\MemoryOS` đã merge cả MEM-126 lẫn MEM-91 (voice) để chạy một
   app duy nhất: commit merge `3b630825` và `35019ee2`. Trong nhánh chạy đó, migration của voice
   được đổi thành `V71/V72` **chỉ tại chỗ** — nhánh MEM-91 vẫn còn `V63/V64` và **sẽ phải tự đánh số
   lại** trước khi mở PR, vì `main` đã chiếm V63/V64.
   Backend biên dịch được và `pnpm typecheck` sạch trên nhánh chạy đó.

## Đã viết xong (mã mới, trong `web/src/features/sources/`)

| Tệp | Nội dung |
| --- | --- |
| `sharepoint-scope.ts` | Bản TS của `SharePointUrl.java`: parse địa chỉ (sharing link `:f:/r`, `/sites|/teams|/personal`, view `Forms` → LIBRARY, giải mã segment), `coversSharePointAddress`, lỗi theo từng dòng, `sharePointScopeRequest`, `sharePointScopeError`, `sharePointScheduleError`, hằng số mặc định 30 phút / 168 giờ |
| `sharepoint-scope-fields.tsx` | RadioGroup All sites / Specific, Textarea địa chỉ + lỗi theo dòng, mục Advanced: hai `Switch` (documents, pages) và hai Textarea loại trừ |
| `sharepoint-schedule-fields.tsx` | Hai ô interval, help giải thích vì sao prune vẫn cần khi change log đã báo xoá |
| `sharepoint-credential-input.tsx` | Secret / `.pfx`: giá trị chỉ nằm trong `useRef`, `take()` trả về một lần rồi xoá, cleanup khi unmount — không vào React Query hay browser storage |
| `sharepoint-entra-guide.tsx` | Hướng dẫn Entra đánh số, bảng quyền (`Sites.Read.All`, `Files.Read.All`) có nút copy |
| `sharepoint-credential-section.tsx` | Bảng chọn credential + hộp thoại tạo/thay xác thực + Test / Rename / Replace authentication / Delete (`ConfirmDialog`, `If-Match`) |
| `create-sharepoint-source-page.tsx` | Rail 4 bước Credential → Content → Access → Review, receipt 202, khôi phục `requestId`, điều hướng sang trang chi tiết khi thao tác SUCCEEDED |
| `sharepoint-panel.tsx` | Panel trang chi tiết: tóm tắt interval, Đồng bộ (refresh status / pause / sync now), Credential, Phạm vi đã lưu + sửa, Lịch |
| `sharepoint-icon.tsx`, `sharepoint-setup-search.ts` | Mark sản phẩm và `validateSearch` cho route |
| `source-selection-operation.ts` | Hook chung cho receipt/khôi phục/poll; `google-drive-selection-operation.ts` giờ chỉ là vỏ bọc (khoá sessionStorage giữ nguyên `memoryos:drive-selection:…`) |
| `routes/_authenticated.admin.sources.new.sharepoint.tsx` | Route mới |

Đã sửa: `source-provider-catalog.ts` (thêm `SHAREPOINT`), `source-errors.ts` (toàn bộ mã
`SOURCE_SHAREPOINT_*` + ba loại mutation mới), `source-detail-page.tsx` (gắn `SharePointPanel` cho
`detail.type === "SHAREPOINT"`).

**Backend có đổi một chỗ**: `SharePointException.rejected(...)` giờ sinh **mã riêng cho từng lý do**
(`…_CREDENTIAL_SECRET_REJECTED`, `…_SECRET_EXPIRED`, `…_CERTIFICATE_UNKNOWN`, `…_DIRECTORY_UNKNOWN`,
`…_APPLICATION_UNKNOWN`, `…_CONSENT_REQUIRED`, và `…_CREDENTIAL_REJECTED` cho UNCLASSIFIED), kèm
`SharePointException.isCredentialRejection(code)`. Lý do: trình duyệt không đọc được `detail` tiếng
Anh của máy chủ, nên phải phân loại bằng mã để dịch được. Đã cập nhật `DefaultSharePointCredentialService`
và hai test (`PostgresSharePointCredentialTest`, `SharePointCredentialApiTest`).

## Việc còn lại, theo thứ tự nên làm

### 1. Sửa lỗi typecheck đang còn (đã chạy `npx tsc -b` trong `web/`)

- **Route chưa có trong `routeTree.gen.ts`** → các lỗi ở `create-sharepoint-source-page.tsx`
  dòng 61, 63, 160, 307 (`useSearch from`, `useNavigate from`, `navigate({ search: … })`).
  Chạy `pnpm build` hoặc `pnpm check:routes` trong `web/` để sinh lại route tree, lỗi sẽ hết.
- **`useSourceSelectionOperation` lệch TError**: options sinh ra của SharePoint dùng `unknown`, của
  Drive dùng `Error`. Sửa bằng cách thêm một generic lỗi và để suy luận:
  `useSourceSelectionOperation<Receipt extends SelectionReceipt, Key extends QueryKey, Err>` với
  `recover: (requestId: string) => UseQueryOptions<Receipt, Err, Receipt, Key>`.
- **`sharepoint-credential-section.tsx:205`**: `notify({ tone: … })` chỉ nhận `"error" | "info" | "success"`;
  đổi nhánh `"warning"` thành `"info"`.

### 2. i18n

Mọi chuỗi mới viết bằng tiếng Anh trong mã phải có khoá + bản dịch tiếng Việt trong
`web/src/i18n/app-translations.ts` (`englishUi`: khoá tiếng Anh → tiếng Việt; chuỗi giống nhau ở hai
ngôn ngữ thì thêm vào `unchanged`). Chạy `node scripts/i18n-audit.mjs --candidates` trong `web/` để
liệt kê chuỗi còn thiếu, rồi `pnpm check:i18n` để chốt.

### 3. Lịch sử lượt chạy

`SourceRun.runKind` đã có sẵn trong API (`REFRESH` / `PRUNE`). Cần hiện trong
`web/src/features/sources/source-run-history.tsx` (một cột hoặc một `StatusBadge` cạnh trigger).

### 4. Search/Chat

Biểu tượng SharePoint trong kết quả tìm kiếm và deep link trong trích dẫn:
`web/src/features/search/document-source-icon.tsx` và `provider-link.tsx`. Deep link còn chờ quyết
định "webUrl của item nằm ở đâu" (đã ghi là điểm mở trong `plan.md`); nếu chưa có webUrl thì chỉ làm
biểu tượng, đừng bịa liên kết.

### 5. Kiểm thử

- `sharepoint-scope.test.ts` (Vitest, thuần): sharing link, view `Forms`, `/personal/`, site tiếng
  Việt, phát hiện lồng nhau, khác host, trùng, quá giới hạn policy, validator lịch.
- `sharepoint-credential-input.test.tsx`: `take()` trả về một lần rồi xoá; unmount xoá ref; từ chối
  `.pfx` > 16 KiB; secret > 256 ký tự.
- `create-sharepoint-source-page.test.tsx` / `sharepoint-panel.test.tsx`: mock hey-api như các test
  Drive đang làm (`google-drive-panel.test.tsx` là mẫu gần nhất).
- E2E `web/tests/e2e/sharepoint-source-setup.spec.ts` theo mẫu các spec sẵn có.

### 6. Tài liệu và cổng kiểm tra

- Cập nhật `plan.md` (đánh dấu Giai đoạn 4), `docs/tests/connector.md` (thêm bảng chứng cứ trình
  duyệt), và ghi các deviation UI vào `ui-references.md §8`.
- Cổng: trong `web/` chạy `pnpm check`; toàn repo chạy `./gradlew clean check` từ gốc worktree.
- Ghi rõ trong `plan.md` rằng Giai đoạn 4 đã làm **trước** MEM-106, nên khi MEM-106 merge phải đối
  chiếu lại component credential/access và gỡ phần trùng.

## Những ràng buộc không được phá

- Secret và `.pfx` **không bao giờ** vào biến React Query, state sống sót sau submit, hay browser
  storage; xoá khi submit hoặc unmount.
- Không dán giá trị bí mật vào chat, log hay tài liệu; credential thật nằm ngoài repo tại
  `C:\Users\Gigabyte\.memoryos\sharepoint-spike.env`.
- Nhãn tiếng Anh viết trong mã, bản dịch nằm trong catalog; không hardcode tiếng Việt trong component.
- Mọi mutation vẫn phải gửi `X-MemoryOS-CSRF` và `If-Match` đúng revision.

## Việc của người dùng (không phải của agent)

- Thu hồi quyền tạm `Sites.ReadWrite.All` trên Entra; connector chỉ cần `Sites.Read.All`.
- Nghiệm thu tenant thật `memoryosvadan` trước 15/10/2026 (Giai đoạn 5).
