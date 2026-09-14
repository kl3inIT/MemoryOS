from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.opc.constants import RELATIONSHIP_TYPE as RT
from docx.shared import Inches, Pt, RGBColor


OUTPUT = Path(r"D:\FPT_Curriculum\Semester_9\MemoryOS\output\documents\de-xuat-agentic-ai-onyx.docx")
FONT = "Arial"


def set_run_font(run, size=None, bold=None, italic=None):
    run.font.name = FONT
    run._element.get_or_add_rPr().rFonts.set(qn("w:ascii"), FONT)
    run._element.get_or_add_rPr().rFonts.set(qn("w:hAnsi"), FONT)
    run._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), FONT)
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
    style._element.get_or_add_rPr().rFonts.set(qn("w:ascii"), FONT)
    style._element.get_or_add_rPr().rFonts.set(qn("w:hAnsi"), FONT)
    style._element.get_or_add_rPr().rFonts.set(qn("w:eastAsia"), FONT)
    style.paragraph_format.space_before = Pt(before)
    style.paragraph_format.space_after = Pt(after)
    style.paragraph_format.line_spacing = line


def keep_with_next(paragraph):
    ppr = paragraph._p.get_or_add_pPr()
    if ppr.find(qn("w:keepNext")) is None:
        ppr.append(OxmlElement("w:keepNext"))


def add_heading(doc, text, level=1):
    p = doc.add_paragraph(style=f"Heading {level}")
    p.add_run(text)
    keep_with_next(p)
    return p


def add_body(doc, text, *, bold_lead=None):
    p = doc.add_paragraph(style="Normal")
    if bold_lead and text.startswith(bold_lead):
        r1 = p.add_run(bold_lead)
        r1.bold = True
        p.add_run(text[len(bold_lead):])
    else:
        p.add_run(text)
    return p


def add_bullet(doc, text, level=0):
    style = "List Bullet" if level == 0 else "List Bullet 2"
    p = doc.add_paragraph(style=style)
    p.add_run(text)
    return p


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


def add_hyperlink(paragraph, label, url):
    rid = paragraph.part.relate_to(url, RT.HYPERLINK, is_external=True)
    hyperlink = OxmlElement("w:hyperlink")
    hyperlink.set(qn("r:id"), rid)
    run = OxmlElement("w:r")
    rpr = OxmlElement("w:rPr")
    rfonts = OxmlElement("w:rFonts")
    rfonts.set(qn("w:ascii"), FONT)
    rfonts.set(qn("w:hAnsi"), FONT)
    rfonts.set(qn("w:eastAsia"), FONT)
    size = OxmlElement("w:sz")
    size.set(qn("w:val"), "19")
    color = OxmlElement("w:color")
    color.set(qn("w:val"), "000000")
    underline = OxmlElement("w:u")
    underline.set(qn("w:val"), "single")
    rpr.extend([rfonts, size, color, underline])
    run.append(rpr)
    text = OxmlElement("w:t")
    text.text = label
    run.append(text)
    hyperlink.append(run)
    paragraph._p.append(hyperlink)


def add_reference(doc, number, title, url):
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(2.5)
    p.paragraph_format.line_spacing = 1.0
    r = p.add_run(f"[{number}] Onyx Documentation, “{title}”. ")
    set_run_font(r, 9.5)
    add_hyperlink(p, url, url)
    r2 = p.add_run(" (truy cập ngày 29/08/2026).")
    set_run_font(r2, 9.5)


doc = Document()
section = doc.sections[0]
section.page_width = Inches(8.5)
section.page_height = Inches(11)
section.top_margin = Inches(0.78)
section.bottom_margin = Inches(0.72)
section.left_margin = Inches(0.92)
section.right_margin = Inches(0.92)
section.header_distance = Inches(0.3)
section.footer_distance = Inches(0.35)

