from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor


OUTPUT = Path(r"D:\FPT_Curriculum\Semester_9\MemoryOS\output\documents\de-xuat-agentic-ai-memory-os.docx")
FONT = "Arial"


def set_run_font(run, size=None, bold=None, italic=None):
    run.font.name = FONT
    rpr = run._element.get_or_add_rPr()
    rpr.rFonts.set(qn("w:ascii"), FONT)
    rpr.rFonts.set(qn("w:hAnsi"), FONT)
    rpr.rFonts.set(qn("w:eastAsia"), FONT)
    if size is not None:
        run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold
    if italic is not None:
        run.italic = italic


def configure_style(style, size, bold=False, before=0, after=0, line=1.15):
    style.font.name = FONT
    style.font.size = Pt(size)
    style.font.bold = bold
    style.font.color.rgb = RGBColor(0, 0, 0)
    rpr = style._element.get_or_add_rPr()
    rpr.rFonts.set(qn("w:ascii"), FONT)
    rpr.rFonts.set(qn("w:hAnsi"), FONT)
    rpr.rFonts.set(qn("w:eastAsia"), FONT)
    style.paragraph_format.space_before = Pt(before)
    style.paragraph_format.space_after = Pt(after)
    style.paragraph_format.line_spacing = line


def keep_with_next(paragraph):
    ppr = paragraph._p.get_or_add_pPr()
    if ppr.find(qn("w:keepNext")) is None:
        ppr.append(OxmlElement("w:keepNext"))


def add_heading(doc, text, level=1):
    paragraph = doc.add_paragraph(style=f"Heading {level}")
    paragraph.add_run(text)
    keep_with_next(paragraph)
    return paragraph


def add_body(doc, text, bold_lead=None):
    paragraph = doc.add_paragraph(style="Normal")
    paragraph.add_run(text)
    return paragraph


def add_bullet(doc, text, level=0, bold_lead=None):
    style = "List Bullet" if level == 0 else "List Bullet 2"
    paragraph = doc.add_paragraph(style=style)
    paragraph.add_run(text)
    return paragraph


def add_field(paragraph, instruction, display="1"):
    run = paragraph.add_run()
    set_run_font(run, 9)
    begin = OxmlElement("w:fldChar")
    begin.set(qn("w:fldCharType"), "begin")
    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = instruction
    separate = OxmlElement("w:fldChar")
    separate.set(qn("w:fldCharType"), "separate")
    text = OxmlElement("w:t")
    text.text = display
    end = OxmlElement("w:fldChar")
    end.set(qn("w:fldCharType"), "end")
    run._r.extend([begin, instr, separate, text, end])


def set_list_layout(paragraph, text_indent, hanging=0.18):
    """Fix the bullet, first line and wrapped lines to deterministic positions."""
    paragraph.paragraph_format.left_indent = Inches(text_indent)
    paragraph.paragraph_format.first_line_indent = Inches(-hanging)
    ppr = paragraph._p.get_or_add_pPr()
    existing_tabs = ppr.find(qn("w:tabs"))
    if existing_tabs is not None:
        ppr.remove(existing_tabs)
    tabs = OxmlElement("w:tabs")
    tab = OxmlElement("w:tab")
    tab.set(qn("w:val"), "num")
    tab.set(qn("w:pos"), str(Inches(text_indent).twips))
    tabs.append(tab)
    ppr.append(tabs)


doc = Document()
section = doc.sections[0]
section.page_width = Inches(8.5)
section.page_height = Inches(11)
section.top_margin = Inches(0.75)
section.bottom_margin = Inches(0.68)
section.left_margin = Inches(0.92)
section.right_margin = Inches(0.92)
section.header_distance = Inches(0.3)
section.footer_distance = Inches(0.32)

doc.core_properties.title = "Đề xuất tính Agentic AI của Memory OS"
doc.core_properties.subject = "Giải quyết hai bài toán ứng dụng AI của Tasco"
doc.core_properties.author = "Nhóm đồ án Memory OS"
doc.core_properties.keywords = "Memory OS, Agentic AI, AI Workspace, AI Agent, Tasco"

