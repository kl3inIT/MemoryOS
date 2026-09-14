from __future__ import annotations

import shutil
from copy import deepcopy
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK, WD_LINE_SPACING
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Pt


ROOT = Path(r"D:\FPT_Curriculum\Semester_9\MemoryOS")
REFERENCE = ROOT / "output" / "documents" / "de-xuat-agentic-ai-memory-os.docx"
FINAL = ROOT / "output" / "documents" / "ban-thu-v2-tinh-agentic-ai-qua-hai-bai-toan-tasco.docx"


def set_run_font(run, size: float, italic: bool = False) -> None:
    run.font.name = "Arial"
    run.font.size = Pt(size)
    run.font.bold = False
    run.font.italic = italic
    r_pr = run._element.get_or_add_rPr()
    r_fonts = r_pr.rFonts
    if r_fonts is None:
        r_fonts = OxmlElement("w:rFonts")
        r_pr.insert(0, r_fonts)
    for attr in ("ascii", "hAnsi", "eastAsia", "cs"):
        r_fonts.set(qn(f"w:{attr}"), "Arial")
    r_pr.get_or_add_b().set(qn("w:val"), "0")
    r_pr.get_or_add_bCs().set(qn("w:val"), "0")


def clear_body(doc: Document) -> None:
    body = doc._element.body
    sect_pr = body.sectPr
    for child in list(body):
        if child is not sect_pr:
            body.remove(child)


def set_exact_line_spacing(paragraph, value: float) -> None:
    paragraph.paragraph_format.line_spacing_rule = WD_LINE_SPACING.EXACTLY
    paragraph.paragraph_format.line_spacing = Pt(value)


def format_paragraph_runs(paragraph, size: float = 11.5, italic: bool = False) -> None:
    for run in paragraph.runs:
        set_run_font(run, size, italic=italic)


def add_date(doc: Document) -> None:
    p = doc.add_paragraph(style="Normal")
    p.alignment = WD_ALIGN_PARAGRAPH.LEFT
    p.paragraph_format.left_indent = Pt(0)
    p.paragraph_format.first_line_indent = Pt(0)
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(12)
    set_exact_line_spacing(p, 13.45)
    set_run_font(p.add_run("30 tháng 8 năm 2026"), 11)


def add_title(doc: Document) -> None:
    p = doc.add_paragraph(style="Normal")
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.left_indent = Pt(0)
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(4)
    set_exact_line_spacing(p, 16.8)
    r = p.add_run("TÍNH AGENTIC AI CỦA MEMORY OS")
    set_run_font(r, 14)
    r.add_break(WD_BREAK.LINE)
    r2 = p.add_run("QUA HAI BÀI TOÁN CỦA TASCO")
    set_run_font(r2, 14)

    p = doc.add_paragraph(style="Normal")
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.left_indent = Pt(0)
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(11)
    set_exact_line_spacing(p, 13.45)
    set_run_font(p.add_run("Bản thử nghiệm phần nội dung trọng tâm"), 11.5, italic=True)


def add_heading1(doc: Document, text: str) -> None:
    p = doc.add_paragraph(style="Heading 1")
    p.paragraph_format.left_indent = Pt(0)
    p.paragraph_format.first_line_indent = Pt(0)
    p.paragraph_format.space_before = Pt(7)
    p.paragraph_format.space_after = Pt(3.5)
    p.paragraph_format.keep_with_next = True
    p.paragraph_format.keep_together = True
    set_exact_line_spacing(p, 12.6)
    set_run_font(p.add_run(text), 12.5)


def add_heading2(doc: Document, text: str) -> None:
    p = doc.add_paragraph(style="Heading 2")
    p.paragraph_format.left_indent = Pt(14.05)
    p.paragraph_format.first_line_indent = Pt(0)
    p.paragraph_format.space_before = Pt(5)
    p.paragraph_format.space_after = Pt(2.5)
    p.paragraph_format.keep_with_next = True
    p.paragraph_format.keep_together = True
    set_exact_line_spacing(p, 12.6)
    set_run_font(p.add_run(text), 11.5)


def add_body(doc: Document, text: str, nested: bool = False) -> None:
    p = doc.add_paragraph(style="Normal")
    p.paragraph_format.left_indent = Pt(25.9 if nested else 12.95)
    p.paragraph_format.first_line_indent = Pt(0)
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(3.8)
    set_exact_line_spacing(p, 13.45)
    set_run_font(p.add_run(text), 11.5)


