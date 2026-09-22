# MEM-122 — Nghiên cứu: Chat sinh HTML để làm slide

Trạng thái: research, đã có quyết định. **Phương án D được chọn và đã triển khai ngày 2026-09-22** theo yêu cầu của chủ sở hữu: `render-deck plan.json deck.html` xuất chính deck plan đó ra một file HTML tự chứa bằng template `memoryos_deck/web.py` — app sở hữu toàn bộ markup, model không viết một byte HTML nào, và mọi nội dung đều được escape. Phương án B (render HTML của model trong app) vẫn bị bác vì lý do ở mục 4; file HTML đi theo phương án A về vòng đời (là `chat_file_artifact`, tải về mở bằng trình duyệt), nên preview trong app vẫn hiện mã nguồn chứ không chiếu được — muốn chiếu thì dùng bản `.pptx` kèm theo. Phần còn lại của tài liệu giữ nguyên làm hồ sơ quyết định.

## 1. Câu hỏi và phạm vi

Yêu cầu: *"Chat sinh HTML để làm slide nhanh gọn khi cần trước buổi họp; đưa context lên là được."*

Tách thành ba việc tách biệt, vì chúng có chi phí rất khác nhau:

1. **Đưa context lên** — đính kèm tài liệu hoặc trỏ vào Source đã index.
2. **Sinh bộ slide** — model tạo ra nội dung và bố cục.
3. **Dùng được trước buổi họp** — xem lại, sửa, chiếu, mang vào cuộc họp.

Phạm vi nghiên cứu: định dạng HTML cho bước 2 và 3. Không nghiên cứu lại bước 1 (đã chạy), không đề xuất capability mới, không đụng Search.

## 2. Hiện trạng: phần lớn đã chạy hôm nay

| Việc | Đã có | Bằng chứng |
| --- | --- | --- |
| Đưa context | Attachments của lượt (≤25 file / 100 MiB) và file gốc mà `search_knowledge` tìm được đều được stage vào sandbox | `docs/specs/chat.md:326-331`; `docs/increments/completed/mem-110-memoryos-interpreter/design.md:98,112` |
| Sinh file | `run_python` trong executor có `python-pptx`, `reportlab`, `fpdf2`, `jinja2`, `markdown`, LibreOffice, poppler | `mem-110/design.md:23,39`; `interpreter/executor/Dockerfile:28-34` |
| **HTML là media type hợp lệ** | `html → text/html` trong bảng phần mở rộng của tool | `core/src/main/java/io/memoryos/chat/tools/RunPythonTool.java:57` |
| Lưu và phục vụ | File sinh ra thành `chat_file_artifact` (V71), `file_link = /api/chat/file-artifacts/{id}/content` | `RunPythonTool.java:155-163`; `V71__chat_interpreter.sql:9-20` |
| Thẻ tải trong câu trả lời, thư viện, panel theo phiên, dọn byte | `ChatGeneratedFiles`, `GET /api/chat/library` (+ `sessionId`), sweep `memoryos-chat-artifact-cleanup-v1` | `web/src/features/chat/chat-generated-files.tsx:73-77`; `api/.../ChatLibraryController.java:84-105`; `worker/.../ControlPlaneConfiguration.java:134-138` |
| Bộ slide PPTX xem ngay trong app | `pptx-to-pdf` chạy trong executor, PDF cache theo V73, hiển thị bằng pdf.js | `core/.../interpreter/PresentationPreviewService.java:19-48`; `V73__chat_file_artifact_pdf_preview.sql:2-9` |

**Kết luận hiện trạng:** hôm nay model **đã sinh được `deck.html`** và file đó được lưu, liệt kê, tải về, dọn đúng vòng đời. Thứ duy nhất không có là **xem nó trong ứng dụng**: `text/html` không nằm trong allowlist inline nên route trả `Content-Disposition: attachment` dưới `nosniff` (`api/.../ChatFileArtifactController.java:36-37,116-121`), và modal preview xếp `.html` vào nhóm *code* nên chỉ hiện mã nguồn tô màu (`web/src/features/chat/chat-file-preview.ts:68,81-83`).

