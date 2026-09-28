# MEM-191 — Trích xuất rơi về bộ đọc gốc khi Docling lỗi

[Linear MEM-191](https://linear.app/memory-os/issue/MEM-191)

## Vấn đề

PDF, DOCX và PPTX chỉ có một đường trích xuất: Docling. Docling không tới được, quá hạn, hay trả về thứ không dùng được thì tài liệu dừng ở `SOURCE_EXTRACTION_<lý do>` — kể cả Word, PowerPoint và PDF có lớp chữ, là những loại mà bộ đọc gốc đọc được không cần OCR.

Production được dựng không có Docling, vì Docling sẽ chạy riêng trên node khác. Trong khoảng đó nó không nhận được tài liệu văn phòng nào.

Spec cấm việc này hai lần: *"No Docling-to-Tika fallback exists"* và *"There is no Docling-to-Tika fallback for structured office formats"*. Chủ sản phẩm quyết định đổi: dùng OCR khi cần và khi nó tốt hơn, nhưng OCR lỗi thì vẫn phải có đường khác.

## Tham chiếu

**Onyx** (`backend/onyx/file_processing/extract_file_text.py`, `_extract_text_and_images`): khi có khoá Unstructured thì gọi dịch vụ đó trước; bất kỳ lỗi nào cũng được log rồi rơi về bộ đọc thường — pdfium cho PDF, python-docx, python-pptx. Đúng khuôn chủ sản phẩm yêu cầu, và là repo tham chiếu chính của dự án.

**RAGFlow** đã được increment [Tasco scanned-PDF OCR](../../active/tasco-scanned-pdf-ocr/design.md) nghiên cứu và loại: từ điển OCR DeepDoc thiếu ký tự tiếng Việt cơ bản, adapter Docling của họ làm mất nguồn gốc bảng.

## Chỗ khác Onyx, và vì sao

Onyx chấp nhận bất cứ thứ gì bộ đọc thường trả về, kể cả rỗng, và chỉ ghi lại việc rơi về vào log. Với khách này, cả hai điều đó đều là rủi ro thật:

**Kết quả mỏng bị từ chối.** Increment Tasco đã đếm: 254 trên 260 trang báo cáo tài chính không có lớp chữ, phần còn lại chỉ là chữ ký. Làm y Onyx thì khi Docling sập, cả bộ báo cáo đó được index thành công với nội dung là vài chữ ký — tài liệu không trả lời được câu hỏi nào mà vẫn báo thành công. Nên kết quả gốc rỗng, hoặc với PDF có dưới 100 ký tự không phải khoảng trắng mỗi trang, bị từ chối và lỗi ban đầu của Docling giữ nguyên.

100 ký tự/trang là ngưỡng thận trọng: một trang PDF có lớp chữ thường mang hàng trăm tới hàng nghìn ký tự, còn một bản scan mang không ký tự nào hoặc một chữ ký. Ngưỡng này không nhằm phân loại tài liệu cho đúng, mà nhằm chặn trường hợp bộ đọc gốc đọc được lề của bản scan rồi báo đã đọc được tài liệu.

**Đường đã đi được ghi lại.** Tài liệu đọc bằng đường này mang `parser=tika`, `fallback_from=docling` và `fallback_reason` trong `documents.metadata_json`. Nó không có OCR và không có cấu trúc bảng; ghi lại để tìm được và trích lại khi Docling khoẻ.

Hai điều này khớp với quyết định đã có trong increment Tasco: *"never silently fall back or mark partial extraction as success"*.

## Lỗi nào được rơi về

| Lỗi | Rơi về | Vì sao |
| --- | --- | --- |
| `CONNECTION_FAILED`, `TIMEOUT` | có | dịch vụ vắng mặt hoặc chậm |
| `INTERNAL` | có | dịch vụ báo thất bại |
| `MALFORMED` từ Docling | có | kết quả không phải một bản chuyển đổi dùng được |
| `ENCRYPTED` | không | tài liệu bị mã hoá; bộ đọc khác cũng từ chối |
| `WRITE_LIMIT` | không | tài liệu vượt giới hạn |
| Lỗi lúc kiểm tra đầu vào (PDFBox không mở được) | không | lỗi ở chính tài liệu, xảy ra trước khi gọi Docling |

Một lỗi runtime ngoài dự kiến vẫn đi đường thử lại có giới hạn như trước: có thể Docling trả lời được ở lần sau.

Khi đường rơi về bị từ chối vì bất kỳ lý do nào, lỗi ban đầu của Docling được ném lại nguyên vẹn. Người gọi thấy đúng thứ họ thấy trước khi đường này tồn tại.

## Phạm vi

Cả nguồn dữ liệu và file đính kèm Chat. Chat giữ đường spool trên đĩa của nó: tiến trình con Tika đọc file theo đường dẫn, không chép vào heap của worker. Tiến trình con nhận thêm PDF, DOCX, PPTX qua đường đó, với OCR tắt.

## Ngoài phạm vi

* Cấu hình URL OCR lúc runtime. Đổi hiếm, một biến môi trường là đủ, và ô URL là nơi worker gửi nguyên văn tài liệu khách hàng đi.
* Nhiều nhà cung cấp OCR. Engine trong Docling đã chọn được bằng biến môi trường; RapidOCR là model PaddleOCR và đã đo thua Tesseract với tiếng Việt.
* Tự động trích lại khi Docling khoẻ. Metadata ghi sẵn để việc đó làm được sau.
* Hiện đường rơi về trên giao diện Sources.