configure_style(doc.styles["Normal"], 11.5, before=0, after=3.8, line=1.12)
doc.styles["Normal"].paragraph_format.alignment = WD_ALIGN_PARAGRAPH.LEFT
configure_style(doc.styles["Heading 1"], 12.5, bold=False, before=7, after=3.5, line=1.05)
configure_style(doc.styles["Heading 2"], 11.5, bold=False, before=5, after=2.5, line=1.05)
configure_style(doc.styles["List Bullet"], 11.5, before=0, after=2.0, line=1.10)
configure_style(doc.styles["List Bullet 2"], 11.5, before=0, after=1.8, line=1.10)
doc.styles["Heading 2"].paragraph_format.left_indent = Inches(0.195)
doc.styles["List Bullet"].paragraph_format.left_indent = Inches(0.48)
doc.styles["List Bullet"].paragraph_format.first_line_indent = Inches(-0.18)
doc.styles["List Bullet 2"].paragraph_format.left_indent = Inches(0.82)
doc.styles["List Bullet 2"].paragraph_format.first_line_indent = Inches(-0.18)

footer_paragraph = section.footer.paragraphs[0]
footer_paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
footer_paragraph.paragraph_format.space_before = Pt(0)
footer_paragraph.paragraph_format.space_after = Pt(0)
footer_run = footer_paragraph.add_run("Trang: ")
set_run_font(footer_run, 9)
add_field(footer_paragraph, "PAGE", "1")
footer_run = footer_paragraph.add_run(" / ")
set_run_font(footer_run, 9)
add_field(footer_paragraph, "NUMPAGES", "4")

date_paragraph = doc.add_paragraph()
date_paragraph.paragraph_format.space_after = Pt(12)
date_run = date_paragraph.add_run("29 tháng 8 năm 2026")
set_run_font(date_run, 11)

title = doc.add_paragraph()
title.alignment = WD_ALIGN_PARAGRAPH.CENTER
title.paragraph_format.space_after = Pt(4)
title_run = title.add_run("ĐỀ XUẤT TÍNH AGENTIC AI\nCỦA MEMORY OS")
set_run_font(title_run, 14, bold=False)

subtitle = doc.add_paragraph()
subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
subtitle.paragraph_format.space_after = Pt(11)
subtitle_run = subtitle.add_run("Giải quyết hai bài toán ứng dụng AI của Tasco")
set_run_font(subtitle_run, 11.5, italic=True)

add_heading(doc, "TÓM TẮT", 1)
add_body(
    doc,
    "Nhóm đồ án đề xuất xây dựng Memory OS như một nền tảng Agentic AI phục vụ Tasco. "
    "Hệ thống sẽ giải quyết hai bài toán: cung cấp AI Workspace dùng chung cho hoạt động tri thức nội bộ; "
    "và phát triển các AI Agent chuyên biệt cho Tài chính - Kế toán, Thuế, Pháp chế và Ngân hàng đầu tư. "
    "Memory OS được định hướng không chỉ trả lời câu hỏi mà còn tiếp nhận mục tiêu, lập kế hoạch, truy xuất bằng chứng, "
    "sử dụng công cụ, kiểm tra kết quả và thực hiện tác vụ trong phạm vi được phân quyền. Tài liệu mô tả trạng thái mục tiêu của sản phẩm, "
    "các năng lực cần xây dựng, cách áp dụng cho từng bài toán và các điều kiện kiểm soát cần thiết."
)

add_heading(doc, "1. GIỚI THIỆU", 1)
add_body(
    doc,
    "Tri thức doanh nghiệp thường phân tán giữa quy định, hồ sơ, thư mục dùng chung, hệ thống nghiệp vụ và kinh nghiệm của từng bộ phận. "
    "Người dùng phải dành nhiều thời gian để tìm đúng nguồn, kiểm tra tính cập nhật, đối chiếu số liệu và chuyển thông tin thành báo cáo hoặc hành động cụ thể."
)
add_body(
    doc,
    "Memory OS được đề xuất như một lớp làm việc thống nhất giữa người dùng, dữ liệu nội bộ, mô hình AI và các công cụ nghiệp vụ. "
    "Hệ thống sẽ hỗ trợ công việc từ tra cứu đến hoàn thành nhiệm vụ, đồng thời duy trì kiểm soát về nguồn dữ liệu, quyền truy cập, phê duyệt và truy vết."
)