## 3. Khoảng trống thật sự

1. **Không xem/chiếu được trong app** — phải tải về rồi mở bằng trình duyệt ngoài.
2. **Không có bảo đảm bố cục** — đây đúng là vấn đề MEM-122 mô tả (`design.md:9,19`): model viết layout mù, chữ tràn slide mà nó không nhìn thấy được. HTML **không** giải quyết việc này; nó chỉ đổi nơi lỗi xuất hiện (tràn `div` thay vì tràn shape).
3. **HTML không phải thứ mang vào phòng họp** — máy chiếu, file gửi kèm và quy trình nội bộ đều chạy trên PPTX/PDF.

## 4. Ràng buộc đã chốt, không được lặng lẽ mở lại

`docs/conventions.md:31` cấm mở lại quyết định đã bác hoặc âm thầm đưa đề xuất vào phạm vi. Bốn quyết định dưới đây chặn thẳng phương án "model viết HTML, app render":

- `docs/specs/chat.md:51` — *"Raw HTML is not executed; CSP is unchanged."* Mermaid chỉ được hiển thị dạng **ảnh SVG bất hoạt**.
- `docs/specs/chat.md:53` — `render_gui` **đã bị gỡ ngày 2026-09-19**: staging chỉ dùng nó ở 2/193 câu trả lời. Kho dữ liệu artifact còn lại là từ vựng đóng `Card/Heading/Text/Metric/Table/Row/Cell`, *"No HTML/JS, forms, URLs, event handlers or application actions."*
- `docs/increments/completed/mem-111-generated-file-preview/design.md` — *"Artifact HTML/JS tương tác chạy trong iframe"* nằm ngoài phạm vi; `:42` ghi rõ **"no new public URL"**; `:25` cố ý dùng pdf.js thay vì iframe.
- `docs/increments/active/mem-122-deck-authoring/design.md:61` — *"No new tool, endpoint, table, SSE event or frontend code. A rendered deck is an ordinary generated file."*; `:27` đã từ chối generator ngoài theo [ADR 0002](../../../decisions/0002-no-speculative-operational-surfaces.md).

### CSP hiện tại và một phép đo

CSP triển khai (`web/nginx.conf:80`):

```
default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self';
img-src 'self' data: blob:; media-src 'self' blob:;
connect-src 'self' ${MEMORYOS_OBJECT_STORAGE_CONNECT_SRC} ${MEMORYOS_SENTRY_CONNECT_SRC};
frame-ancestors 'none'; base-uri 'self'; form-action 'self'
```

Không có `frame-src`, nên nó rơi về `default-src 'self'`.

**Đo ngày 2026-09-22** (Chromium headless, trang `data:` mang thẻ meta CSP `default-src 'self'; script-src 'self'; style-src 'self'`, một `<iframe srcdoc>` chứa `<script>`, `<style>` và thuộc tính `style=`):

| Trang | `<script>` inline chạy | `<style>` inline áp dụng | `style=` áp dụng |
| --- | --- | --- | --- |
| Có CSP | không (`data-ran = null`) | không (`rgb(0,0,0)` thay vì xanh) | không (nền trong suốt thay vì đỏ) |
| Đối chứng, không CSP | có | có | có |

Tài liệu `srcdoc` **kế thừa CSP của trang cha**. Hệ quả: một file HTML tự chứa (inline CSS/JS — đúng thứ model hay sinh ra) **không thể hiển thị đúng** trong `srcdoc`, `blob:` hay `data:` iframe trên origin của ứng dụng. Muốn render nó phải có **origin khác**, đúng như Claude làm với `claudeusercontent.com` (iframe sandbox + CSP riêng).

## 5. Bốn phương án

### A. Giữ nguyên — HTML là một generated file tải về

Không sửa gì. Model viết `deck.html`, người dùng bấm tải, mở bằng trình duyệt.

