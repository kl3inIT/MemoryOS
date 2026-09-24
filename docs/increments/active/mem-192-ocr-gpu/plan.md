# MEM-192 — Kế hoạch

Thứ tự đi cùng [MEM-135](../mem-135-embedding-settings/plan.md): hai increment dùng chung node `serving` và phải xong trước khi nạp bộ tài liệu Tasco, để mỗi tài liệu chỉ OCR một lần và embed một lần.

## Phase 1 — Chuẩn bị node `serving` (dùng chung với MEM-135 Phase A.1)

Xong ngày 2026-09-23. Chi tiết và số phiên bản ở [runbook](../../../runbooks/ci-cd.md#serving-node).

- [x] Nâng gói, kernel `6.8.0-142-generic`, reboot một lần.
- [x] Driver `580.178.04` (`580-server-open`, module dựng sẵn). Khoá driver và các gói kernel.
- [x] Docker 29.8.1, Compose v5.5.1, jq.
- [x] NVIDIA Container Toolkit 1.20.1. `nvidia-smi` chạy trong container.
- [x] Docker mặc định bind `127.0.0.1`, log giới hạn 50 MB × 5.
- [x] ufw: chỉ SSH từ `172.24.244.120`. Đã kiểm tra đường jump host và đường backup.
- [ ] Tắt `PasswordAuthentication` và `PermitRootLogin`: hoãn theo quyết định của chủ sản phẩm.

## Phase 2 — Spike đo đạc trên `serving`

Xong ngày 2026-09-23; kết quả ở [design](design.md#kết-quả-spike-2026-09-23). Container thử đã gỡ; image và kết quả giữ ở `/srv/mem192-spike` trên `serving`.

- [x] A (PaddleOCR-VL-1.6 native) trên 40, 43 và 72 trang; C (Docling cu128 + Tesseract) trên 40 trang. B không chạy.
- [x] Đối chiếu số liệu trang 6 và trang 9 với PDF gốc.
- [x] Sửa nghẽn RAM của vLLM bằng `mm-processor-cache-gb: 0` và `max-num-seqs: 32`.
- [x] Chủ sản phẩm chốt A, bỏ OCR của Docling, không hạ cấp khi PaddleOCR-VL lỗi.

## Phase 3 — Làm thật: một PR, qua `clean check` và CI

Theo [Nhiều adapter, một Document](design.md#nhiều-adapter-một-document).

**Mô hình Document**

1. Mô hình có kiểu `memoryos-extraction-v2` trong module `document` của `core`: block, location, table cell, page.
2. Bộ nâng cấp v1 → v2 lúc đọc; không reader nào khác biết v1. Test trên artifact v1 thật của nhánh Docling và nhánh native.
3. `StructuredDocumentChunker` còn một nhánh bảng; `FinancialTableDiagnostics` đọc `table.cells`.
4. Test: chunk và provenance sinh từ v1 và từ v2 trùng nhau trên cùng tài liệu.

Mục 1–4 đã làm (commit a), cùng phần "reader native ghi v2" của mục 5 và Docling ghi v2; `do_ocr=false` và định tuyến chưa đổi. `DocumentChunk.CONVENTION` lên `structured-cl100k-768-v3`. Artifact v1 dùng để test là bản dựng lại theo đúng hình dạng hai nhánh, chưa phải artifact lấy từ staging; kết quả chunk mong đợi được ghi bằng chunker cũ trước khi sửa.

**Adapter và định tuyến**

5. Adapter Docling ghi v2, Docling chạy với `do_ocr=false`. Các reader native ghi v2.
6. Client PaddleOCR-VL có giới hạn thời gian và kích thước, theo cùng quy tắc với `BoundedDoclingClient`. Không có API key: ranh giới mạng thay cho key (xem [design](design.md#nhiều-adapter-một-document)).
7. Adapter PaddleOCR-VL: `parsing_res_list` sang v2, bbox pixel sang điểm PDF, bảng HTML có `rowspan`/`colspan` sang `table.cells`, không đoán tiêu đề. Tắt nắn trang; xoay trang phải quy đổi toạ độ hoặc bỏ `bbox`. Test bằng trang thật từ spike (báo cáo công khai của HUT), gồm một trang xoay 90°.
8. Định tuyến theo mật độ lớp chữ: scan sang PaddleOCR-VL, có chữ sang Docling. Lỗi của PaddleOCR-VL là lỗi cuối như mọi `ExtractionException` (`FAILED`, reindex theo Source), không hạ cấp. File đính kèm Chat đi cùng định tuyến.
9. Nâng `compose.base.yaml` lên `docling-serve-cpu:v1.34.0`, tắt OCR.

Mục 5–9 đã làm (commit b): `PaddleOcrVlClient` gửi base64 dạng stream với `Content-Length` biết trước, đọc phản hồi tối đa 64 MiB, không thử lại; `PaddleOcrVlDocument` là adapter; `PdfLayout` đo lớp chữ và kích thước trang (CropBox, đổi chiều khi `/Rotate` 90/270) trong cùng một lần PDFBox với bước admission; `DocumentAssembly` là phần đuôi chung của hai adapter (text, `financial_checks`, giới hạn 32 MiB). Docling chỉ tắt OCR khi có endpoint PaddleOCR-VL (`ocr=none` trong `parser_configuration`); không có endpoint thì giữ nguyên cấu hình OCR cũ. Worker nhận `MEMORYOS_EXTRACTION_PADDLEOCR_VL_ENDPOINT`, `_TIMEOUT` (ban đầu 10 phút: 200 trang × 2,3 s; nay 30 phút, xem Phase 3b) và `_REVISION`. Ảnh Chat không có chữ (ảnh chụp) giữ mô tả ảnh cũ thay vì lỗi `MALFORMED`; lỗi dịch vụ vẫn làm hỏng file đính kèm. Test: `PaddleOcrVlDocumentTest`, `PaddleOcrVlClientTest`, `PaddleOcrVlRoutingTest`. Fixture `financial-statement-page.json` là trang ngang (1685 × 1191), không phải trang dọc; trang dọc lấy từ trang 2 của fixture xoay. Chưa chạy với PaddleOCR-VL thật.

**Hạ tầng**

10. `compose.serving.yaml`: layout API và vLLM của PaddleOCR-VL (`mm-processor-cache-gb: 0`, `max-num-seqs: 32`, `shm_size` nhỏ), TEI của MEM-135. vLLM không publish cổng, layout API bind `172.24.244.79`, user không phải root, giới hạn RAM, chia VRAM, ghim digest, tắt ảnh trong phản hồi.
11. Bước rollout lên host thứ hai trong workflow production: user deploy riêng trên `serving` với rule sudo đúng kịch bản, như node `application`.
12. Chain `DOCKER-USER` chỉ nhận `172.24.244.120` vào cổng layout API và TEI, lưu bằng unit systemd trong repo. Kiểm tra sau reboot: gọi được từ `application`, bị chặn từ nguồn khác.
13. Cập nhật `docs/specs/document.md`, `docs/specs/ingestion.md`, `docs/tests/*`, runbook và `ARCHITECTURE.md`.

Mục 10–12 đã làm (commit c), trừ TEI thuộc MEM-135: `compose.serving.yaml` và `serving.env.example`; `serving-firewall.sh` với `memoryos-serving-firewall.service`, đã thử trên `serving` ngày 2026-09-23 (cho phép `172.24.244.120`: HTTP 200; đổi nguồn khác: bị chặn; chạy hai lần: một rule); `deploy-serving.sh` bật firewall trước khi mở cổng rồi `compose up --wait`; bước "Roll out the serving node" trong `deploy.yml` chỉ cho production, qua `ProxyJump`, trước khi rollout ứng dụng. CI kiểm tra hai script và `compose config`. Việc cần làm trên máy và GitHub trước lần deploy đầu (user `memoryos-ci`, thư mục, `.env.serving`, key và biến môi trường `production`) ghi ở [runbook](../../../runbooks/ci-cd.md#serving-node); đã làm, production chạy PaddleOCR-VL từ 2026-09-23.

## Phase 3b — Làm cứng sau lần chạy đầu

Theo [design](design.md#làm-cứng-sau-lần-chạy-đầu-2026-09-24).

- [x] Bbox PaddleOCR-VL ghi `BOTTOMLEFT` trong toạ độ người dùng của PDF, cộng góc CropBox; trang có `/Rotate` khác 0 chỉ ghi `page_no`. Test: CropBox bắt đầu ở (36, 48) kiểm bằng đúng phép tính của `pdfBoxRect`, trang `/Rotate 90` không có bbox.
- [x] `max-concurrent-requests` (mặc định 2, 1–16) giới hạn request đồng thời mỗi worker; hạn chót tính từ lúc có permit; log `queued_ms`; timeout mặc định 30 phút trong properties, `application.yaml` của worker và `compose.base.yaml`.
- [x] Staging đọc qua `https://ocr.vadan.app` (access list chỉ nhận `72.62.193.33`); runbook ghi cả key `memoryos-ci` trên node `application` chỉ được forward tới `172.24.244.79:22`.

## Phase 4 — Nghiệm thu

- [x] Staging, 2026-09-24 (release `917266d0`): hai báo cáo scan HUT 45 và 44 trang index qua PaddleOCR-VL trong 78,8 s và 61,8 s (910 và 886 chunk, `parser=paddleocr-vl`, convention v3, tìm được, không lỗi); PDF có chữ và DOCX đi Docling với `ocr=none`.

- [ ] Bộ 6 báo cáo khoảng 260 trang được index qua production: không OOM, restart hay timeout.
- [ ] Số liệu và bảng khớp PDF gốc, không kém bản CPU. Highlight PDF đúng chỗ, kiểm trên trình duyệt.
- [ ] Có số đo tốc độ so với CPU, peak VRAM và RAM khi OCR và embedding chạy cùng lúc.
- [ ] Rollback về release trước (Docling làm OCR) đã thử. Khi PaddleOCR-VL dừng, tài liệu scan thành `FAILED` với `SOURCE_EXTRACTION_CONNECTION_FAILED`, và reindex theo Source đọc lại được khi nó chạy lại.
- [ ] Cập nhật `docs/runbooks/docling-extraction.md`, `ARCHITECTURE.md`, design MEM-171 và `AGENTS.md`.
