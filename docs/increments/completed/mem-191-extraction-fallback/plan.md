# MEM-191 — Kế hoạch

> Closed 2026-09-27 by owner decision (Linear Done); open acceptance items not run:
> - Chạy thật trên production khi Docling vắng mặt: một DOCX được index qua đường rơi về, một PDF scan vẫn báo lỗi.


- [x] Đọc khuôn tham chiếu trong Onyx và quyết định đã có của increment Tasco.
- [x] `DoclingSourceContentExtractor`: tách lỗi kiểm tra đầu vào khỏi lỗi dịch vụ; rơi về tiến trình con Tika cho các lỗi dịch vụ; từ chối kết quả rỗng hoặc mỏng như scan; ghi `parser`, `fallback_from`, `fallback_reason`.
- [x] Tiến trình con Tika nhận PPTX, và nhận PDF/DOCX/PPTX qua đường spool của Chat với OCR tắt.
- [x] `DoclingFallbackTest`: bảy trường hợp với tiến trình con Tika thật, PDFBox và POI.
- [x] Cập nhật `docs/specs/ingestion.md` và `docs/tests/ingestion.md`.
- [ ] Chạy thật trên production khi Docling vắng mặt: một DOCX được index qua đường rơi về, một PDF scan vẫn báo lỗi.

## Kiểm chứng — 2026-09-22

`gradlew :connector:test --tests DoclingFallbackTest --tests DoclingSourceContentExtractorTest --tests TikaSourceContentExtractorTest`:

| Lớp | Test | Bỏ qua | Lỗi |
| --- | --- | --- | --- |
| `DoclingFallbackTest` | 7 | 0 | 0 |
| `DoclingSourceContentExtractorTest` | 15 | 0 | 0 |
| `TikaSourceContentExtractorTest` | 7 | 0 | 0 |

Mười lăm test Docling có sẵn không đổi: chúng dùng một PDF trắng, nên đường rơi về chạy, bị từ chối vì rỗng, và lỗi ban đầu được ném lại — đúng lỗi các test đó chờ, kể cả `assertNull(error.getCause())` và việc Docling chỉ được gọi một lần.

Không có Docling thật trong lần chạy này; đó là phần của bước cuối.