- Chi phí: 0.
- Được: đúng chữ "chat sinh HTML", chạy ngay hôm nay.
- Mất: không xem trong app; không bảo đảm bố cục.

### B. Viewer HTML trong app (mô hình Claude artifacts)

Cần đủ **tất cả** các món sau, không món nào bỏ được:

1. Một **origin riêng** cho artifact (server block nginx + DNS + TLS riêng), vì CSP hiện tại chặn inline script/style (mục 4).
2. `frame-src <origin-artifact>` trên origin app, và `frame-ancestors <origin-app>` trên origin artifact (thay cho `frame-ancestors 'none'` + `X-Frame-Options: DENY`, `web/nginx.conf:80,83`).
3. `sandbox` **không có** `allow-same-origin` → frame chạy trên origin mờ → **không gửi được cookie phiên** → phải có **URL ký ngắn hạn** cho từng artifact. MemoryOS hiện không có khái niệm capability URL nào; mọi byte đều đọc qua phiên chủ sở hữu, và MEM-111 đã chốt *"no new public URL"*.
4. Một route phục vụ `text/html` **inline** — tức là bỏ `attachment` cho đúng một media type nguy hiểm nhất.
5. Sửa `docs/specs/chat.md:51` và ghi lại quyết định; `web/src/components/assistant-ui/elements/markdown-text.test.tsx:96-98` (khẳng định không có `script`/`iframe`) vẫn đúng cho *văn bản model*, nhưng hợp đồng spec thì đổi.

- Được: xem và chiếu ngay trong app; slide tương tác thật (animation, nav bằng phím).
- Mất: một origin công khai mới + một mô hình uỷ quyền mới, cho một tính năng chưa có người dùng nào yêu cầu quá một lần.

### C. HTML → PDF trong executor, xem bằng pdf.js sẵn có

Đúng khuôn `PresentationPreviewService`: upload file lên interpreter, chạy CLI chuyển đổi, cache PDF vào cột `preview_*` (V73), hiển thị bằng `DocumentPdfView`.

- Không đụng CSP, không origin mới, không URL ký.
- Cần một renderer chưa có trong image: **Chromium headless** (`--headless --print-to-pdf`, giữ được JS/CSS hiện đại) hoặc **WeasyPrint** (CSS phân trang, không JS). Image executor hiện đã 3.26 GB (`mem-110/design.md:42`).
- Được: có file mang vào họp (PDF) + preview sẵn có.
- Mất: image lớn hơn, thêm một đường chuyển đổi nữa bên cạnh `pptx-to-pdf` — hai đường làm cùng một việc.

### D. Không thêm HTML do model viết; xuất HTML từ **cùng deck plan** nếu cần

MEM-122 đã định nghĩa deck plan JSON đóng và `render-deck` dựng PPTX theo template cố định, chữ tràn thì **fail render** chứ không ra slide hỏng (`design.md:17-19`). Nếu sau này thật sự cần bản HTML, thêm `render-deck plan.json deck.html` dùng template Jinja2 **do app sở hữu**, nội dung escape — model không viết một byte markup nào. Bản HTML đó vẫn đi theo phương án A (tải về) trừ khi B được chấp nhận riêng.

### Bảng so sánh theo `docs/conventions.md:26`