add_heading(doc, "2. THUẬT NGỮ VÀ TỪ VIẾT TẮT", 1)
add_bullet(doc, "Agentic AI: hệ thống AI tiếp nhận mục tiêu, tự lựa chọn dữ liệu và công cụ, thực hiện nhiều bước, đánh giá kết quả trung gian và hoàn thành nhiệm vụ trong giới hạn được kiểm soát.", bold_lead="Agentic AI:")
add_bullet(doc, "RAG (Retrieval-Augmented Generation): kỹ thuật truy xuất tài liệu liên quan trước khi mô hình tạo nội dung, giúp câu trả lời dựa trên bằng chứng nội bộ.", bold_lead="RAG (Retrieval-Augmented Generation):")
add_bullet(doc, "MCP/API: cơ chế để AI Agent gọi công cụ hoặc trao đổi dữ liệu với hệ thống nghiệp vụ theo hợp đồng tích hợp xác định.", bold_lead="MCP/API:")

add_heading(doc, "3. PHÁT BIỂU BÀI TOÁN", 1)
add_heading(doc, "3.1. Bài toán 1 - AI Workspace", 2)
add_body(doc, "AI Workspace cần tạo một không gian làm việc chung để người dùng có thể:")
add_bullet(doc, "Tra cứu thông tin, quy định, tài liệu và dữ liệu nội bộ.")
add_bullet(doc, "Hỏi đáp dựa trên nguồn có thẩm quyền và còn hiệu lực.")
add_bullet(doc, "Soạn thảo, tóm tắt, tổng hợp, so sánh và phân tích nội dung.")
add_bullet(doc, "Thực hiện các tác vụ AI nhằm nâng cao năng suất, chất lượng và tốc độ công việc.")
add_body(doc, "Các khó khăn chính cần được giải quyết:")
add_bullet(doc, "Nguồn tri thức phân tán, khác định dạng và có mức độ tin cậy khác nhau.")
add_bullet(doc, "Một yêu cầu nghiệp vụ thường cần nhiều bước tìm kiếm, đối chiếu và xử lý.")
add_bullet(doc, "Kết quả phải đúng theo quyền của người dùng và có thể kiểm chứng bằng nguồn.")

add_heading(doc, "3.2. Bài toán 2 - Các AI Agent chuyên biệt", 2)
add_body(doc, "Tasco có nhu cầu lớn tại bốn khối: Tài chính - Kế toán, Thuế, Pháp chế và Ngân hàng đầu tư. Mỗi AI Agent cần được thiết kế riêng theo:")
add_bullet(doc, "Mục tiêu và quy trình nghiệp vụ của đơn vị.")
add_bullet(doc, "Nguồn dữ liệu, thuật ngữ và quy tắc chuyên ngành.")
add_bullet(doc, "Công cụ và hệ thống nghiệp vụ được phép sử dụng.")
add_bullet(doc, "Yêu cầu bảo mật, phê duyệt và trách nhiệm của con người.")

add_heading(doc, "3.3. Yêu cầu chung đối với giải pháp", 2)
add_bullet(doc, "Không dừng ở chatbot hỏi - đáp một lượt hoặc RAG chỉ truy xuất một nguồn.")
add_bullet(doc, "Có khả năng xử lý mục tiêu nhiều bước và lựa chọn công cụ phù hợp theo ngữ cảnh.")
add_bullet(doc, "Cung cấp bằng chứng, trạng thái thực hiện và dấu vết để người dùng kiểm tra.")
add_bullet(doc, "Yêu cầu con người phê duyệt trước các hành động có tác động cao.")

add_heading(doc, "4. MỤC TIÊU", 1)
add_bullet(doc, "Xây dựng AI Workspace thống nhất để làm việc với tri thức nội bộ bằng ngôn ngữ tự nhiên.")
add_bullet(doc, "Cho phép AI chủ động tìm kiếm, phân tích, sử dụng công cụ và tạo sản phẩm công việc.")
add_bullet(doc, "Tạo các AI Agent chuyên biệt có cấu hình, dữ liệu và quyền hạn riêng.")
add_bullet(doc, "Bảo đảm câu trả lời và hành động tuân theo quyền của người dùng.")
add_bullet(doc, "Đo lường được chất lượng, hiệu quả, rủi ro và mức độ chấp nhận của người dùng.")

