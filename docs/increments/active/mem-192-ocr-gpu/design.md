# MEM-192 — OCR chạy GPU trên node `serving`

Linear: [MEM-192](https://linear.app/memory-os/issue/MEM-192). Liên quan: [MEM-171](../mem-171-production-deployment/design.md) (production), [MEM-135](../mem-135-embedding-settings/design.md) (embedding trên cùng GPU), [MEM-193](https://linear.app/memory-os/issue/MEM-193) (model chat trên cùng GPU), [MEM-191](../mem-191-extraction-fallback/design.md) (rơi về bộ đọc gốc), [MEM-79](../mem-79-rancher-ocr/design.md) (service OCR trên cụm dev).

## Kết quả mong muốn

Worker production gửi PDF scan tới OCR chạy trên RTX 4090 của node `serving`, thay cho Docling CPU đang chạy chung stack trên node `application`. Tiếng Việt và tiếng Anh đọc đúng dấu. Số liệu và bảng khớp PDF gốc. Provenance vẫn có `page_no` và `bbox` để Chat và Search tô sáng trích dẫn. Tăng tốc chỉ tính khi đo được trên chính bộ tài liệu Tasco.

## Hiện trạng

* Node `serving` (172.24.244.79): 8 vCPU, **15 GiB RAM**, RTX 4090 24 GB. Đã chuẩn bị ngày 2026-09-23, xem [runbook](../../../runbooks/ci-cd.md#serving-node).
* Production chạy `docling-serve-cpu:v1.32.0` trong `compose.base.yaml`, trên node `application`.
* `infrastructure/deployment/ocr/` là service OCR riêng: Tesseract `vie`, orientation wrapper, build trên image CPU. Nó chạy ở cụm Rancher `jmix-ocr`. **Cụm đó chỉ là môi trường dev**; production không phụ thuộc vào nó.
* Tesseract chỉ chạy CPU. GPU chỉ tăng tốc các model của Docling (layout, TableFormer) và các OCR engine chạy PyTorch.

## Bản mới nhất đã kiểm tra (2026-09-23)

| Thành phần | Bản | Ghi chú |
| --- | --- | --- |
| docling-serve | v1.34.0 (docling-slim 2.128.0) | Image `docling-serve-cu128` và `-cu130` chỉ có tag phiên bản, không có `latest`. Ghim digest. |
| vLLM | v0.30.0 | |
| PaddleOCR-VL | 1.6 (2026-05-28), 0.9B | [Recipe vLLM chính thức](https://recipes.vllm.ai/PaddlePaddle/PaddleOCR-VL-1.6). Hỗ trợ 109 ngôn ngữ. |

Các model OCR chính hãng khác cùng thời điểm: MinerU2.5-Pro, DeepSeek-OCR 2, GLM-OCR, dots.mocr. PaddleOCR-VL-1.6 được chọn làm ứng viên chính vì mới nhất, nhỏ nhất và có recipe vLLM chính thức. Không hãng nào công bố benchmark tiếng Việt, nên chất lượng phải đo trên tài liệu của mình.

## Ràng buộc phát hiện khi đọc source Docling

**Mất bbox.** Docling có VLM pipeline gọi được model qua API OpenAI-compatible (`DOCLING_SERVE_ENABLE_REMOTE_SERVICES`, `DOCLING_SERVE_CUSTOM_VLM_PRESETS`) và không có preset sẵn cho PaddleOCR-VL. Khi model trả Markdown, Docling ghi mọi bbox thành `0,0,0,0` và chỉ giữ số trang (`docling/pipeline/vlm_pipeline.py`, nhánh `ResponseFormat.MARKDOWN`). Chỉ các định dạng DocTags, DeepSeek-OCR, dots, MinerU2, Chandra và Nemotron giữ được toạ độ.

MemoryOS cần bbox: [document viewer](../document-viewer/design.md) tô sáng trích dẫn PDF bằng `page_no` + `bbox`, và [Document contract](../../../specs/document.md) liệt kê `prov.bbox` là khoá mà Chat và Search đọc.

**Mất orientation.** Orientation wrapper của `memoryos_docling` chỉ bọc pipeline PDF chuẩn. Đi qua VLM pipeline thì trang xoay không được sửa.

## Ba phương án, chọn bằng spike

| | Cách chạy | bbox | Orientation | Rủi ro |
| --- | --- | --- | --- | --- |
| **A** | Pipeline chính thức của PaddleOCR-VL (layout PP-DocLayout + nhận dạng VL trên vLLM). Một adapter trong Worker chuyển kết quả sang Document của MemoryOS (xem [Nhiều adapter, một Document](#nhiều-adapter-một-document)). | Theo block | Tự xử lý trước khi gửi | Phải viết adapter. Cần xác nhận pipeline gọi được vLLM server. |
| **B** | Docling VLM pipeline, preset tự khai trỏ vào vLLM, đầu ra Markdown. | Chỉ số trang | Mất | Trích dẫn PDF scan không tô sáng được. |
| **C** | Docling pipeline chuẩn chạy `cu128`: layout và TableFormer trên GPU, OCR vẫn Tesseract `vie`. | Có | Giữ | Tốc độ bị Tesseract (CPU) giới hạn. |

[Tài liệu PaddleOCR-VL](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/pipeline_usage/PaddleOCR-VL.en.md) nói rõ: gửi ảnh thẳng tới model qua vLLM, như phương án B, **không tương đương** pipeline đầy đủ và dễ sinh chữ bịa. Pipeline đầy đủ gồm layout (trả toạ độ từng block và thứ tự đọc) rồi mới nhận dạng từng block bằng VLM. Cách triển khai chính thức cho NVIDIA là hai service: `paddleocr-vl-api` (layout, HTTP API trên PaddleX) và `paddleocr-vlm-server` (vLLM, model có sẵn trong image bản `-offline`). File mẫu ở `deploy/paddleocr_vl_docker/accelerators/nvidia-gpu/`. Mẫu đặt `shm_size: 64g` và chạy `root`; trên node 15 GiB phải hạ `shm_size`.

C là mốc so sánh và đường rollback. Đề xuất ban đầu là A. Chọn B chỉ khi chủ sản phẩm chấp nhận tường minh rằng PDF scan chỉ cần trích dẫn theo trang.

Tiêu chí, theo thứ tự: số liệu và bảng khớp PDF gốc; dấu tiếng Việt; có bbox dùng được; số trang mỗi giây; peak VRAM và RAM. VLM có thể sinh ra con số trông hợp lý nhưng sai, nên tiêu chí đầu tiên được kiểm bằng so sánh tay với PDF, không dựa vào điểm benchmark.

## Kết quả spike (2026-09-23)

Chạy trên node `serving`, container chỉ bind loopback. Dữ liệu: báo cáo tài chính công khai của HUT (Tasco), bản scan không có lớp chữ. Chủ sản phẩm chọn A (native) trước khi spike xong; B không chạy, vì việc mất bbox đã thấy trong source Docling và Baidu nói rõ cách đó không tương đương pipeline đầy đủ.

| | C: Docling cu128 v1.34.0 + Tesseract `vie,eng` | A: PaddleOCR-VL-1.6 native |
| --- | --- | --- |
| Q1/2026 hợp nhất, 40 trang | 317 s (7,9 s/trang) | 93 s (2,3 s/trang) |
| Q1/2026 công ty mẹ, 43 trang | chưa chạy | 66 s (1,5 s/trang) |
| Bản CPU cũ, cùng loại tài liệu | 616 s cho 40 trang (4 CPU / 5 GiB, increment Tasco OCR) | |
| Trang 6, Báo cáo tình hình tài chính: 26 số | khoảng 16/26 đúng số và đúng dòng: bốn dòng gộp vào một ô, số gán sai dòng, hai số đọc `.` thành `,` | 26/26 |
| Trang 9, Kết quả kinh doanh: 56 số | 3 số sai chữ số (`87.552…` thay `57.552…`, `…274.670`, `41.846…`), mã số sai (`LA`, `VI.B`, `VLS`) | 56/56, mã số đúng |
| Lỗi chữ | nhiều lỗi dấu và ký tự | vài lỗi dấu: "NGẨN HẢN", "tiễn", "glả", "Chỉ phí", "thuyền" |
| bbox | có | có theo block: `block_bbox` pixel ở hệ số 2 so với điểm PDF; bảng trang 6 lệch dưới 2 pt so với bbox của Docling |
| Peak VRAM (cả GPU) | 7,5 GB, trong đó vLLM của A đang nằm chờ 5,2 GB | 9,5–9,7 GB |
| Peak RAM container | Docling 5,65 GiB | vLLM 4,7–5,2 GiB, layout API 2,8–2,9 GiB |

**Sự cố RAM của vLLM.** Với trần 6 GiB, báo cáo 72 trang làm container vLLM nghẽn bộ nhớ (`memory.pressure full avg10≈16%`, anon 5,0 GB cộng 1,37 GB `/dev/shm` tính vào trần): tốc độ sinh xuống 0–12 token/s, 62 request chạy và 129 request chờ, không OOM. Image của Paddle không đặt `--mm-processor-cache-gb 0` như recipe vLLM chính thức. Sau khi đặt `mm-processor-cache-gb: 0` và `max-num-seqs: 32`, pressure về 0 và anon còn 2,7 GB. Cấu hình production phải mang hai giá trị này.

Kết luận tạm thời: A đúng số liệu hơn hẳn và nhanh gấp khoảng 3,4 lần C trên GPU, 6,6 lần bản CPU. Lỗi còn lại của A nằm ở dấu trong chữ, không nằm ở số.

## Nhiều adapter, một Document

**Hiện trạng.** Artifact `memoryos-extraction-v1` là vỏ của MemoryOS, nhưng ruột của nhánh Docling là dữ liệu Docling chép nguyên: `provenance` là mảng `prov` của Docling, bảng là `TableData` với `table_cells` và `start_row_offset_idx`, `pages` là object của Docling. Các reader vì thế phải hiểu Docling: `StructuredDocumentChunker` có hai nhánh bảng (`table_cells` của Docling và `cells` của các reader native), `FinancialTableDiagnostics` đọc `table_cells`, và [Document contract](../../../specs/document.md) ghi "Docling blocks keep Docling `prov` items". Thêm PaddleOCR-VL theo kiểu đó sẽ là định dạng thứ ba mà mọi reader phải biết.

**Quyết định.** Document của MemoryOS là một mô hình có kiểu, nằm trong module `document` của `core`, và là thứ duy nhất reader đọc. Mỗi provider có một adapter chuyển đầu ra của nó sang mô hình đó; không adapter nào để lọt khoá riêng của provider.

```
Docling JSON        ─┐
PaddleOCR-VL JSON   ─┼─ adapter của từng provider ─→ Document (memoryos-extraction-v2) ─→ chunker, kiểm tra bảng, viewer
Tika, XLSX, CSV,    ─┘
Google Docs/Sheets,
SharePoint page
```

Mô hình (`memoryos-extraction-v2`):

* `blocks[]`: `kind` (`HEADING` | `PARAGRAPH` | `LIST_ITEM` | `TABLE` | `IMAGE`), `text`, `headingLevel`, `locations[]`, `table`, `sheetName`.
* `locations[]`: `page_no` (từ 1), `bbox` gồm `l`, `t`, `r`, `b` **tính bằng điểm PDF** và `coord_origin` (`TOPLEFT` | `BOTTOMLEFT`). Đây là khoá MemoryOS đã dùng trong provenance của chunk và trình xem PDF, nên giữ nguyên tên. Adapter chỉ ghi đúng các khoá này; khoá khác của provider (như `charspan`) bị bỏ.
* `table.cells[]`: một hình dạng duy nhất cho mọi provider: `row`, `column`, `rowSpan`, `columnSpan`, `columnHeader`, `rowHeader`, `text`, và `blocks` cho ô lồng khối (Google Docs). Thay cả `table_cells` của Docling lẫn `cells` hiện tại.
* `pages[]`: `page_no`, `width`, `height` tính bằng điểm, và `orientation` khi có.
* `financial_checks` giữ nguyên, nhưng tính trên `table.cells` của v2.

Hệ quả:

* **Một nhánh bảng trong chunker** thay vì hai. `FinancialTableDiagnostics` đọc `table.cells`.
* **Adapter Docling** chuyển `prov` và `table_cells` sang v2. Hành vi tìm kiếm và highlight giữ nguyên.
* **Adapter PaddleOCR-VL** đọc `parsing_res_list`: `block_label` sang `kind`, `block_bbox` (pixel ảnh trang) nhân tỉ lệ `điểm PDF / chiều rộng ảnh` rồi đổi sang toạ độ người dùng của PDF với `coord_origin: BOTTOMLEFT` (xem [Làm cứng sau lần chạy đầu](#làm-cứng-sau-lần-chạy-đầu-2026-09-24)), bảng HTML (`rowspan`, `colspan`) sang `table.cells`. `columnHeader` theo quy tắc đã đo (xem [Ngữ cảnh của bảng](#ngữ-cảnh-của-bảng-tiêu-đề-cột-và-tên-báo-cáo-2026-09-24)); không chắc thì không đánh dấu.
* **Artifact v1 đã lưu vẫn đọc được**: một bộ nâng cấp v1 → v2 duy nhất chạy lúc đọc, để việc dựng lại chunk (`DocumentChunk.CONVENTION`) không cần OCR lại. Không reader nào khác biết v1.
* **Provenance đã lưu trong chunk không đổi khoá**, nên trình xem PDF không phải sửa.
* **`parser` trong metadata** ghi provider thật (`docling`, `paddleocr-vl`, `tika`, …), `parser_configuration` ghi phiên bản model hoặc engine.

**Chọn provider theo lớp chữ, không theo đuôi file.** Khi đã có PaddleOCR-VL, Docling không làm OCR nữa (quyết định của chủ sản phẩm, 2026-09-23):

| Đầu vào | Adapter | OCR |
| --- | --- | --- |
| PDF scan, ảnh | PaddleOCR-VL | có, GPU trên `serving` |
| PDF có lớp chữ, DOCX, PPTX | Docling, `do_ocr=false` | không |
| XLSX, CSV, Google Docs/Sheets, SharePoint page | reader native hiện có | không |

PDF là "scan" khi mật độ chữ của lớp chữ dưới ngưỡng 100 ký tự không trắng mỗi trang, cùng ngưỡng PDFBox mà MEM-191 đã dùng. PDF đi PaddleOCR-VL khi bất kỳ trang nào có lớp chữ dưới ngưỡng, để các bảng báo cáo scan nằm trong một báo cáo có chữ không bị mất; một trang trắng hay trang ảnh chỉ tốn thời gian GPU, không mất nội dung.

**Không hạ cấp khi PaddleOCR-VL lỗi.** Spike cho thấy Tesseract sai chữ số trong số tiền, và một tài liệu đã index sai không được đọc lại khi dịch vụ quay lại, vì `parser_configuration` chỉ là metadata chẩn đoán. Không có dự phòng sang Docling hay Tesseract cho bản scan. Lỗi của PaddleOCR-VL đi đúng đường hiện có: `ExtractionException` là lỗi cuối (`DefaultIngestionCoordinator`), attempt và item thành `FAILED` với `SOURCE_EXTRACTION_<failure>`, lần đồng bộ sau không tự đọc lại item không đổi, và người quản lý Source bấm reindex. Chủ sản phẩm chọn cách này thay vì thêm cơ chế tự thử lại (2026-09-23). Dự phòng MEM-191 sang bộ đọc native vẫn áp dụng cho Docling và file có lớp chữ.

**Mạng thay cho API key.** API `layout-parsing` của PaddleX và vLLM server của Paddle không có xác thực. API key sẽ không thêm bảo vệ đáng kể: key nằm trên node `application`, API không lưu tài liệu, và key không mã hoá đường truyền. Rủi ro thật là cổng Docker vượt qua ufw: bind vào `172.24.244.79` là mở cho cả subnet `172.24.244.0/24`. Vì vậy: vLLM không publish cổng; layout API bind `172.24.244.79`; chain `DOCKER-USER` chỉ nhận `172.24.244.120` vào các cổng dịch vụ và DROP mọi nguồn khác, lưu bằng unit systemd trong repo để còn sau reboot. Rule này phủ cả cổng TEI của MEM-135. Không có proxy xác thực.

**Hệ quả cho hạ tầng.** Docling chạy bản CPU chính thức `docling-serve-cpu:v1.34.0` trên node `application`, không cần GPU và không cần Tesseract `vie`. Image tự build ở `infrastructure/deployment/ocr/` (Tesseract, orientation wrapper) không còn dùng cho production; việc xoay trang giao cho bước tiền xử lý của PaddleOCR-VL. Node `serving` chỉ chạy PaddleOCR-VL và TEI của MEM-135, phần còn lại để cho MEM-193.

**Trang xoay và toạ độ.** Đã thử ngày 2026-09-23 với trang 6 của báo cáo HUT được xoay 90° trong ảnh scan, đặt trên trang khổ ngang, kèm bản đứng để đối chiếu. Tiền xử lý tắt (`useDocOrientationClassify=false`, `useDocUnwarping=false`): bảng đọc giống hệt bản đứng, và `block_bbox` `[259, 91, 1005, 1032]` trên ảnh 1685 × 1191 đúng là vị trí bảng trên trang như nó hiển thị (quy đổi từ bản đứng `[158, 270, 1100, 1000]` ra xấp xỉ `[270, 91, 1000, 1033]`). Bật `useDocOrientationClassify` thì API trả 500 vì image không nạp model tiền xử lý (`use_doc_preprocessor: False`). Vì vậy adapter gửi cả hai cờ là `false`, và toạ độ luôn thuộc trang PDF như nó hiển thị: nhân `chiều rộng trang (điểm) / width` và `chiều cao trang (điểm) / height` của ảnh. Bản đầu ghi `coord_origin: TOPLEFT` tính từ góc CropBox; bản hiện tại ghi `BOTTOMLEFT` trong toạ độ người dùng, và trang có `/Rotate` trong PDF (xoay bằng metadata, không phải ảnh) chỉ mang `page_no`, xem [Làm cứng sau lần chạy đầu](#làm-cứng-sau-lần-chạy-đầu-2026-09-24).

**Tham số request cố định.** `fileType: 0`, `visualize: false` (bỏ ảnh minh hoạ; phản hồi 2 trang còn 29,7 KB), `mergeTables: false` (bảng qua nhiều trang giữ `page_no` riêng của từng trang), hai cờ tiền xử lý `false`, `useChartRecognition: false` (xem Nhận dạng biểu đồ tắt trong [Ngữ cảnh của bảng](#ngữ-cảnh-của-bảng-tiêu-đề-cột-và-tên-báo-cáo-2026-09-24)). Layout model của image là `PP-DocLayoutV3`.

**Tiêu đề bảng.** Bảng của Paddle chỉ có `<td>`. Tiêu đề tài chính thường có số ("31/03/2026", "Quý I"), nên quy tắc "hàng không có số" bị loại. Quy tắc đang dùng được đo trên dữ liệu thật, xem [Ngữ cảnh của bảng](#ngữ-cảnh-của-bảng-tiêu-đề-cột-và-tên-báo-cáo-2026-09-24).

**File đính kèm Chat** (`extractChatFile`) đi cùng định tuyến: ảnh scan và PDF scan đính kèm cũng đọc bằng PaddleOCR-VL.

**Một PR** gồm: mô hình v2 và bộ nâng cấp v1, adapter Docling và PaddleOCR-VL, định tuyến, cấu hình, `compose.serving.yaml` và bước rollout lên `serving`.

## Quyết định

* **Nâng docling-serve lên v1.34.0** bản CPU trên `application`, tắt OCR. Đổi engine revision theo image thật.
* **Một file `compose.serving.yaml` trong repo** cho mọi dịch vụ trên `serving`: PaddleOCR-VL (layout API và vLLM), TEI của MEM-135 và sau này vLLM chat của MEM-193. Deploy bằng workflow production với một bước rollout lên host thứ hai. Không có đường deploy tay. Image Docling do repo build, nên không thể để người vận hành tự quản.
* **Chỉ mạng riêng.** Xem [Mạng thay cho API key](#nhiều-adapter-một-document). Docker trên `serving` mặc định bind `127.0.0.1`, nên cổng nào quên ghi IP cũng không lộ ra ngoài.
* **Chunk sinh lại theo quy ước mới.** Gộp hai nhánh bảng đổi chữ của bảng Docling không có tiêu đề ("Column N: …" thành "[A1] …"), nên `DocumentChunk.CONVENTION` đổi. Staging dựng lại chunk và embedding từ artifact đã lưu, không OCR lại; production chưa có tài liệu.
* **Image Paddle** ghim digest: `paddleocr-vl@sha256:6c735bdf9e758ffdd58ccc067db0c2d84e37e5e6a2cbd47156069d4d7ea5d709`, `paddleocr-genai-vllm-server@sha256:d0d32c04a2119613d25a0a4c292e165ccc107954b74580613cf59e378037f8f5` (PaddleX 3.6.1, model `PaddlePaddle/PaddleOCR-VL-1.6` revision `c5630abae1d940eafe0697512a0325494b02ab42`). Image mặc định chạy user `paddleocr`; compose mẫu của Baidu ép `root`, MemoryOS thì không. Tiền xử lý trang tắt sẵn (`use_doc_preprocessor: False`), nên toạ độ nằm trên trang gốc; trang xoay phải được thử trên dữ liệu thật.
* **Ngân sách tài nguyên dùng chung.** VRAM chia bằng `--gpu-memory-utilization` cho từng vLLM, RAM đặt giới hạn cho từng container. Giới hạn thật là 15 GiB RAM, không phải 24 GB VRAM. Nếu không đủ, Docling phần CPU quay về `application` (31 GiB), chỉ giữ model trên `serving`.
* **Luồng dự phòng MEM-191 chỉ còn cho file có lớp chữ:** Docling lỗi thì Worker rơi về bộ đọc native. Bản scan không có dự phòng; nó chờ PaddleOCR-VL.

## Làm cứng sau lần chạy đầu (2026-09-24)

**Bbox trong toạ độ người dùng của PDF.** Trình xem nhận `view` của trang = CropBox `[x0, y0, x1, y1]` (trục y hướng lên) và `pdfBoxRect` trừ `x0`, `y0` khỏi box. Bản đầu ghi `TOPLEFT` tính từ góc CropBox, nên PDF có CropBox không bắt đầu ở (0, 0) bị tô lệch. Nay `PdfLayout` ghi cho từng trang góc dưới trái `(x0, y0)`, chiều rộng và cao của CropBox đúng như lưu (không đổi chiều) và `/Rotate`; `pages[]` của Document không đổi. Trang không xoay: `l = x0 + px1·sx`, `r = x0 + px2·sx`, `t = y0 + h − py1·sy`, `b = y0 + h − py2·sy`, với `sx = w / width ảnh`, `sy = h / height ảnh`, `coord_origin: BOTTOMLEFT`. Trang có `/Rotate` 90, 180 hoặc 270 chỉ ghi `page_no`: trình xem không tô trang xoay, và trích dẫn không định vị được thì không vẽ ([document viewer](../document-viewer/design.md)). Hợp đồng `ExtractedDocument.Page` không đổi.

**Giới hạn số request đồng thời.** Mỗi worker chạy tới `MEMORYOS_WORKER_INGESTION_BATCH_SIZE` (mặc định 8) thao tác cùng lúc, và staging dùng chung GPU với production. Tám bản scan 200 trang gửi cùng lúc, ở 1,5–2,3 s/trang, đều vượt hạn 10 phút, và timeout là lỗi cuối. Nay client giữ một `Semaphore` công bằng, `max-concurrent-requests` mặc định 2 (1–16, `MEMORYOS_EXTRACTION_PADDLEOCR_VL_MAX_CONCURRENT_REQUESTS`); request chờ permit, hạn chót tính từ lúc có permit, nên chờ hàng không làm tài liệu hết hạn. Log ghi `queued_ms`. Timeout mặc định lên 30 phút.

**Nghiệm thu trên staging (2026-09-24).** Worker staging (release `917266d0`) với `MEMORYOS_EXTRACTION_PADDLEOCR_VL_ENDPOINT=https://ocr.vadan.app` index hai báo cáo tài chính scan của HUT qua PaddleOCR-VL: 45 trang trong 78,8 s và 44 trang trong 61,8 s, 910 và 886 chunk, `parser=paddleocr-vl`, convention v3, tìm được, không lỗi. Một PDF có chữ và một DOCX đi Docling với `ocr=none`. `ocr.vadan.app` là host Nginx Proxy Manager trên node `application`, access list chỉ nhận `72.62.193.33` (nơi khác 403), chuyển tới `172.24.244.79:18080`, nên rule `DOCKER-USER` vẫn chỉ thấy `172.24.244.120`. Đường staging này là ngoại lệ có chủ ý với việc tách môi trường của [MEM-171](../mem-171-production-deployment/design.md), chủ sản phẩm chấp nhận ngày 2026-09-23. Còn mở: nghiệm thu bộ tài liệu Tasco trên production và kiểm tra highlight trên trình duyệt.

## Ngữ cảnh của bảng: tiêu đề cột và tên báo cáo (2026-09-24)

**Vấn đề.** Chunk dựng từ bảng PaddleOCR-VL mất ngữ cảnh. Bảng không có ô tiêu đề nên chunker đọc theo địa chỉ ô: `[A5] Tiền [B5] 111 [D5] 2.470.482.178.999`, không còn biết số nào là "Số cuối kỳ", số nào là "Số đầu kỳ". Tên báo cáo ("BÁO CÁO TÌNH HÌNH TÀI CHÍNH") Paddle gắn nhãn `figure_title`, adapter coi là đoạn văn, nên dòng `Section:` của chunk không nêu báo cáo nào.

**Dữ liệu đo.** Kết quả PaddleOCR-VL-1.6 thật của ba báo cáo công khai của HUT (Tasco): hợp nhất quý I/2026, công ty mẹ quý I/2026 và hợp nhất 6 tháng 2026; 155 trang, 156 bảng. Mỗi bảng được dàn lại bằng đúng quy tắc `rowspan`/`colspan` của adapter, rồi gán nhãn tay hàng tiêu đề của **cả 156 bảng** (44, 34 và 78 bảng), tổng 184 hàng tiêu đề. Quy ước gán nhãn: hàng chú thích (một ô trải cả bảng như "5. Hàng tồn kho", "Lãi cho vay") không phải tiêu đề; hàng nhóm dưới tiêu đề (chữ ở cột chỉ tiêu, cột số trống, như "1. Lưu chuyển tiền từ hoạt động kinh doanh", "NGUYÊN GIÁ") không phải tiêu đề; ô gộp "Đơn vị tính: VND Năm trước" vẫn là tiêu đề; tiêu đề lặp giữa bảng nằm ngoài phạm vi. Ba bảng có ô lạ trong hàng tiêu đề được ghi riêng từng ô: "Nguyên giá" (tên nhóm Paddle gộp vào hàng tiêu đề), ô chỉ tiêu có `rowspan` phủ xuống dòng số, và một dòng dữ liệu đọc lẫn với tiêu đề dưới chú thích. Nhãn và script đo nằm ở `.tmp/` (không commit).

**Quy tắc.** Giá trị là số tiền (`1.234.567`, `(8.910.000.000)`, `802.373,22`), phần trăm, mã số (`01`, `111`), mã thuyết minh (`V.1`) hoặc dấu gạch. Hàng dữ liệu đầu tiên là hàng đầu tiên có giá trị; các ô giá trị của nó là cột số, các cột bên trái cột số đầu tiên là cột chỉ tiêu. Tiêu đề là các hàng phía trên, tính từ trên xuống, chừng nào mỗi hàng phủ ít nhất nửa số cột số (ô có `rowspan` của hàng tiêu đề phía trên được tính) và, từ hàng tiêu đề thứ hai hoặc dưới một chú thích, để trống cột chỉ tiêu; hàng có chữ chỉ tiêu mà không có số là hàng nhóm và kết thúc tiêu đề. Một ô duy nhất trải qua cả cột chỉ tiêu và cột số ở trên cùng là chú thích. Quy tắc **không đánh dấu gì** (bảng giữ cách đọc theo địa chỉ ô) khi giá trị đầu tiên ở hàng 1 hoặc dưới hàng 5, khi một ô khác trải qua cả cột chỉ tiêu và cột số, khi hơn ba hàng đủ điều kiện, hoặc khi một ô tiêu đề có `rowspan` phủ xuống dữ liệu. Ô trống không bao giờ là tiêu đề; ô ở cột chỉ tiêu của hàng tiêu đề cũng không, nếu thân bảng có hàng nhóm, vì khi đó tên nhóm và tên cột trông như nhau. Không đánh dấu `rowHeader`: ô chỉ tiêu đã đọc thành `CHỈ TIÊU: …` hoặc `Column 1: …` trên chính dòng của nó, và dòng dài nhất của dữ liệu (9 ô) vừa một chunk, nên dòng `Row:` chỉ lặp lại nó.

**Kết quả đo** (156 bảng, 184 hàng tiêu đề):

| Quy tắc | Precision (hàng) | Recall (hàng) | Ghi chú |
| --- | --- | --- | --- |
| Hàng đầu luôn là tiêu đề | 90,4% | 77,9% | 15 hàng sai: bảng nối trang, chú thích |
| Mọi hàng trên hàng có số | 82,9% | 96,7% | 36 hàng sai: hàng nhóm, chú thích |
| Quy tắc dưới đây, chưa có luật cột chỉ tiêu | 99,4% | 94,0% | sai ô "Nguyên giá" |
| **Quy tắc đang dùng** | **100% (173/173)** | **94,0% (173/184)** | theo ô: precision 100%, recall 94,0% |

Mười bốn bảng bị bỏ qua. Ba là bảng nối trang không có tiêu đề (đúng). Sáu là bảng chữ không có số ("Bên liên quan | Mối quan hệ", mục lục, "Nhóm TSCĐ | Số năm"). Năm là bảng khó: ô "12." có `rowspan` 8, ô chỉ tiêu phủ xuống dòng doanh thu, "4. | (tiếp theo)" trải qua cột chỉ tiêu, chú thích trên một dòng dữ liệu lẫn tiêu đề, và "Kỳ này" nằm ở cột mà dòng số đầu tiên để trống. Bản Java cho đúng các ô như script Python trên cả 156 bảng.

**Tên báo cáo trong `Section:`.** Đo trên cùng dữ liệu. `doc_title` (44) là phần của báo cáo: trang bìa ("CÔNG BỐ THÔNG TIN ĐỊNH KỲ BÁO CÁO TÀI CHÍNH"), phần thuyết minh; giữ cấp 1. `paragraph_title` (347) là tên thuyết minh ("1. Tiền và tương đương tiền", "V. THÔNG TIN BỔ SUNG…") ngay trên bảng của nó; giữ cấp 2. `figure_title` (40) lẫn năm loại: tên báo cáo ("BÁO CÁO TÌNH HÌNH TÀI CHÍNH / Tại ngày 31 tháng 3 năm 2026"), tên thuyết minh Paddle tưởng là chú thích ("9. Tăng, giảm tài sản cố định hữu hình", "23.3 …"), tên bảng con ("Công ty con trực tiếp"), khối tên công ty và địa chỉ hay "BẢN THUYẾT MINH … (tiếp theo)" lặp ở đầu trang, và chữ dưới chữ ký. Tên báo cáo và tên thuyết minh đóng đúng vai `paragraph_title`, nên cũng là cấp 2: `figure_title` thành heading cấp 2 khi khối thân tiếp theo trên cùng trang, bỏ qua ghi chú đơn vị (`vision_footnote`), là bảng, và nó không lặp một tiêu đề hay header chạy nào trước đó trong tài liệu (trùng từ Jaccard ≥ 0,75; chữ thường, bỏ dấu, từ có từ hai chữ cái). Trong dữ liệu có 27 `figure_title` nằm ngay trên bảng: 10 thành heading (tên báo cáo, mục lục, tên thuyết minh, tên bảng con), 17 bị coi là lặp (12 header chạy, 5 trang "(tiếp theo)" hoặc tiêu đề đã có). Tiêu đề thật gần header chạy nhất ở mức 0,64 (tên báo cáo so với header "Báo cáo tài chính hợp nhất / Tại ngày …"); trang "(tiếp theo)" thấp nhất 0,80, header chạy thấp nhất 0,89; ngưỡng 0,75 nằm giữa. Header trang vẫn là furniture: chỉ dùng để so, không bao giờ vào chỉ mục. Kết quả: 30 trong 156 bảng đổi `Section:`; 21 bảng nay nêu đúng báo cáo, thuyết minh hoặc bảng con của nó (ví dụ `CÔNG BỐ THÔNG TIN ĐỊNH KỲ BÁO CÁO TÀI CHÍNH > BÁO CÁO TÌNH HÌNH TÀI CHÍNH …`); 9 bảng sai cả trước lẫn sau, vì tên của chúng chỉ có ở header trang hoặc bị gắn nhãn `text` (báo cáo kết quả kinh doanh ở trang 9 kế thừa tên bảng cân đối).

**Chunker.** Ở chế độ có nhãn, ô tiêu đề trống không thêm nhãn và ô trống không thêm dòng, như chế độ địa chỉ; cột không có tiêu đề đọc `Column N`. Dòng tiền của bảng cân đối đọc `TÀI SẢN: 1. Tiền`, `Mã\nSố: 111`, `Số cuối kỳ: 2.470.482.178.999`, `Số đầu kỳ: 2.764.761.087.606` (`\n` là chữ Paddle trả về). Không đổi `DocumentChunk.CONVENTION`: bảng Docling có ô trống chỉ bớt dòng `Nhãn: ` khi chunk được dựng lại.

**Nhận dạng biểu đồ tắt.** Thử `useChartRecognition` của PaddleOCR-VL-1.6 ngày 2026-09-24 trên hai biểu đồ trong báo cáo thường niên 2025 của HUT: biểu đồ đường hai chuỗi chỉ trả về một chuỗi và không có tên chuỗi; biểu đồ cột nhóm xuất khẩu, nhập khẩu, xuất siêu trả về một bảng bịa `Year | Quarter | Start Price | End Price` với số sai (ví dụ `33625` thay cho `336,25`). Một con số bịa trong chỉ mục tệ hơn một biểu đồ không có chữ, nên request gửi `useChartRecognition: false` tường minh, kể cả khi server bật mặc định; `PaddleOcrVlClientTest.chartRecognitionIsNeverRequested` giữ điều đó.

**Còn mở.** 34 trong 44 `doc_title` lặp một tiêu đề hoặc header trước đó: báo cáo 6 tháng gắn `doc_title` cho header chạy ("CÔNG TY CỔ PHẦN TASCO BẢN THUYẾT MINH … (tiếp theo)") và dòng kỳ ("Cho kỳ tài chính từ ngày …"), nên mỗi trang đặt lại `Section:` về cấp 1 và mất tên thuyết minh. Chặn lặp cho `doc_title` đã thử trong script: giữ được tên thuyết minh qua trang, nhưng dòng kỳ lần đầu lại thành cấp 1 cho cả phần còn lại; chưa làm.

## Ngân sách node `serving`

Điền dần bằng số đo.

| Dịch vụ | VRAM | RAM | Nguồn |
| --- | --- | --- | --- |
| TEI + Qwen3-Embedding-4B | khoảng 8,4 GB (cả GPU 18,1 GB cùng PaddleOCR-VL) | 4,6 GiB, đỉnh 5,9 GiB lúc nạp | MEM-135 |
| PaddleOCR-VL-1.6: vLLM + layout API | 9,6–9,7 GB cả GPU lúc chạy (vLLM đặt 0,35) | vLLM 2,7 GB anon + 1,4 GB shm sau khi sửa cache; layout API 2,9 GiB | spike |
| vLLM chat | phần còn lại | phần còn lại | MEM-193 |
| OS và Docker | 0 | khoảng 1 GiB | đo lúc trống: 670 MiB |

## Ngoài phạm vi

vLLM cho model chat (MEM-193); embedding (MEM-135); đổi Search hoặc Chat; mọi thay đổi trên cụm dev `jmix-ocr`.

## Việc còn mở trên node

* `PasswordAuthentication` và `PermitRootLogin` vẫn đang `yes` trên `serving`. Chủ sản phẩm hoãn việc tắt ngày 2026-09-23. Runbook yêu cầu tắt; ufw đã giới hạn SSH chỉ từ `172.24.244.120`, nên rủi ro hiện chỉ đến từ node `application`.