doc.core_properties.title = "Đề xuất ứng dụng Agentic AI trên nền tảng Onyx"
doc.core_properties.subject = "AI Workspace và các AI Agent chuyên biệt"
doc.core_properties.author = ""
doc.core_properties.keywords = "Onyx, Agentic AI, AI Workspace, Custom Agents, MCP, RAG"

configure_style(doc.styles["Normal"], 11.5, before=0, after=4.5, line=1.15)
doc.styles["Normal"].paragraph_format.alignment = WD_ALIGN_PARAGRAPH.LEFT
configure_style(doc.styles["Heading 1"], 12, bold=True, before=8, after=4, line=1.05)
configure_style(doc.styles["Heading 2"], 11.5, bold=True, before=6, after=3, line=1.05)
configure_style(doc.styles["List Bullet"], 11.5, before=0, after=2.5, line=1.12)
configure_style(doc.styles["List Bullet 2"], 11.5, before=0, after=2.5, line=1.12)
doc.styles["List Bullet"].paragraph_format.left_indent = Inches(0.28)
doc.styles["List Bullet"].paragraph_format.first_line_indent = Inches(-0.18)
doc.styles["List Bullet 2"].paragraph_format.left_indent = Inches(0.55)
doc.styles["List Bullet 2"].paragraph_format.first_line_indent = Inches(-0.18)

footer = section.footer
fp = footer.paragraphs[0]
fp.alignment = WD_ALIGN_PARAGRAPH.CENTER
fp.paragraph_format.space_before = Pt(0)
fp.paragraph_format.space_after = Pt(0)
r = fp.add_run("Trang: ")
set_run_font(r, 9)
add_field(fp, "PAGE", "1")
r = fp.add_run(" / ")
set_run_font(r, 9)
add_field(fp, "NUMPAGES", "5")

# Trang 1
date_p = doc.add_paragraph()
date_p.alignment = WD_ALIGN_PARAGRAPH.LEFT
date_p.paragraph_format.space_after = Pt(14)
date_r = date_p.add_run("29 tháng 8 năm 2026")
set_run_font(date_r, 11)

title = doc.add_paragraph()
title.alignment = WD_ALIGN_PARAGRAPH.CENTER
title.paragraph_format.space_after = Pt(4)
tr = title.add_run("ĐỀ XUẤT ỨNG DỤNG AGENTIC AI\nTRÊN NỀN TẢNG ONYX")
set_run_font(tr, 14, bold=True)

subtitle = doc.add_paragraph()
subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
subtitle.paragraph_format.space_after = Pt(12)
sr = subtitle.add_run("Giải quyết bài toán AI Workspace và các AI Agent chuyên biệt")
set_run_font(sr, 11.5, italic=True)

add_heading(doc, "TÓM TẮT", 1)
add_body(doc, "Tài liệu đề xuất sử dụng Onyx làm nền tảng AI Workspace cho doanh nghiệp và phát triển các AI Agent chuyên biệt cho Tài chính - Kế toán, Thuế, Pháp chế và Ngân hàng đầu tư. Tính Agentic AI của Onyx không chỉ nằm ở khả năng hỏi đáp bằng mô hình ngôn ngữ mà còn ở việc Agent có thể tự lựa chọn nguồn tri thức, thực hiện nhiều vòng tìm kiếm, sử dụng công cụ, chạy mã phân tích và tương tác với hệ thống bên ngoài. Onyx đã cung cấp phần lớn các năng lực cần thiết thông qua Connectors, Internal Search, Deep Research, Custom Agents, Code Execution và Actions; tuy nhiên các quy trình dài hạn, nhiều bước phê duyệt vẫn cần tích hợp thêm workflow hoặc dịch vụ nghiệp vụ bên ngoài.")