add_heading(doc, "5. TÍNH AGENTIC AI CỦA MEMORY OS", 1)
add_heading(doc, "5.1. Cấu trúc Agentic AI mục tiêu", 2)
add_body(doc, "Mỗi AI Agent trong Memory OS sẽ được hình thành từ sáu thành phần:")
add_bullet(doc, "Mục tiêu và chỉ dẫn: xác định nhiệm vụ, quy tắc xử lý, điều kiện dừng và dạng đầu ra.", bold_lead="Mục tiêu và chỉ dẫn:")
add_bullet(doc, "Tri thức: giới hạn nguồn tài liệu, dữ liệu và phạm vi thời gian được sử dụng.", bold_lead="Tri thức:")
add_bullet(doc, "Hành động: công cụ tìm kiếm, phân tích, tạo tài liệu và tích hợp hệ thống nghiệp vụ.", bold_lead="Hành động:")
add_bullet(doc, "Ngữ cảnh và bộ nhớ: duy trì thông tin cần thiết trong nhiệm vụ và tái sử dụng tri thức đã được xác nhận.", bold_lead="Ngữ cảnh và bộ nhớ:")
add_bullet(doc, "Chính sách và bảo mật: kiểm tra quyền, dữ liệu nhạy cảm, giới hạn công cụ và bước phê duyệt.", bold_lead="Chính sách và bảo mật:")
add_bullet(doc, "Đánh giá và kiểm soát: kiểm tra bằng chứng, chất lượng đầu ra, lỗi công cụ và khả năng truy vết.", bold_lead="Đánh giá và kiểm soát:")

add_heading(doc, "5.2. Chu trình thực thi", 2)
add_bullet(doc, "Hiểu mục tiêu, phạm vi, tiêu chí hoàn thành và mức độ rủi ro của yêu cầu.")
add_bullet(doc, "Lập kế hoạch gồm các bước tìm kiếm, xử lý và hành động cần thực hiện.")
add_bullet(doc, "Kiểm tra quyền đối với dữ liệu và công cụ trước khi thực thi.")
add_bullet(doc, "Truy xuất nhiều vòng, đối chiếu bằng chứng và xác định thông tin còn thiếu.")
add_bullet(doc, "Gọi công cụ phù hợp để tính toán, phân tích hoặc tương tác với hệ thống ngoài.")
add_bullet(doc, "Tự kiểm tra kết quả trung gian; điều chỉnh kế hoạch khi bằng chứng chưa đủ.")
add_bullet(doc, "Tạo câu trả lời, báo cáo, hồ sơ hoặc đề xuất hành động hoàn chỉnh.")
add_bullet(doc, "Yêu cầu phê duyệt khi cần và lưu lại dấu vết của toàn bộ quá trình.")

add_heading(doc, "5.3. Áp dụng cho bài toán 1 - AI Workspace", 2)
add_bullet(doc, "Kết nối và tổ chức tri thức: Memory OS sẽ tiếp nhận tài liệu, metadata và quyền truy cập từ các nguồn nội bộ; lập chỉ mục để phục vụ tìm kiếm và RAG.", bold_lead="Kết nối và tổ chức tri thức:")
add_bullet(doc, "Tra cứu có phân quyền: hệ thống sẽ chỉ tìm và sử dụng nội dung mà người dùng có quyền xem; kết quả gắn với nguồn để kiểm chứng.", bold_lead="Tra cứu có phân quyền:")
add_bullet(doc, "Hỏi đáp nhiều bước: Agent sẽ tự mở rộng truy vấn, tìm nhiều nguồn, đối chiếu quy định và hỏi lại khi yêu cầu thiếu dữ kiện.", bold_lead="Hỏi đáp nhiều bước:")
add_bullet(doc, "Soạn thảo và tổng hợp: Agent sẽ lập dàn ý, tóm tắt, so sánh, soạn văn bản và điều chỉnh đầu ra theo mẫu của tổ chức.", bold_lead="Soạn thảo và tổng hợp:")
add_bullet(doc, "Phân tích dữ liệu: Agent sẽ dùng công cụ tính toán hoặc môi trường chạy mã có kiểm soát để xử lý bảng dữ liệu, tính chỉ số và tạo biểu đồ.", bold_lead="Phân tích dữ liệu:")
add_bullet(doc, "Thực hiện tác vụ: khi được cấp quyền, Agent sẽ gọi API/MCP để lấy dữ liệu thời gian thực, chuẩn bị biểu mẫu, tạo yêu cầu hoặc cập nhật trạng thái.", bold_lead="Thực hiện tác vụ:")
add_bullet(doc, "Kiểm soát kết quả: Memory OS sẽ hiển thị nguồn, bước xử lý, công cụ đã dùng và trạng thái phê duyệt để người dùng chịu trách nhiệm cuối cùng.", bold_lead="Kiểm soát kết quả:")