| Mục | Nội dung |
| --- | --- |
| Yêu cầu hiện tại / lỗi đã chứng minh | Cần bộ slide nhanh trước cuộc họp từ tài liệu nội bộ. Lỗi đã chứng minh là **bố cục** (`mem-122/design.md:9`), không phải định dạng file. Chưa có phép đo nào cho thấy tải file HTML về là rào cản. |
| Hành vi tham chiếu | Onyx Craft render deck **trong sandbox** rồi hiển thị ảnh/PDF từng slide (`mem-111/design.md:18,34`). Claude phục vụ HTML trên **origin riêng** + iframe sandbox + CSP riêng. Cả hai đều **không** render HTML của model trên origin ứng dụng. |
| Khác biệt đề xuất | Render HTML do model viết ngay trong app. |
| Lợi ích cụ thể | Xem/chiếu ngay, slide tương tác. |
| Chi phí | Origin công khai mới; URL ký ngắn hạn (mô hình uỷ quyền mới); phục vụ `text/html` inline; đảo hợp đồng `chat.md:51`; vận hành thêm một host TLS. |
| Phương án baseline đơn giản hơn | A (tải về, chi phí 0) hoặc D + C (PDF, dùng lại preview có sẵn). |
| Bằng chứng cần để chọn | Tần suất dùng thực (so với ngưỡng `render_gui` 2/193 đã bị gỡ); kích thước image khi thêm Chromium/WeasyPrint, đo bằng `apt-get --assume-no` như MEM-110 đã đo font/LibreOffice; thời gian render trong ngân sách 60 s. |

**Theo `conventions.md:26`: lợi ích không bù được chi phí → giữ baseline.**

## 6. Khuyến nghị

1. **Không mở đường HTML-render-trong-app bây giờ (bác B).** Nó đòi một origin công khai mới và một mô hình uỷ quyền mới để đảo một quyết định bảo mật vừa chốt cách đây ba ngày, cho lợi ích mà PPTX + preview PDF đã cấp gần hết.
2. **Đường "nhanh gọn trước buổi họp" hiện tại là MEM-122 `render-deck`, không phải HTML.** Đã có: stage tài liệu → `search_knowledge` → `run_python` → `chat_file_artifact` → preview PDF → `/library`. Phần còn thiếu đúng bằng `plan.md` Phase 1 (`theme/plan/render/inspect`, hai CLI, guidance, test).
3. **Phần "đưa context lên là được" giải quyết bằng cấu hình, không bằng code**: một Agent (MEM-119) với task prompt "dựng slide họp từ tài liệu đính kèm" + prompt shortcut, bật Code Interpreter. Không thêm tool, endpoint hay surface nào.
4. **Nếu chủ sở hữu vẫn muốn HTML**: làm A trước (đã chạy, chỉ cần một dòng guidance nếu muốn model biết `.html` là lựa chọn), rồi C khi cần xem trong app; B chỉ khi xuất hiện nhu cầu slide **tương tác** thật, và khi đó phải viết ADR riêng cho origin artifact.

## 7. Việc dọn dẹp mà MEM-122 đang nợ (phát hiện kèm theo)

- `docs/roadmap.md:134` vẫn xếp MEM-122 là *candidate*; bảng Active chưa có dòng MEM-122.
- `AGENTS.md` mục "Current active increments" chưa có MEM-122.
- `docs/specs/chat.md` mục Code Interpreter chưa có gạch đầu dòng **Presentations** (`:333-336` mới có Spreadsheets/Documents/Output/Result).
- Trên đĩa mới có `interpreter/executor/memoryos_deck/{theme,measure,plan,render}.py`; `inspect.py`, `render_deck.py`, `check_pptx.py` chưa tồn tại.

## 8. Nguồn và caveat

- Đo CSP/`srcdoc`: Chromium headless qua omp browser, 2026-09-22, kèm đối chứng không CSP. Đo bằng thẻ `<meta>`; CSP thật của MemoryOS là header, ràng buộc chặt hơn hoặc bằng.
- Claude artifacts: iframe sandbox trên `claudeusercontent.com` với CSP riêng — [Claude Code artifacts docs](https://code.claude.com/docs/en/artifacts), [Simon Willison, claude-artifacts](https://simonwillison.net/tags/claude-artifacts/). Đây là mô tả từ tài liệu/bài viết, không phải mã nguồn kiểm chứng được.
- Onyx Craft `generate_pptx_preview` (`soffice` → `pdftoppm`): trích qua `mem-111/design.md:18,34`, không đọc lại thượng nguồn trong lần nghiên cứu này.
- Kích thước Chromium/WeasyPrint trong image executor: **chưa đo**.