add_heading(doc, "1. GIỚI THIỆU", 1)
add_body(doc, "Trong doanh nghiệp, tri thức thường phân tán giữa tài liệu, thư mục dùng chung, hệ thống nghiệp vụ và kinh nghiệm của từng bộ phận. Người sử dụng mất nhiều thời gian để tìm đúng nguồn, kiểm tra tính cập nhật, đối chiếu dữ liệu và chuyển kết quả thành báo cáo hoặc hành động cụ thể. AI Workspace được kỳ vọng tạo ra một giao diện làm việc thống nhất, nơi người dùng có thể tra cứu, hỏi đáp, soạn thảo, tổng hợp, phân tích và thực hiện tác vụ trên dữ liệu nội bộ.")
add_body(doc, "Onyx là nền tảng AI mã nguồn mở tập trung vào tri thức doanh nghiệp. Hệ thống kết hợp tìm kiếm nội bộ, RAG, mô hình ngôn ngữ, Agent và công cụ hành động trong một giao diện hội thoại. Tài liệu này phân tích cách các năng lực Agentic AI của Onyx được áp dụng để giải quyết hai bài toán: xây dựng AI Workspace dùng chung và phát triển các Agent nghiệp vụ chuyên biệt.")

add_heading(doc, "2. THUẬT NGỮ VÀ TỪ VIẾT TẮT", 1)
add_body(doc, "Agentic AI: hệ thống AI có khả năng tiếp nhận mục tiêu, tự lựa chọn dữ liệu và công cụ, thực hiện nhiều bước xử lý, đánh giá kết quả trung gian và hoàn thành nhiệm vụ trong phạm vi được kiểm soát.")
add_body(doc, "RAG (Retrieval-Augmented Generation): kỹ thuật truy xuất tài liệu liên quan trước khi mô hình tạo câu trả lời, giúp kết quả dựa trên bằng chứng nội bộ.")
add_body(doc, "MCP (Model Context Protocol): giao thức cho phép Agent kết nối và gọi các công cụ hoặc dịch vụ bên ngoài theo một hợp đồng thống nhất.")

# Phần nội dung tiếp theo
add_heading(doc, "3. PHÁT BIỂU BÀI TOÁN", 1)
add_body(doc, "Bài toán thứ nhất yêu cầu AI Workspace hỗ trợ tra cứu thông tin, quy định và dữ liệu nội bộ; hỏi đáp; soạn thảo, tổng hợp, phân tích và thực hiện các tác vụ AI khác nhằm nâng cao năng suất, chất lượng và tốc độ công việc. Nếu hệ thống chỉ tìm một tài liệu rồi sinh câu trả lời, nó mới dừng ở chatbot hoặc RAG. Để mang tính agentic, hệ thống phải biết xác định mục tiêu, tìm kiếm nhiều vòng, gọi công cụ khi cần, kiểm tra bằng chứng và tạo ra sản phẩm công việc hoàn chỉnh.")
add_body(doc, "Bài toán thứ hai yêu cầu phát triển các AI Agent chuyên biệt cho Tài chính - Kế toán, Thuế, Pháp chế và Ngân hàng đầu tư. Mỗi Agent phải tuân theo nguồn tri thức, công cụ, quy trình nghiệp vụ và yêu cầu bảo mật riêng. Một Agent chỉ có prompt và bộ tài liệu riêng vẫn là trợ lý chuyên ngành; Agent chỉ thực sự mang tính agentic khi có thể sử dụng dữ liệu và công cụ để hoàn thành một quy trình nhiều bước.")

add_heading(doc, "4. MỤC TIÊU", 1)
add_body(doc, "Đề xuất hướng tới bốn mục tiêu chính:")
add_bullet(doc, "Cung cấp một AI Workspace thống nhất để người dùng làm việc với tri thức nội bộ qua ngôn ngữ tự nhiên.")
add_bullet(doc, "Cho phép Agent chủ động tìm kiếm, phân tích, sử dụng công cụ và tạo sản phẩm công việc thay vì chỉ trả lời câu hỏi.")
add_bullet(doc, "Cấu hình Agent chuyên biệt theo từng khối nghiệp vụ bằng Instructions, Knowledge và Actions.")
add_bullet(doc, "Duy trì giới hạn truy cập dữ liệu và hành động dựa trên quyền của người dùng và nguồn dữ liệu.")