def add_bullet(
    doc: Document,
    text: str,
    nested: bool = True,
    keep_with_next: bool = False,
) -> None:
    p = doc.add_paragraph(style="List Bullet")
    p.paragraph_format.left_indent = Pt(44.65 if nested else 34.55)
    p.paragraph_format.first_line_indent = Pt(-12.95)
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(2)
    p.paragraph_format.keep_with_next = keep_with_next
    p.paragraph_format.keep_together = True
    set_exact_line_spacing(p, 13.2)
    set_run_font(p.add_run(text), 11.5)


def add_field(paragraph, instruction: str, cached_text: str) -> None:
    run = paragraph.add_run()
    set_run_font(run, 9)
    begin = OxmlElement("w:fldChar")
    begin.set(qn("w:fldCharType"), "begin")
    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = f" {instruction} "
    separate = OxmlElement("w:fldChar")
    separate.set(qn("w:fldCharType"), "separate")
    text = OxmlElement("w:t")
    text.text = cached_text
    end = OxmlElement("w:fldChar")
    end.set(qn("w:fldCharType"), "end")
    run._r.extend([begin, instr, separate, text, end])


def rebuild_footer(doc: Document) -> None:
    footer = doc.sections[0].footer
    footer.is_linked_to_previous = False
    p = footer.paragraphs[0]
    for child in list(p._element):
        p._element.remove(child)
    for extra in list(footer.paragraphs[1:]):
        footer._element.remove(extra._element)
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.left_indent = Pt(0)
    p.paragraph_format.right_indent = Pt(0)
    p.paragraph_format.first_line_indent = Pt(0)
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.space_after = Pt(0)
    set_exact_line_spacing(p, 10.8)
    set_run_font(p.add_run("Trang: "), 9)
    add_field(p, "PAGE", "1")
    set_run_font(p.add_run(" / "), 9)
    add_field(p, "NUMPAGES", "1")