add_heading(doc, "5.4. Áp dụng cho bài toán 2 - AI Agent chuyên biệt", 2)
add_body(doc, "Các AI Agent sẽ dùng chung nền tảng kỹ thuật nhưng có mục tiêu, tri thức, quy trình, công cụ và quyền hạn tách biệt:")
add_bullet(doc, "Agent Tài chính - Kế toán: đối chiếu số liệu, phân tích biến động, hỗ trợ lập báo cáo và chuẩn bị giao dịch để chuyên viên kiểm tra.", bold_lead="Agent Tài chính - Kế toán:")
add_bullet(doc, "Agent Thuế: tra cứu văn bản theo thời điểm, đối chiếu hồ sơ, tính toán nghĩa vụ, phát hiện dữ liệu thiếu và cảnh báo rủi ro tuân thủ.", bold_lead="Agent Thuế:")
add_bullet(doc, "Agent Pháp chế: rà soát hợp đồng, so sánh điều khoản với mẫu chuẩn, phát hiện rủi ro và đề xuất nội dung sửa đổi.", bold_lead="Agent Pháp chế:")
add_bullet(doc, "Agent Ngân hàng đầu tư: tổng hợp dữ liệu doanh nghiệp, hỗ trợ phân tích tài chính, xác định khoảng trống thông tin và xây dựng bản ghi nhớ đầu tư.", bold_lead="Agent Ngân hàng đầu tư:")
add_bullet(doc, "Cơ chế phối hợp: Agent sẽ chuyển việc cho con người khi thiếu bằng chứng, vượt thẩm quyền hoặc phát hiện trường hợp ngoại lệ.", bold_lead="Cơ chế phối hợp:")

add_heading(doc, "5.5. Bảo mật và kiểm soát", 2)
add_bullet(doc, "Phân tách phạm vi tri thức theo đơn vị, vai trò, nhóm người dùng và mức độ nhạy cảm.")
add_bullet(doc, "Kế thừa quyền truy cập từ nguồn dữ liệu khi có thể; từ chối xử lý khi không xác định được quyền.")
add_bullet(doc, "Tách quyền đọc dữ liệu khỏi quyền ghi hoặc kích hoạt hành động.")
add_bullet(doc, "Áp dụng phê duyệt của con người đối với gửi, sửa, ghi nhận hoặc phát hành nội dung quan trọng.")
add_bullet(doc, "Ghi nhật ký truy xuất, lập kế hoạch, gọi công cụ, lỗi, kết quả và quyết định phê duyệt.")
add_bullet(doc, "Thiết lập chính sách lưu giữ dữ liệu và giới hạn việc sử dụng thông tin nhạy cảm trong ngữ cảnh AI.")

add_heading(doc, "6. PHƯƠNG PHÁP TRIỂN KHAI", 1)
add_bullet(doc, "Giai đoạn 1 - Nền tảng AI Workspace: kết nối nguồn tri thức ưu tiên, xây dựng tìm kiếm có phân quyền, hỏi đáp có nguồn và quản trị người dùng.", bold_lead="Giai đoạn 1 - Nền tảng AI Workspace:")
add_bullet(doc, "Giai đoạn 2 - Năng lực Agentic AI: bổ sung lập kế hoạch, truy xuất nhiều vòng, công cụ phân tích, API/MCP, kiểm tra kết quả và phê duyệt.", bold_lead="Giai đoạn 2 - Năng lực Agentic AI:")
add_bullet(doc, "Giai đoạn 3 - AI Agent chuyên biệt: triển khai từng use case có giá trị cao, dữ liệu sẵn sàng và rủi ro kiểm soát được; sau đó mới mở rộng.", bold_lead="Giai đoạn 3 - AI Agent chuyên biệt:")
add_body(doc, "Mỗi use case cần xác định trước mục tiêu, dữ liệu, công cụ, chủ sở hữu nghiệp vụ, điều kiện dừng, tình huống ngoại lệ và tiêu chí nghiệm thu.")