add_heading(doc, "5. TÍNH AGENTIC AI CỦA ONYX", 1)
add_body(doc, "Onyx tổ chức Agent theo ba thành phần cốt lõi: Instructions xác định mục tiêu và cách ứng xử; Knowledge xác định nguồn tri thức; Actions xác định các công cụ mà Agent được phép sử dụng [3]. Trong phiên làm việc, Agent có thể tự quyết định khi nào cần Internal Search, Web Search, Code Execution hoặc Custom Action. Deep Research cho phép thực hiện nhiều chu kỳ suy luận, nghiên cứu và hành động đối với câu hỏi phức tạp [2].")

add_heading(doc, "5.1. Chu trình Agentic AI", 2)
add_body(doc, "Chu trình xử lý điển hình của Onyx gồm: tiếp nhận yêu cầu; xác định nguồn cần tìm; truy xuất bằng chứng; lựa chọn Action; xử lý kết quả; tạo câu trả lời hoặc sản phẩm đầu ra. Khả năng lựa chọn và phối hợp các bước này làm cho Onyx vượt khỏi mô hình hỏi - đáp một lượt. Tuy nhiên, mức độ tự chủ phụ thuộc vào model, Instructions, công cụ được cấp và giới hạn bảo mật do quản trị viên cấu hình.")

# Phần nội dung tiếp theo
add_heading(doc, "5.2. Áp dụng cho bài toán AI Workspace", 2)
add_body(doc, "Tra cứu tri thức nội bộ. Onyx sử dụng Connectors để đồng bộ tài liệu và metadata từ các ứng dụng doanh nghiệp. Dữ liệu được lập chỉ mục phục vụ Internal Search và RAG. Khi người dùng đặt câu hỏi, Agent có thể tìm trong kho tri thức, chọn bằng chứng liên quan và trả lời kèm nguồn trích dẫn [1], [5].")
add_body(doc, "Hỏi đáp và nghiên cứu nhiều bước. Trong chế độ Chat, người dùng có thể kết hợp file, URL, Internal Search và các Actions. Khi bật Deep Research, mô hình thực hiện nhiều chu kỳ suy luận, tìm kiếm và hành động thay vì chỉ tạo một câu trả lời duy nhất. Cách tiếp cận này phù hợp với yêu cầu tổng hợp nhiều quy định, đối chiếu báo cáo hoặc giải quyết câu hỏi chưa có đủ bằng chứng trong lần tìm kiếm đầu tiên [2].")
add_body(doc, "Soạn thảo và tổng hợp. Agent có thể sử dụng tài liệu đã lập chỉ mục và file được cung cấp để tóm tắt, so sánh, lập dàn ý và soạn nội dung. Craft mở rộng khả năng này bằng một AI coding agent chạy trong sandbox, cho phép tạo tài liệu, bản trình bày, dashboard và ứng dụng web từ tri thức doanh nghiệp. Craft hiện được Onyx công bố ở trạng thái beta [7].")
add_body(doc, "Phân tích dữ liệu. Code Execution cho phép mô hình tự viết và chạy Python trong môi trường sandbox. Agent có thể xử lý file, tính toán chỉ số, phân tích dữ liệu và tạo biểu đồ. Việc Agent tự xác định khi nào cần dùng mã tính toán thay vì chỉ suy luận bằng văn bản là một biểu hiện quan trọng của Agentic AI [6].")
add_body(doc, "Thực hiện tác vụ. Onyx hỗ trợ Custom Actions thông qua OpenAPI và MCP. Khi được cấp quyền, Agent có thể truy vấn API, kiểm tra trạng thái, tạo hoặc cập nhật bản ghi và kích hoạt quy trình bên ngoài. Onyx cho phép dùng thông tin xác thực dùng chung hoặc xác thực riêng theo từng người dùng, giúp Action phản ánh quyền thực tế của người thực hiện [4].")