def write_content(doc: Document) -> None:
    add_date(doc)
    add_title(doc)

    add_heading1(doc, "1. HAI BÀI TOÁN CỦA TASCO")
    add_heading2(doc, "1.1. Bài toán 1 - AI Workspace")
    add_body(
        doc,
        "Tasco cần một AI Workspace dùng chung để hỗ trợ người dùng làm việc với thông tin, quy định và dữ liệu nội bộ. Không gian này phải bao quát các nhóm nhu cầu chính:",
        nested=True,
    )
    add_bullet(doc, "Tra cứu thông tin, quy định, tài liệu và dữ liệu nội bộ.")
    add_bullet(doc, "Hỏi đáp dựa trên nguồn có thẩm quyền, đúng phạm vi truy cập và còn hiệu lực.")
    add_bullet(doc, "Soạn thảo, tóm tắt, tổng hợp, so sánh và phân tích nội dung hoặc dữ liệu.")
    add_bullet(doc, "Thực hiện các tác vụ AI khác nhằm nâng cao năng suất, chất lượng và tốc độ công việc.")
    add_body(
        doc,
        "Bài toán không chỉ là cung cấp một công cụ tìm kiếm hoặc chatbot. Một yêu cầu công việc thường cần nhiều bước tìm kiếm, đối chiếu, xử lý và tạo ra kết quả có thể sử dụng trực tiếp.",
        nested=True,
    )

    add_heading2(doc, "1.2. Bài toán 2 - Các AI Agent chuyên biệt")
    add_body(
        doc,
        "Tasco cần phát triển các AI Agent chuyên biệt cho những khối có nhu cầu lớn:",
        nested=True,
    )
    add_bullet(doc, "Tài chính - Kế toán.")
    add_bullet(doc, "Thuế.")
    add_bullet(doc, "Pháp chế.")
    add_bullet(doc, "Ngân hàng đầu tư.")
    add_body(
        doc,
        "Mỗi Agent phải được thiết kế theo mục tiêu và quy trình nghiệp vụ, nguồn dữ liệu, thuật ngữ, công cụ được phép sử dụng, yêu cầu bảo mật và cơ chế phê duyệt riêng của từng khối.",
        nested=True,
    )

    add_heading1(doc, "2. CÁCH HIỂU VỀ TÍNH AGENTIC AI")
    add_heading2(doc, "2.1. Tính Agentic AI trong phạm vi Memory OS")
    add_body(
        doc,
        "Trong Memory OS, Agentic AI là khả năng tiếp nhận mục tiêu của người dùng, tự xác định các bước cần thực hiện, lựa chọn nguồn tri thức và công cụ phù hợp, kiểm tra kết quả trung gian, điều chỉnh kế hoạch và tạo ra sản phẩm công việc trong phạm vi được phân quyền. Tính agentic không đồng nghĩa với tự chủ không giới hạn; mọi hành động vẫn chịu ràng buộc bởi dữ liệu được phép sử dụng, quyền của người dùng và các điểm phê duyệt của con người.",
        nested=True,
    )
    add_bullet(doc, "Goal interpretation: chuyển yêu cầu bằng ngôn ngữ tự nhiên thành mục tiêu, phạm vi, tiêu chí hoàn thành và dạng đầu ra cụ thể.")
    add_bullet(doc, "Planning: chia mục tiêu thành các bước tìm kiếm, đối chiếu, phân tích, tạo nội dung hoặc thực hiện tác vụ.")
    add_bullet(doc, "Iterative reasoning and retrieval: truy xuất nhiều vòng, so sánh bằng chứng và xác định thông tin còn thiếu thay vì chỉ tìm một lần rồi trả lời.")
    add_bullet(doc, "Actions: chủ động lựa chọn công cụ, API/MCP hoặc môi trường tính toán được cấp quyền để hoàn thành từng bước.")
    add_bullet(doc, "Evaluation and adaptation: kiểm tra đầu ra theo tiêu chí, thử lại hoặc thay đổi kế hoạch khi bằng chứng chưa đủ hoặc công cụ trả về lỗi.")
    add_bullet(doc, "Human-in-the-loop: hỏi lại, xin phê duyệt hoặc chuyển việc cho con người khi yêu cầu không rõ, vượt thẩm quyền hoặc có tác động cao.")

    add_heading2(doc, "2.2. Khác biệt với hỏi đáp và RAG thông thường")
    add_bullet(doc, "Chatbot hỏi đáp thường tạo phản hồi trực tiếp từ câu hỏi và ngữ cảnh hiện có.")
    add_bullet(doc, "RAG giúp truy xuất một hoặc nhiều nguồn liên quan để làm căn cứ cho câu trả lời.")
    add_bullet(doc, "Agentic AI sử dụng RAG như một bước trong chu trình lớn hơn, đồng thời lập kế hoạch, gọi công cụ, đánh giá kết quả và tiếp tục hành động cho đến khi đạt tiêu chí hoàn thành hoặc cần con người can thiệp.")

    add_heading1(doc, "3. TÍNH AGENTIC AI TRONG BÀI TOÁN 1 - AI WORKSPACE")
    add_body(
        doc,
        "AI Workspace không chỉ cung cấp một ô hỏi đáp chung. Memory OS sẽ đóng vai trò như một tác nhân làm việc với tri thức nội bộ, có khả năng chuyển yêu cầu của người dùng thành một quy trình giải quyết công việc hoàn chỉnh.",
    )

    add_heading2(doc, "3.1. Chu trình xử lý một yêu cầu trong AI Workspace")
    add_bullet(doc, "Goal and Context: xác định người yêu cầu muốn biết điều gì hoặc cần tạo sản phẩm gì; làm rõ phạm vi đơn vị, thời điểm, đối tượng sử dụng và tiêu chí đầu ra.")
    add_bullet(doc, "Planning: lập kế hoạch gồm các bước cần tìm tài liệu, kiểm tra hiệu lực, đối chiếu dữ liệu, phân tích và tạo kết quả.")
    add_bullet(doc, "Knowledge: chỉ truy cập tài liệu, hồ sơ và dữ liệu mà người dùng có quyền xem; ưu tiên nguồn có thẩm quyền và metadata phù hợp.")
    add_bullet(doc, "Reasoning and Retrieval: mở rộng truy vấn, tìm nhiều nguồn, so sánh các phiên bản, phát hiện mâu thuẫn hoặc khoảng trống thông tin và hỏi lại khi cần.")
    add_bullet(doc, "Actions: sử dụng công cụ tính toán, xử lý bảng dữ liệu, tạo tài liệu hoặc gọi API/MCP để lấy dữ liệu thời gian thực và chuẩn bị tác vụ được giao.")
    add_bullet(doc, "Evaluation: kiểm tra tính đầy đủ của bằng chứng, sự nhất quán của số liệu, mức độ đáp ứng mẫu đầu ra và các điều kiện hoàn thành đã xác định.")
    add_bullet(doc, "Approval and Traceability: yêu cầu phê duyệt trước hành động có tác động; lưu nguồn, bước xử lý, công cụ đã dùng, lỗi và quyết định của người phê duyệt.")

    add_heading2(doc, "3.2. Cách giải quyết các nhóm công việc chính")
    add_bullet(doc, "Tra cứu và hỏi đáp: Agent không chỉ trả về đoạn văn liên quan mà còn xác định văn bản còn hiệu lực, đối chiếu nhiều nguồn và giải thích căn cứ của kết luận.")
    add_bullet(doc, "Soạn thảo và tổng hợp: Agent lập dàn ý theo mục tiêu, thu thập bằng chứng cho từng phần, tạo bản nháp theo mẫu của tổ chức và tự kiểm tra các nội dung còn thiếu.")
    add_bullet(doc, "Phân tích dữ liệu: Agent xác định dữ liệu cần dùng, gọi công cụ tính toán, kiểm tra chất lượng dữ liệu, phát hiện bất thường và tạo báo cáo kèm phương pháp xử lý.")
    add_bullet(doc, "Thực hiện tác vụ: Agent có thể chuẩn bị biểu mẫu, tạo yêu cầu hoặc đề xuất cập nhật trạng thái; việc ghi, gửi hoặc phát hành chỉ được thực hiện khi đúng quyền và qua điểm phê duyệt đã cấu hình.")

    add_heading2(doc, "3.3. Giá trị Agentic AI đối với Tasco")
    add_bullet(doc, "Người dùng mô tả mục tiêu công việc thay vì phải chỉ dẫn từng thao tác tìm kiếm và xử lý.")
    add_bullet(doc, "Một yêu cầu có thể được giải quyết xuyên suốt từ tri thức đến sản phẩm công việc, giảm chuyển đổi thủ công giữa nhiều nguồn và công cụ.")
    add_bullet(doc, "Kết quả có thể kiểm chứng và tái hiện nhờ nguồn bằng chứng, lịch sử hành động và trạng thái phê duyệt.")

    add_heading1(doc, "4. TÍNH AGENTIC AI TRONG BÀI TOÁN 2 - CÁC DOMAIN AGENT")
    add_body(
        doc,
        "Các Domain Agent sử dụng chung chu trình Agentic AI nhưng được chuyên biệt hóa theo quy trình, dữ liệu, công cụ và giới hạn trách nhiệm của từng khối. Sự chuyên biệt này quyết định cách Agent lập kế hoạch, lựa chọn bằng chứng, thực hiện hành động và chuyển việc cho con người.",
    )

    add_heading2(doc, "4.1. Agent Tài chính - Kế toán")
    add_bullet(doc, "Mục tiêu điển hình: đối chiếu số liệu và chuẩn bị báo cáo phân tích biến động theo kỳ.", keep_with_next=True)
    add_bullet(doc, "Hành vi agentic: xác định kỳ và đơn vị báo cáo; lập kế hoạch lấy dữ liệu từ hệ thống được cấp quyền; đối chiếu sổ, ngân sách và dữ liệu hỗ trợ; phát hiện chênh lệch; gọi công cụ tính toán; yêu cầu bổ sung dữ liệu; sau đó tạo báo cáo và phần giải trình để chuyên viên kiểm tra.", keep_with_next=True)
    add_bullet(doc, "Giới hạn: không tự động ghi sổ, phê duyệt giao dịch hoặc khởi tạo thanh toán nếu chưa có quyền và phê duyệt tương ứng; phải lưu nguồn số liệu và công thức đã sử dụng.")

    add_heading2(doc, "4.2. Agent Thuế")
    add_bullet(doc, "Mục tiêu điển hình: rà soát hồ sơ và xác định nghĩa vụ thuế theo kỳ.", keep_with_next=True)
    add_bullet(doc, "Hành vi agentic: xác định pháp nhân, kỳ tính thuế và văn bản có hiệu lực; thu thập hồ sơ liên quan; kiểm tra dữ liệu thiếu; gọi công cụ tính toán; đối chiếu kết quả với quy định; đánh dấu rủi ro và tạo hồ sơ làm việc để chuyên gia thuế xem xét.", keep_with_next=True)
    add_bullet(doc, "Giới hạn: không tự quyết định cách diễn giải pháp lý có tranh luận hoặc tự nộp hồ sơ; các giả định, phiên bản văn bản và bước phê duyệt phải được ghi lại.")

    add_heading2(doc, "4.3. Agent Pháp chế")
    add_bullet(doc, "Mục tiêu điển hình: rà soát hợp đồng theo mẫu chuẩn và chính sách của Tasco.", keep_with_next=True)
    add_bullet(doc, "Hành vi agentic: nhận diện loại hợp đồng; chọn mẫu, chính sách và nguồn pháp lý phù hợp; so sánh từng điều khoản; phát hiện nội dung thiếu hoặc lệch chuẩn; phân loại rủi ro; đề xuất sửa đổi; chuyển trường hợp ngoại lệ cho chuyên viên pháp chế.", keep_with_next=True)
    add_bullet(doc, "Giới hạn: tài liệu mật chỉ được truy cập theo nhu cầu biết; Agent không tự phát hành ý kiến pháp lý hoặc gửi bản sửa đổi ra bên ngoài khi chưa được phê duyệt.")

    add_heading2(doc, "4.4. Agent Ngân hàng đầu tư")
    add_bullet(doc, "Mục tiêu điển hình: tổng hợp hồ sơ doanh nghiệp và xây dựng bản ghi nhớ đầu tư ban đầu.", keep_with_next=True)
    add_bullet(doc, "Hành vi agentic: lập danh sách dữ liệu cần thiết; truy xuất nguồn được phép trong từng thương vụ; chuẩn hóa số liệu; gọi công cụ phân tích và định giá; nhận diện khoảng trống; tạo câu hỏi bổ sung; cập nhật phân tích khi có dữ liệu mới và hình thành bản ghi nhớ để nhóm đầu tư thẩm định.", keep_with_next=True)
    add_bullet(doc, "Giới hạn: tách biệt dữ liệu theo thương vụ, kiểm soát thông tin nhạy cảm và quyền biết; không tự chia sẻ tài liệu ra ngoài hoặc đưa ra quyết định đầu tư.")

    add_heading2(doc, "4.5. Điểm chung thể hiện tính agentic")
    add_bullet(doc, "Mỗi Agent xử lý một mục tiêu nghiệp vụ theo nhiều bước thay vì chỉ sinh một câu trả lời chuyên ngành.")
    add_bullet(doc, "Agent có thể thay đổi kế hoạch khi thiếu dữ liệu, phát hiện ngoại lệ hoặc kết quả trung gian không đạt tiêu chí.")
    add_bullet(doc, "Agent phối hợp tri thức, công cụ và kiểm soát con người để tạo ra sản phẩm công việc có căn cứ và có thể kiểm toán.")

    add_heading1(doc, "5. CÁC THÀNH PHẦN HỖ TRỢ")
    add_body(
        doc,
        "Memory OS sử dụng Instructions để xác định mục tiêu và quy tắc; Knowledge để giới hạn nguồn dữ liệu và quyền truy cập; Actions để cấp công cụ, API/MCP và thao tác nghiệp vụ; Memory/Context để duy trì ngữ cảnh; Guardrails để kiểm soát dữ liệu nhạy cảm, hành động và phê duyệt; Evaluation để kiểm tra kết quả. Đây là các cơ chế hỗ trợ; tính agentic xuất hiện khi Agent phối hợp chúng trong chu trình lập kế hoạch, hành động, đánh giá và điều chỉnh theo mục tiêu.",
    )


def main() -> None:
    if not REFERENCE.exists():
        raise FileNotFoundError(REFERENCE)
    FINAL.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(REFERENCE, FINAL)
    doc = Document(FINAL)
    clear_body(doc)
    write_content(doc)
    rebuild_footer(doc)
    doc.core_properties.title = "Tính Agentic AI của Memory OS qua hai bài toán của Tasco"
    doc.core_properties.subject = "Bản thử nghiệm phần nội dung trọng tâm"
    doc.save(FINAL)
    print(FINAL)


if __name__ == "__main__":
    main()