add_heading(doc, "7. ĐO LƯỜNG", 1)
add_bullet(doc, "Tỷ lệ câu trả lời có bằng chứng đúng và còn hiệu lực.")
add_bullet(doc, "Tỷ lệ hoàn thành nhiệm vụ theo tiêu chí nghiệp vụ.")
add_bullet(doc, "Thời gian xử lý tiết kiệm so với quy trình hiện tại.")
add_bullet(doc, "Tỷ lệ kết quả phải sửa và mức độ sửa của chuyên viên.")
add_bullet(doc, "Tỷ lệ gọi công cụ hoặc hành động thất bại.")
add_bullet(doc, "Số trường hợp truy cập sai quyền hoặc vượt phạm vi cho phép.")
add_bullet(doc, "Chi phí, độ trễ và mức độ hài lòng trên mỗi nhóm nhiệm vụ.")

add_heading(doc, "8. GIỚI HẠN VÀ ĐIỀU KIỆN", 1)
add_bullet(doc, "Các năng lực trong tài liệu là trạng thái mục tiêu; Memory OS cần được thiết kế, xây dựng, tích hợp và kiểm thử trước khi đưa vào vận hành.")
add_bullet(doc, "Chất lượng phụ thuộc vào độ đầy đủ, tính cập nhật và cấu trúc của dữ liệu nội bộ.")
add_bullet(doc, "Không phải mọi chức năng RAG hoặc hỏi đáp đều mang tính agentic; tính agentic chỉ hình thành khi hệ thống có mục tiêu, kế hoạch, công cụ và cơ chế kiểm soát.")
add_bullet(doc, "Các kết luận chuyên môn và hành động có tác động cao vẫn cần chuyên gia chịu trách nhiệm xác nhận.")
add_bullet(doc, "Tích hợp hệ thống ngoài phải có API ổn định, phạm vi quyền rõ ràng và cơ chế xử lý lỗi.")

add_heading(doc, "KẾT LUẬN", 1)
add_body(
    doc,
    "Memory OS được đề xuất để giải quyết đồng thời hai bài toán của Tasco trên một nền tảng thống nhất. "
    "Đối với AI Workspace, hệ thống sẽ kết nối tri thức nội bộ, hỗ trợ hỏi đáp, soạn thảo, phân tích và thực hiện tác vụ có kiểm soát. "
    "Đối với các khối nghiệp vụ, hệ thống sẽ cung cấp AI Agent chuyên biệt theo quy trình, dữ liệu, công cụ và yêu cầu bảo mật riêng. "
    "Giá trị Agentic AI cốt lõi nằm ở khả năng chuyển từ câu hỏi thành mục tiêu nhiều bước, lựa chọn bằng chứng và công cụ, tự kiểm tra kết quả, "
    "phối hợp với con người và để lại dấu vết đầy đủ cho việc quản trị."
)

current_level = 0
for paragraph in doc.paragraphs:
    style_name = paragraph.style.name
    if style_name == "Heading 1":
        current_level = 1
    elif style_name == "Heading 2":
        current_level = 2
    elif style_name == "Normal":
        if current_level == 1:
            paragraph.paragraph_format.left_indent = Inches(0.18)
        elif current_level == 2:
            paragraph.paragraph_format.left_indent = Inches(0.36)
    elif style_name == "List Bullet":
        if current_level == 1:
            set_list_layout(paragraph, 0.48)
        elif current_level == 2:
            set_list_layout(paragraph, 0.62)
    elif style_name == "List Bullet 2":
        set_list_layout(paragraph, 0.86 if current_level == 2 else 0.70)
    for run in paragraph.runs:
        if run.font.name is None:
            set_run_font(run)
        run.bold = False
    ppr = paragraph._p.get_or_add_pPr()
    if ppr.find(qn("w:widowControl")) is None:
        ppr.append(OxmlElement("w:widowControl"))

settings = doc.settings._element
update_fields = OxmlElement("w:updateFields")
update_fields.set(qn("w:val"), "true")
settings.append(update_fields)

OUTPUT.parent.mkdir(parents=True, exist_ok=True)
doc.save(OUTPUT)
print(OUTPUT)