add_heading(doc, "5.3. Giá trị đối với người sử dụng", 2)
add_body(doc, "Với các năng lực trên, người dùng không cần tách riêng các bước tìm tài liệu, tải dữ liệu, tính toán và viết báo cáo. Agent có thể phối hợp các bước trong một phiên làm việc, qua đó rút ngắn thời gian xử lý, tăng khả năng kiểm chứng và giảm thao tác thủ công. Kết quả vẫn cần được người dùng đánh giá trước khi phát hành hoặc thực hiện hành động có ảnh hưởng.")

# Phần nội dung tiếp theo
add_heading(doc, "5.4. Áp dụng cho các AI Agent chuyên biệt", 2)
add_body(doc, "Custom Agents cho phép tạo các Agent dùng lặp lại và chia sẻ cho người dùng hoặc nhóm. Mỗi Agent có Instructions, Knowledge và Actions riêng [3]. Nhờ đó cùng một nền tảng Onyx có thể cung cấp nhiều Agent nghiệp vụ mà không để tất cả Agent truy cập chung một tập dữ liệu hoặc công cụ.")
add_body(doc, "Agent Tài chính - Kế toán có thể truy xuất báo cáo, sử dụng Code Execution để đối chiếu số liệu, phân tích biến động và soạn báo cáo. Khi được tích hợp với ERP hoặc phần mềm kế toán qua MCP/OpenAPI, Agent còn có thể lấy dữ liệu thời gian thực hoặc chuẩn bị giao dịch để người dùng kiểm tra.")
add_body(doc, "Agent Thuế có thể tra cứu văn bản áp dụng theo thời điểm, đối chiếu quy định với hồ sơ, tính toán nghĩa vụ và cảnh báo dữ liệu thiếu. Instructions cần quy định rõ cách xử lý nguồn mâu thuẫn và yêu cầu chuyên viên xác nhận trước khi phát hành kết luận.")
add_body(doc, "Agent Pháp chế có thể rà soát hợp đồng, so sánh điều khoản với mẫu chuẩn, phát hiện rủi ro và đề xuất sửa đổi. Onyx sử dụng Legal Reviewer như một ví dụ điển hình của Custom Agent [3]. Agent Ngân hàng đầu tư có thể tổng hợp dữ liệu doanh nghiệp, chạy phân tích tài chính, phát hiện khoảng trống thông tin và hỗ trợ xây dựng investment memo.")

add_heading(doc, "5.5. Bảo mật và kiểm soát", 2)
add_body(doc, "Onyx cho phép giới hạn Knowledge theo connector, file, thư mục hoặc Document Set; Agent có thể được chia sẻ theo người dùng và nhóm. Connectors có các chế độ Public, Private và Auto Sync Permissions. Với connector hỗ trợ đồng bộ quyền, hệ thống duy trì ACL từ nguồn và chỉ cho người dùng truy cập tài liệu họ được phép xem [5].")
add_body(doc, "Đối với dữ liệu nhạy cảm, Agent cần được gắn nguồn tri thức cụ thể, không sử dụng connector Public và không mặc định tìm trên toàn bộ dữ liệu. Action nên ưu tiên xác thực theo từng người dùng; chỉ các endpoint và công cụ cần thiết mới được công bố cho Agent. Các thao tác ghi, gửi hoặc cập nhật dữ liệu cần có bước xác nhận của con người hoặc được điều phối bởi hệ thống nghiệp vụ bên ngoài.")

