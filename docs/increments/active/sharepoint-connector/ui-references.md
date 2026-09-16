# MEM-126 — Tham chiếu giao diện SharePoint connector

Design: [design.md](design.md). Nghiên cứu qua Mobbin MCP ngày 16/09/2026, nền tảng web, theo yêu cầu dựng giao diện bằng component shadcn.

- **Vai trò các nguồn tham chiếu:** Onyx vẫn là baseline hành vi ([tham chiếu Onyx §9](onyx-sharepoint-reference.md#9-giao-diện)); Mobbin chỉ quyết định cách trình bày.
- **Quan hệ với MEM-106:** bản đồ màn Nguồn đã chọn trong [MEM-106](https://linear.app/memory-os/issue/MEM-106) (comment "Bản đồ toàn bộ màn hình Nguồn") được giữ nguyên. Tài liệu này chỉ bổ sung phần riêng của SharePoint.

Mobbin không trả kết quả cho Glean, Dust, Onyx, Fivetran, Segment, Airtable và Retool. Cũng không có màn nào vừa chọn "client secret hay certificate", vừa nhập danh sách URL trong một integration. Hai mẫu này được ghép từ các màn tương tự ở lĩnh vực khác.

## 1. Catalog chọn loại nguồn

| Sản phẩm | Màn hình | Điều dùng |
| --- | --- | --- |
| Langdock | [Integrations](https://mobbin.com/screens/1220a80f-e373-46cb-91b8-c03e048f275b) | SharePoint, OneDrive, Confluence đứng ngang hàng; card gồm logo, tên, mô tả một dòng, nút "Connect"; nguồn đã kết nối hiện chữ "Connected" |
| LangChain Fleet | [Integrations](https://mobbin.com/screens/c3ee2df7-4268-4daa-a45d-194770781ec6) | Nhóm theo hãng (Google / Microsoft); pill "Connected" và "Missing scopes" cho trạng thái thiếu quyền |
| StackAI | [Connections](https://mobbin.com/screens/9d64d068-a34a-4916-9b12-26497fd6248b) | Số kết nối trên mỗi provider ("3 connectors"), hợp với một provider có nhiều Source |
| Vanta | [Integrations](https://mobbin.com/screens/0bfbcb9b-e013-4fc0-b8f1-ec17b1f70996) | Tab Connected/Available có số đếm |

MEM-106 màn #2 chọn Steep làm mẫu chính. MemoryOS chỉ có FILE, Google Drive và SharePoint, nên không cần rail danh mục hay ô tìm kiếm lớn như Langdock (MEM-106 đã loại catalog kiểu Perplexity/Mistral).

## 2. Credential, phương thức xác thực, hướng dẫn Entra

| Sản phẩm | Màn hình | Điều dùng |
| --- | --- | --- |
| incident.io | [Webhook setup](https://mobbin.com/screens/b706be80-a72c-41bd-a4a1-ee4baf5711a7) | Bước đánh số trong console của provider, nhãn UI in đậm, giá trị cần dán hiện trong ô read-only mono có nút copy |
| Google Workspace | [Prerequisites flow](https://mobbin.com/flows/900c6a0e-053b-4267-8ea9-bace24ec113d) | Khối "Prerequisites" trước form: bước đánh số, "What you'll need", callout thông tin, link "Learn more" |
| Steep | [Test and save](https://mobbin.com/screens/c70f80de-5c92-4044-b3b5-b44b819647b9) | Trường credential xếp dọc, secret bị che; khối "Test and save" chuyển thành panel lỗi có nút Retry |
| Oyster | [API credentials](https://mobbin.com/screens/7caec408-5ca7-47c0-b032-08eeb1139295) | Helper text báo secret sẽ không hiện lại |
| Remote | [Method choice](https://mobbin.com/screens/2b72499b-6ab8-4dc9-879e-2e05100a0ec1) | Radio dạng card đặt cạnh nhau, có tiêu đề và mô tả; trường phía dưới đổi theo lựa chọn |
| Square | [Option cards](https://mobbin.com/screens/08299f22-befc-4491-a1c7-cd46d4398501) | Card được chọn có viền nổi bật |
| Faire | [Upload under option](https://mobbin.com/screens/05b86dcf-5626-46bb-a12f-970499539d86) | Vùng upload xuất hiện ngay dưới lựa chọn đang chọn |

MEM-106 màn #4a (credential Drive) dùng n8n/Tines cho danh sách credential. SharePoint dùng lại đúng mẫu danh sách đó; phần mới chỉ là hộp thoại tạo credential ở trên.

## 3. Luồng tạo nguồn nhiều bước

| Sản phẩm | Màn hình | Điều dùng |
| --- | --- | --- |
| WRITER | [Adding a connector](https://mobbin.com/flows/29fb8ed6-9bdc-4281-8608-6de500031d7e) | Bước "Who has access by default?" gồm card lựa chọn và Cancel/Previous/Next; kết thúc ở hàng trạng thái trong bảng cùng toast |
| Sana AI | [Setting up shared data sources](https://mobbin.com/flows/f342a720-9d29-4f26-a736-4f374b44f562) | Bước cuối đặt tên và báo đồng bộ tiếp tục chạy nền (chip "Syncing") |
| AWS Amplify | [Review](https://mobbin.com/screens/9af9f1a6-fd6e-4af7-8790-b7ae6e7c7703) | Trang Review có một card cho mỗi bước, nút "Edit" quay lại bước đó, giá trị hiện dạng key/value |
| Workable | [Review](https://mobbin.com/screens/41e79574-116d-49ed-a7ca-a1756dd9271c) | Previous/Done ở footer cố định |

MemoryOS đã có setup rail cho Google Drive (Credential → Connector), và MEM-106 tách thành Credential → Phạm vi → Truy cập → Group. SharePoint theo MEM-106, thêm bước Xem lại.

## 4. Phạm vi: tất cả site hoặc danh sách URL

| Sản phẩm | Màn hình | Điều dùng |
| --- | --- | --- |
| Pipedrive | [Synced folders](https://mobbin.com/screens/59c26881-d362-49d0-97da-668b55349e53) | Radio "đồng bộ tất cả" / "chọn cụ thể": mẫu rõ nhất cho All sites / Specific |
| Klaviyo | [Sync settings](https://mobbin.com/screens/f6d3a49d-ff1d-43d4-9c3c-e05136700874) | Radio có mô tả, kèm callout giải thích hệ quả (MEM-106 cũng chọn cho bước truy cập) |
| Uber Eats | [One item per line](https://mobbin.com/screens/950785d6-ae42-48cd-aebe-81f1a0a5b950) | Textarea "mỗi dòng một mục" có helper text |
| Airwallex | [Website list](https://mobbin.com/screens/98049604-a19c-4d98-a5d7-bd53f3f980f9) | Mỗi URL một dòng, có nút xóa và "+ Add website" |
| Graphite | [Synced repositories](https://mobbin.com/screens/80ac843f-caa5-471f-bf14-0375273d7ccc) | Bộ đếm "1 of 30 synced", mục đã chọn ghim lên nhóm riêng |

## 5. Danh sách nguồn và chi tiết

Giữ mẫu MEM-106 cho các màn #1, #5–#13:
- danh sách: Sana AI, WRITER;
- chi tiết: Customer.io kết hợp panel phải của Sana AI;
- lượt index: Relevance AI; chi tiết lỗi: OpenAI Batch;
- lịch sử đồng bộ: n8n; chi tiết lượt đồng bộ: Databricks.

Bổ sung cho SharePoint:

| Sản phẩm | Màn hình | Điều dùng |
| --- | --- | --- |
| Sana AI | [Integration detail](https://mobbin.com/screens/cb148035-69d7-4c1b-ada8-a845fd5ef540) | Chip đếm theo lý do: "Processing issue · Too large to process · Unsupported format", khớp mã bỏ qua `TOO_LARGE`/`UNSUPPORTED_FORMAT` |
| Databricks | [Job runs](https://mobbin.com/screens/1bd9f80c-3c2d-46ba-bd06-b6177a78ebdb) | "Run now" cạnh tên, bảng lượt chạy có Duration và Error code; dùng cột loại lượt Refresh/Prune |
| Deel | [Slack integration](https://mobbin.com/screens/7b7a9f07-cad9-4c17-807a-d9e2df6f45ac) | "Connected" và "Synced: <time>" dưới tiêu đề; banner vàng cho lỗi một phần, kèm nút xử lý |

## 6. Trạng thái lỗi, đang xử lý, rỗng

| Sản phẩm | Màn hình | Điều dùng |
| --- | --- | --- |
| Klaviyo | [Credentials expired](https://mobbin.com/screens/d093ed4b-a5c5-464e-8e65-4628ef3939a3) | Badge "Action Required" ở header cùng alert đỏ "credentials have expired… to resume syncing" và nút xác thực lại: mẫu cho secret/certificate hết hạn |
| Customer.io | [Needs attention](https://mobbin.com/screens/87bdb14a-dc9d-49db-87e5-7a9dbdf63737) | Chip vàng "Needs attention" kèm banner có hành động ngay tại chỗ: mẫu cho thiếu quyền Graph và prune không hoàn tất |
| Steep | [Connection failed](https://mobbin.com/screens/c70f80de-5c92-4044-b3b5-b44b819647b9) | Panel lỗi dưới form, mã lỗi kỹ thuật trong khối mono, nút Retry |
| Contractbook | [Importing banner](https://mobbin.com/screens/f4654d56-94ee-455f-93a7-afd17b24b2ce) | Banner "đang nhập, sẽ báo khi xong" cho lần index đầu (MEM-106 #11) |
| Cursor | [Connecting](https://mobbin.com/screens/893ffaa1-74b0-48d5-8f16-a40a616b80d6) | Card nhỏ có spinner và dòng giải thích khi đang kiểm tra kết nối |
| Fabric | [First connection](https://mobbin.com/screens/a4f06792-edb8-4ea3-8c04-f99a2f771acc) | Trạng thái rỗng có logo provider và một CTA chính |

## 7. Quyết định trình bày

- **Trang riêng, không dùng dialog cho luồng tạo nguồn.** Route `/admin/sources/new/sharepoint` dùng setup rail hiện có, gồm các bước Credential → Phạm vi → Truy cập → Xem lại. Luồng SharePoint dài hơn Onyx (hướng dẫn Entra, danh sách URL, loại trừ) nên không hợp dialog như WRITER.
- **Hộp thoại credential:**
  - `RadioGroup` dạng card "Client secret" / "Certificate", mỗi card có mô tả. Đây là sửa lỗi O12, vì Onyx không hiện mô tả.
    - Client secret: "Dễ thiết lập; secret có hạn và cần thay định kỳ."
    - Certificate: "Không truyền secret dạng chuỗi; upload `.pfx`, MemoryOS chỉ giữ key đã mã hóa."
  - Đổi phương thức sẽ xóa giá trị bí mật của phương thức kia, như Onyx.
  - Certificate: `Input type=file` nhận `.pfx` cùng mật khẩu. Tải lên xong hiện thumbprint và ngày hết hạn, không hiện nội dung key.
- **Hướng dẫn Entra theo incident.io và Google Workspace:**
  - `Collapsible` "Thiết lập trong Microsoft Entra", mở mặc định khi chưa có credential nào.
  - Bước đánh số: tạo app registration single-tenant; thêm quyền Microsoft Graph Application; admin consent; tạo secret hoặc upload certificate; copy Application ID và Directory ID.
  - Bảng quyền: `Sites.Read.All` khi dùng "Tất cả site"; hoặc `Sites.Selected` và cấp `read` cho từng site. Tên quyền nằm trong ô read-only có nút copy.
  - Ghi chú tenant phải có gói Microsoft 365 (lỗi consent "không có subscription hoặc service principal").
  - Link "Mở Microsoft Entra admin center".
- **Test trước khi lưu, theo Steep:**
  - Nút "Kiểm tra kết nối" nằm cạnh "Lưu"; lưu cũng kiểm tra luôn.
  - Kết quả hiện bằng `Alert` trong hộp thoại; mã AADSTS nằm trong `Collapsible` "Chi tiết kỹ thuật".
- **Phạm vi, theo Pipedrive và Uber Eats:**
  - `RadioGroup` "Tất cả site mà app đọc được" (ghi rõ cần `Sites.Read.All`) / "Site cụ thể".
  - "Site cụ thể" dùng `Textarea`, mỗi dòng một URL. Lỗi được báo theo từng dòng, có số URL hợp lệ. Không dựng trình duyệt cây site vì Onyx không có.
  - `Collapsible` "Nâng cao":
    - `Switch` "Index tài liệu thư viện";
    - `Switch` "Index trang site (.aspx)";
    - hai `Textarea` cho site và đường dẫn loại trừ.
  - Validation "phải bật ít nhất một" giữ copy của Onyx.
- **Truy cập:** `RadioGroup` dạng card Public / Private, theo MEM-106 #4c, cộng bước Group. Không hiện Auto Sync (ngoài phạm vi MEM-126).
- **Xem lại, theo AWS Amplify:**
  - Một `Card` cho mỗi bước, có nút "Sửa".
  - "Tự động đồng bộ mỗi (phút)", mặc định 30.
  - "Dọn tài liệu đã xóa mỗi (giờ)", mặc định 168, 0 là tắt. `HelpPopover` giải thích tài liệu đã xóa trên SharePoint vẫn tìm thấy tới lượt dọn kế tiếp.
  - Nút chính "Tạo nguồn và bắt đầu đồng bộ". Sau `202`, giữ receipt đang chờ như Drive.
- **Chi tiết Source:** bố cục MEM-106, thêm:
  - **Đồng bộ:** interval và prune interval (sửa tại chỗ như Automatic interval của Drive), lượt refresh/prune gần nhất.
  - **Phạm vi:** danh sách root đã lưu, icon site/thư viện/thư mục. Sửa phạm vi mở `Sheet` chứa cùng editor của bước tạo.
  - **Credential:** phương thức; `StatusBadge` cảnh báo khi certificate còn dưới 30 ngày hoặc đã hết hạn.
  - **Alert theo mã lỗi:**
    - secret/certificate sai hoặc hết hạn → "Cập nhật credential";
    - thiếu quyền → tên quyền kèm nút copy và link Entra;
    - prune không hoàn tất → lỗi đi kèm và thông báo chưa gỡ tài liệu nào;
    - bị throttling → thông tin, không phải lỗi.
- **Không chọn:**
  - xác nhận xóa bằng cách gõ tên (Tailscale, Dub), vì `ConfirmDialog` của MemoryOS đã có contract riêng;
  - trình duyệt cây site (Mistral/Relevance), vì chưa có trong Onyx;
  - toast Sonner, vì MemoryOS có notification provider Radix.

## 8. Control shadcn

Registry: `web/components.json`, style `radix-nova`, Lucide. Tuân thủ [shadcn/ui registry](../../../conventions.md#shadcnui-registry) và [frontend interaction contracts](../../../conventions.md#frontend-interaction-contracts).

**Đã có trên `main`, dùng lại:** `Accordion`, `Badge`, `Button`, `Card`, `Checkbox`, `Collapsible`, `Command`, `ConfirmDialog`, `Empty`, `HelpPopover`, `IconButton`, `Input`, `Item`, `Label`, `Popover`, `RadioGroup`, `Select`, `Separator`, `SettingsLayout`, `Skeleton`, `StatusBadge`, `Switch`, `Table`, `TablePagination`, `Tabs`, `TextButton`, `Tooltip`. Nhánh MEM-91 thêm `Dialog` và `Slider`.

**Dự kiến có từ MEM-106:** `Sheet`, `DropdownMenu`, `Breadcrumb`, `Alert`, `Progress`, `ScrollArea`, `Textarea`, `Field`, `ToggleGroup`, `Pagination`. Khi bắt đầu giai đoạn 4, component nào vẫn thiếu thì cài bằng `pnpm exec shadcn add <component>` trong `web/`. Sau đó:
- chuyển về token MemoryOS và contract `ui()`;
- giữ tên export, `data-slot` và phần truy cập của Radix;
- ghi mọi thay đổi so với bản gốc vào mục này.

**Ánh xạ khi đọc gợi ý Mobbin/shadcn:**

| Gợi ý | MemoryOS |
| --- | --- |
| `Button variant="outline"` | `Button prominence="secondary"` (tone/prominence/size) |
| Nút chỉ có icon | `IconButton` với tên truy cập đã dịch |
| `Badge` trạng thái | `StatusBadge` |
| `AlertDialog` xóa | `ConfirmDialog` |
| `Sonner` | notification provider hiện có (`action-notifications`) |
| `InputGroup` + nút hiện secret | `Input` + `IconButton`; chỉ cài `input-group` nếu MEM-106 đã cài |
| Stepper | setup rail hiện có (`source-setup-steps`), không tự viết stepper mới |

Không viết raw `<input type="checkbox|radio">`, `<table>`, `<details>` hoặc tooltip bằng `title=` trong feature code.

**Đã làm (Giai đoạn 4, trước MEM-106):** mọi control dùng đúng primitive trong bảng "Đã có trên
main" — `RadioGroup` cho chọn credential và scope mode, `Switch` cho include documents/pages,
`Table` cho danh sách credential, `ConfirmDialog` cho xoá, `StatusBadge` cho trạng thái,
`Collapsible` cho mục Advanced. Không cài component mới nào: `Textarea` dùng `inputVariants()`
trên `<textarea>` thô như Drive đang làm; sửa phạm vi nằm inline trong panel thay vì `Sheet`
(khớp pattern panel Drive hiện có). `useSourceSelectionOperation` là hook chung trích từ Drive;
khi MEM-106 merge cần đối chiếu lại credential/access component.