add_heading(doc, "6. PHƯƠNG PHÁP ÁP DỤNG", 1)
add_body(doc, "Việc áp dụng nên bắt đầu từ AI Workspace dùng chung, sau đó phát triển Agent chuyên biệt theo từng use case đo lường được. Mỗi Agent cần xác định rõ mục tiêu, nguồn dữ liệu, công cụ, người dùng, điều kiện dừng và tiêu chí đánh giá. Doanh nghiệp nên ưu tiên một quy trình có dữ liệu sẵn sàng, giá trị cao và rủi ro có thể kiểm soát trước khi mở rộng.")

# Phần nội dung tiếp theo
add_heading(doc, "7. ĐO LƯỜNG", 1)
add_body(doc, "Hiệu quả của hệ thống không nên được đo bằng số lượng Agent hoặc số lượt hội thoại. Các chỉ số phù hợp gồm: tỷ lệ câu trả lời có bằng chứng đúng; tỷ lệ hoàn thành nhiệm vụ; thời gian xử lý tiết kiệm; tỷ lệ người dùng phải sửa kết quả; tỷ lệ Action thất bại; số vi phạm quyền truy cập; chi phí và độ trễ trên mỗi nhiệm vụ. Đối với từng Agent nghiệp vụ cần có bộ tình huống kiểm thử riêng và chuyên gia của đơn vị xác nhận kết quả.")

add_heading(doc, "8. GIỚI HẠN", 1)
add_body(doc, "Onyx đã đáp ứng tốt AI Workspace, nghiên cứu nhiều bước, Code Execution, Custom Agents và tool calling. Tuy nhiên, tài liệu chính thức hiện vẫn ghi Workflows là “Coming Soon”, bao gồm tác vụ bất đồng bộ, xử lý hàng loạt, thông báo phê duyệt và báo cáo công việc nền [8]. Vì vậy, quy trình dài hạn, nhiều cấp phê duyệt, có trạng thái bền vững hoặc nhiều nhánh ngoại lệ cần kết hợp với workflow engine, MCP server hoặc API nghiệp vụ bên ngoài.")

add_heading(doc, "KẾT LUẬN", 1)
add_body(doc, "Onyx có khả năng giải quyết phần lớn hai bài toán được đề xuất. Đối với AI Workspace, hệ thống kết hợp tri thức nội bộ, tìm kiếm, Deep Research, Code Execution và Actions để hỗ trợ toàn bộ chuỗi từ tra cứu đến tạo sản phẩm công việc. Đối với Agent chuyên biệt, Onyx cung cấp mô hình Instructions - Knowledge - Actions cùng các cơ chế chia sẻ và giới hạn dữ liệu. Tính Agentic AI nằm ở khả năng Agent tự lựa chọn bằng chứng và công cụ để hoàn thành mục tiêu nhiều bước; phần workflow nghiệp vụ phức tạp vẫn cần được thiết kế và tích hợp riêng.")

add_heading(doc, "TÀI LIỆU THAM KHẢO", 1)
add_reference(doc, 1, "Welcome to Onyx", "https://docs.onyx.app/welcome")
add_reference(doc, 2, "Chat UI", "https://docs.onyx.app/overview/core_features/chat")
add_reference(doc, 3, "Custom Agents", "https://docs.onyx.app/overview/core_features/agents")
add_reference(doc, 4, "Actions & MCP", "https://docs.onyx.app/overview/core_features/actions")
add_reference(doc, 5, "Connectors Overview", "https://docs.onyx.app/admins/connectors/overview")
add_reference(doc, 6, "Code Execution", "https://docs.onyx.app/overview/core_features/code_interpreter")
add_reference(doc, 7, "Craft (beta)", "https://docs.onyx.app/overview/core_features/craft")
add_reference(doc, 8, "Workflows (Coming Soon)", "https://docs.onyx.app/overview/core_features/workflows")

for paragraph in doc.paragraphs:
    for run in paragraph.runs:
        if run.font.name is None:
            set_run_font(run)
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
