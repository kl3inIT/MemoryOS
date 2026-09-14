from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    BaseDocTemplate,
    Flowable,
    Frame,
    KeepTogether,
    PageBreak,
    PageTemplate,
    Paragraph,
    Spacer,
    Table,
    TableStyle,
)


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "output" / "pdf" / "onyx-agentic-ai-proposal.pdf"
OUTPUT.parent.mkdir(parents=True, exist_ok=True)

PAGE_W, PAGE_H = A4
MARGIN_X = 17 * mm
MARGIN_TOP = 15 * mm
MARGIN_BOTTOM = 16 * mm
CONTENT_W = PAGE_W - 2 * MARGIN_X

NAVY = colors.HexColor("#0B1F3A")
INK = colors.HexColor("#17243A")
BLUE = colors.HexColor("#2563EB")
CYAN = colors.HexColor("#0EA5B7")
GREEN = colors.HexColor("#0F9D7A")
AMBER = colors.HexColor("#D97706")
PURPLE = colors.HexColor("#6D4AFF")
RED = colors.HexColor("#C2413A")
MUTED = colors.HexColor("#5F6B7A")
LINE = colors.HexColor("#D9E1EC")
PALE_BLUE = colors.HexColor("#EEF4FF")
PALE_CYAN = colors.HexColor("#ECFAFC")
PALE_GREEN = colors.HexColor("#ECF8F4")
PALE_AMBER = colors.HexColor("#FFF7E8")
PALE_PURPLE = colors.HexColor("#F3F0FF")
PALE_RED = colors.HexColor("#FFF0EE")
OFFWHITE = colors.HexColor("#F7F9FC")
WHITE = colors.white


pdfmetrics.registerFont(TTFont("Arial", r"C:\Windows\Fonts\arial.ttf"))
pdfmetrics.registerFont(TTFont("Arial-Bold", r"C:\Windows\Fonts\arialbd.ttf"))
pdfmetrics.registerFont(TTFont("Arial-Italic", r"C:\Windows\Fonts\ariali.ttf"))


styles = getSampleStyleSheet()
BODY = ParagraphStyle(
    "Body",
    fontName="Arial",
    fontSize=9.2,
    leading=12.4,
    textColor=INK,
    spaceAfter=4,
)
SMALL = ParagraphStyle(
    "Small",
    parent=BODY,
    fontSize=7.6,
    leading=10.2,
    textColor=MUTED,
)
TINY = ParagraphStyle(
    "Tiny",
    parent=BODY,
    fontSize=6.7,
    leading=8.5,
    textColor=MUTED,
)
TITLE = ParagraphStyle(
    "Title",
    fontName="Arial-Bold",
    fontSize=24,
    leading=28,
    textColor=WHITE,
    spaceAfter=8,
)
SUBTITLE = ParagraphStyle(
    "Subtitle",
    fontName="Arial",
    fontSize=10.5,
    leading=14,
    textColor=colors.HexColor("#D6E3F7"),
)
EYEBROW = ParagraphStyle(
    "Eyebrow",
    fontName="Arial-Bold",
    fontSize=7.5,
    leading=9,
    textColor=colors.HexColor("#81C7FF"),
    uppercase=True,
    tracking=1.2,
)
H1 = ParagraphStyle(
    "H1",
    fontName="Arial-Bold",
    fontSize=17,
    leading=20,
    textColor=NAVY,
    spaceAfter=7,
)
H2 = ParagraphStyle(
    "H2",
    fontName="Arial-Bold",
    fontSize=11.2,
    leading=14,
    textColor=NAVY,
    spaceBefore=4,
    spaceAfter=5,
)
CARD_TITLE = ParagraphStyle(
    "CardTitle",
    fontName="Arial-Bold",
    fontSize=9.3,
    leading=11.5,
    textColor=NAVY,
)
CARD_BODY = ParagraphStyle(
    "CardBody",
    fontName="Arial",
    fontSize=8.2,
    leading=10.7,
    textColor=INK,
)
WHITE_CARD_TITLE = ParagraphStyle(
    "WhiteCardTitle",
    fontName="Arial-Bold",
    fontSize=9.5,
    leading=12,
    textColor=WHITE,
)
QUOTE = ParagraphStyle(
    "Quote",
    fontName="Arial-Bold",
    fontSize=10.2,
    leading=14.2,
    textColor=NAVY,
    alignment=TA_LEFT,
)
TABLE_HEAD = ParagraphStyle(
    "TableHead",
    fontName="Arial-Bold",
    fontSize=7.5,
    leading=9.2,
    textColor=WHITE,
)
TABLE_BODY = ParagraphStyle(
    "TableBody",
    fontName="Arial",
    fontSize=7.25,
    leading=9.3,
    textColor=INK,
)
TABLE_BODY_BOLD = ParagraphStyle(
    "TableBodyBold",
    parent=TABLE_BODY,
    fontName="Arial-Bold",
    textColor=NAVY,
)
REF = ParagraphStyle(
    "Ref",
    parent=TINY,
    fontSize=6.45,
    leading=8.2,
    textColor=MUTED,
)


def P(text, style=BODY):
    return Paragraph(text, style)


def bullet_lines(items, style=BODY, color=BLUE, gap=1.5):
    rows = []
    for item in items:
        dot = Table([[""]], colWidths=[5], rowHeights=[5])
        dot.setStyle(
            TableStyle(
                [
                    ("BACKGROUND", (0, 0), (-1, -1), color),
                    ("BOX", (0, 0), (-1, -1), 0, color),
                ]
            )
        )
        rows.append([dot, P(item, style)])
    table = Table(rows, colWidths=[12, CONTENT_W - 12], hAlign="LEFT")
    table.setStyle(
        TableStyle(
            [
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("TOPPADDING", (0, 0), (-1, -1), gap),
                ("BOTTOMPADDING", (0, 0), (-1, -1), gap),
                ("LEFTPADDING", (0, 0), (-1, -1), 0),
                ("RIGHTPADDING", (0, 0), (0, -1), 6),
                ("RIGHTPADDING", (1, 0), (1, -1), 0),
            ]
        )
    )
    return table


def problem_card(number, title, body, color, background):
    number_style = ParagraphStyle(
        f"Number{number}",
        fontName="Arial-Bold",
        fontSize=18,
        leading=20,
        textColor=WHITE,
        alignment=TA_CENTER,
    )
    number_box = Table([[P(number, number_style)]], colWidths=[34], rowHeights=[34])
    number_box.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), color),
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("ALIGN", (0, 0), (-1, -1), "CENTER"),
            ]
        )
    )
    content = [P(title, CARD_TITLE), Spacer(1, 3), P(body, CARD_BODY)]
    table = Table([[number_box, content]], colWidths=[48, CONTENT_W - 48])
    table.setStyle(
        TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), background),
                ("BOX", (0, 0), (-1, -1), 0.8, LINE),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (0, 0), 9),
                ("RIGHTPADDING", (0, 0), (0, 0), 5),
                ("TOPPADDING", (0, 0), (-1, -1), 10),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 10),
                ("LEFTPADDING", (1, 0), (1, 0), 4),
                ("RIGHTPADDING", (1, 0), (1, 0), 12),
            ]
        )
    )
    return table


def info_box(title, body, color=BLUE, background=PALE_BLUE):
    return Table(
        [[P(title, CARD_TITLE), P(body, CARD_BODY)]],
        colWidths=[112, CONTENT_W - 112],
        style=TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), background),
                ("LINEBEFORE", (0, 0), (0, 0), 4, color),
                ("BOX", (0, 0), (-1, -1), 0.7, LINE),
                ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ("LEFTPADDING", (0, 0), (-1, -1), 10),
                ("RIGHTPADDING", (0, 0), (-1, -1), 10),
                ("TOPPADDING", (0, 0), (-1, -1), 8),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
            ]
        ),
    )


class AgentLoop(Flowable):
    def __init__(self, width):
        super().__init__()
        self.width = width
        self.height = 58

    def draw(self):
        c = self.canv
        labels = [
            ("Mục tiêu", BLUE),
            ("Lập kế hoạch", PURPLE),
            ("Tìm tri thức", CYAN),
            ("Dùng công cụ", AMBER),
            ("Tự kiểm tra", GREEN),
            ("Kết quả", NAVY),
        ]
        gap = 8
        box_w = (self.width - gap * (len(labels) - 1)) / len(labels)
        y = 15
        for i, (label, color) in enumerate(labels):
            x = i * (box_w + gap)
            c.setFillColor(color)
            c.roundRect(x, y, box_w, 28, 5, fill=1, stroke=0)
            c.setFillColor(WHITE)
            c.setFont("Arial-Bold", 6.9)
            c.drawCentredString(x + box_w / 2, y + 10.2, label)
            if i < len(labels) - 1:
                ax = x + box_w + 1.5
                ay = y + 14
                c.setStrokeColor(colors.HexColor("#AAB6C8"))
                c.setLineWidth(1.2)
                c.line(ax, ay, ax + gap - 3, ay)
                c.line(ax + gap - 5.5, ay + 2.6, ax + gap - 3, ay)
                c.line(ax + gap - 5.5, ay - 2.6, ax + gap - 3, ay)
        c.setFillColor(MUTED)
        c.setFont("Arial-Italic", 6.7)
        c.drawString(0, 1, "Agent tự lựa chọn bước tiếp theo dựa trên mục tiêu, bằng chứng và kết quả trung gian.")


class CapabilityStack(Flowable):
    def __init__(self, width):
        super().__init__()
        self.width = width
        self.height = 164

    def draw(self):
        c = self.canv
        layers = [
            ("TRẢI NGHIỆM", "Chat  |  Deep Research  |  Craft", NAVY, WHITE),
            ("AGENT", "Instructions  |  Knowledge  |  Actions  |  Tool selection", PURPLE, WHITE),
            ("TRI THỨC", "Connectors  |  Internal Search  |  Citations  |  Code Execution", CYAN, WHITE),
            ("TÍCH HỢP", "MCP  |  OpenAPI  |  OAuth / xác thực theo người dùng", BLUE, WHITE),
            ("KIỂM SOÁT", "Users & Groups  |  Connector ACL  |  Knowledge scope", colors.HexColor("#334155"), WHITE),
        ]
        y = self.height - 29
        for label, text, bg, fg in layers:
            c.setFillColor(bg)
            c.roundRect(0, y, self.width, 25, 5, fill=1, stroke=0)
            c.setFillColor(colors.Color(fg.red, fg.green, fg.blue, alpha=0.72))
            c.setFont("Arial-Bold", 6.5)
            c.drawString(10, y + 9, label)
            c.setFillColor(fg)
            c.setFont("Arial-Bold", 7.5)
            c.drawString(88, y + 8.5, text)
            y -= 30


def capability_card(title, body, color, bg):
    return Table(
        [[P(title, CARD_TITLE), P(body, CARD_BODY)]],
        colWidths=[88, 154],
        style=TableStyle(
            [
                ("BACKGROUND", (0, 0), (-1, -1), bg),
                ("LINEABOVE", (0, 0), (-1, 0), 2.8, color),
                ("BOX", (0, 0), (-1, -1), 0.6, LINE),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 8),
                ("RIGHTPADDING", (0, 0), (-1, -1), 8),
                ("TOPPADDING", (0, 0), (-1, -1), 7),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
            ]
        ),
    )


def footer(canvas, doc):
    canvas.saveState()
    page = doc.page
    canvas.setStrokeColor(LINE)
    canvas.setLineWidth(0.5)
    canvas.line(MARGIN_X, 10.5 * mm, PAGE_W - MARGIN_X, 10.5 * mm)
    canvas.setFont("Arial", 6.6)
    canvas.setFillColor(MUTED)
    canvas.drawString(MARGIN_X, 6.7 * mm, "ONYX AGENTIC AI PROPOSAL  |  28.08.2026")
    canvas.drawRightString(PAGE_W - MARGIN_X, 6.7 * mm, f"{page:02d}")
    canvas.restoreState()


doc = BaseDocTemplate(
    str(OUTPUT),
    pagesize=A4,
    rightMargin=MARGIN_X,
    leftMargin=MARGIN_X,
    topMargin=MARGIN_TOP,
    bottomMargin=MARGIN_BOTTOM,
    title="Đề xuất năng lực Agentic AI của Onyx",
    author="OpenAI Codex",
    subject="Giải pháp cho AI Workspace và các AI Agent chuyên biệt",
)
frame = Frame(
    MARGIN_X,
    MARGIN_BOTTOM,
    CONTENT_W,
    PAGE_H - MARGIN_TOP - MARGIN_BOTTOM,
    id="main",
    leftPadding=0,
    rightPadding=0,
    topPadding=0,
    bottomPadding=0,
)
doc.addPageTemplates([PageTemplate(id="proposal", frames=[frame], onPage=footer)])

story = []

# PAGE 1 - Problems and executive answer
title_block = Table(
    [[
        [
            P("PROPOSAL  |  AI WORKSPACE & DOMAIN AGENTS", EYEBROW),
            Spacer(1, 10),
            P("ĐỀ XUẤT NĂNG LỰC<br/>AGENTIC AI CỦA ONYX", TITLE),
            P("Giải quyết bài toán tri thức doanh nghiệp và Agent nghiệp vụ chuyên biệt", SUBTITLE),
        ]
    ]],
    colWidths=[CONTENT_W],
)
title_block.setStyle(
    TableStyle(
        [
            ("BACKGROUND", (0, 0), (-1, -1), NAVY),
            ("BOX", (0, 0), (-1, -1), 0, NAVY),
            ("LEFTPADDING", (0, 0), (-1, -1), 20),
            ("RIGHTPADDING", (0, 0), (-1, -1), 20),
            ("TOPPADDING", (0, 0), (-1, -1), 18),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 18),
        ]
    )
)
story.extend([title_block, Spacer(1, 14), P("BÀI TOÁN ĐỀ XUẤT", H1)])

story.append(
    problem_card(
        "01",
        "AI Workspace cho tri thức và công việc nội bộ",
        "Hỗ trợ tra cứu thông tin, quy định và dữ liệu nội bộ; hỏi đáp; soạn thảo, tổng hợp, phân tích và thực hiện các tác vụ AI khác nhằm nâng cao năng suất, chất lượng và tốc độ công việc.",
        BLUE,
        PALE_BLUE,
    )
)
story.append(Spacer(1, 8))
story.append(
    problem_card(
        "02",
        "AI Agent chuyên biệt theo khối nghiệp vụ",
        "Phát triển Agent cho Tài chính - Kế toán, Thuế, Pháp chế và Ngân hàng đầu tư. Mỗi Agent được thiết kế theo mục tiêu, quy trình nghiệp vụ, nguồn dữ liệu, công cụ và yêu cầu bảo mật riêng.",
        PURPLE,
        PALE_PURPLE,
    )
)
story.extend([Spacer(1, 12), P("KẾT LUẬN ĐIỀU HÀNH", H2)])
exec_rows = [
    [P("Bài toán 1", WHITE_CARD_TITLE), P("Onyx đáp ứng phần lớn bằng Connectors, Internal Search, Chat, Deep Research, Code Execution, Craft và Actions.", CARD_BODY)],
    [P("Bài toán 2", WHITE_CARD_TITLE), P("Onyx cung cấp nền tảng Custom Agent với Instructions, Knowledge, Actions và phạm vi chia sẻ theo người dùng/nhóm.", CARD_BODY)],
    [P("Điều kiện", WHITE_CARD_TITLE), P("Quy trình dài hạn, batch, nhiều cấp phê duyệt hoặc nhiều ngoại lệ vẫn cần lớp workflow/tích hợp bổ sung.", CARD_BODY)],
]
exec_table = Table(exec_rows, colWidths=[82, CONTENT_W - 82])
exec_table.setStyle(
    TableStyle(
        [
            ("BACKGROUND", (0, 0), (0, -1), NAVY),
            ("BACKGROUND", (1, 0), (1, -1), OFFWHITE),
            ("BOX", (0, 0), (-1, -1), 0.7, LINE),
            ("INNERGRID", (0, 0), (-1, -1), 0.5, LINE),
            ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
            ("LEFTPADDING", (0, 0), (-1, -1), 9),
            ("RIGHTPADDING", (0, 0), (-1, -1), 9),
            ("TOPPADDING", (0, 0), (-1, -1), 7),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
        ]
    )
)
story.append(exec_table)
story.extend(
    [
        Spacer(1, 10),
        Table(
            [[P("ĐỊNH NGHĨA", TABLE_HEAD), P("Agentic AI của Onyx là khả năng tự lựa chọn tri thức, nghiên cứu nhiều bước, sử dụng công cụ, phân tích kết quả và tạo sản phẩm hoặc hành động để hoàn thành mục tiêu người dùng.", QUOTE)]],
            colWidths=[68, CONTENT_W - 68],
            style=TableStyle(
                [
                    ("BACKGROUND", (0, 0), (0, 0), BLUE),
                    ("BACKGROUND", (1, 0), (1, 0), PALE_CYAN),
                    ("BOX", (0, 0), (-1, -1), 0.8, LINE),
                    ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                    ("LEFTPADDING", (0, 0), (-1, -1), 9),
                    ("RIGHTPADDING", (0, 0), (-1, -1), 9),
                    ("TOPPADDING", (0, 0), (-1, -1), 8),
                    ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
                ]
            ),
        ),
        PageBreak(),
    ]
)

# PAGE 2 - AI Workspace
story.extend(
    [
        P("01  |  AGENTIC AI CHO AI WORKSPACE", H1),
        P("Onyx chuyển trải nghiệm từ hỏi đáp đơn lẻ sang một vòng lặp tác nhân: hiểu mục tiêu, tìm bằng chứng, lựa chọn công cụ, kiểm tra kết quả và tạo đầu ra có thể sử dụng.", BODY),
        Spacer(1, 7),
    ]
)

left_cards = [
    capability_card("TRUY XUẤT", "Connectors đồng bộ dữ liệu; Internal Search tìm trong tri thức đã lập chỉ mục và trả lời kèm nguồn.", BLUE, PALE_BLUE),
    capability_card("NGHIÊN CỨU", "Deep Research thực hiện nhiều chu kỳ suy luận, tìm kiếm và hành động cho câu hỏi phức tạp.", PURPLE, PALE_PURPLE),
    capability_card("PHÂN TÍCH", "Code Execution cho phép Agent tự viết và chạy Python để tính toán, phân tích file và tạo biểu đồ.", GREEN, PALE_GREEN),
]
right_cards = [
    capability_card("SOẠN THẢO", "Chat và Craft sử dụng tri thức doanh nghiệp để tạo báo cáo, tài liệu, slide, dashboard hoặc ứng dụng web.", CYAN, PALE_CYAN),
    capability_card("HÀNH ĐỘNG", "MCP/OpenAPI Actions cho phép Agent truy vấn hoặc thay đổi trạng thái trong các hệ thống bên ngoài.", AMBER, PALE_AMBER),
    capability_card("KIỂM SOÁT", "ACL, Knowledge scope và xác thực theo người dùng giới hạn dữ liệu và công cụ mà Agent được phép sử dụng.", NAVY, OFFWHITE),
]
cards = Table([[left_cards, right_cards]], colWidths=[CONTENT_W / 2 - 4, CONTENT_W / 2 - 4], hAlign="LEFT")
cards.setStyle(
    TableStyle(
        [
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (0, 0), 0),
            ("RIGHTPADDING", (0, 0), (0, 0), 4),
            ("LEFTPADDING", (1, 0), (1, 0), 4),
            ("RIGHTPADDING", (1, 0), (1, 0), 0),
            ("TOPPADDING", (0, 0), (-1, -1), 0),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 0),
        ]
    )
)
story.extend([cards, Spacer(1, 10), P("VÒNG LẶP TÁC NHÂN", H2), AgentLoop(CONTENT_W), Spacer(1, 7)])

mapping_data = [
    [P("Yêu cầu", TABLE_HEAD), P("Năng lực Onyx", TABLE_HEAD), P("Biểu hiện Agentic AI", TABLE_HEAD)],
    [P("Tra cứu nội bộ", TABLE_BODY_BOLD), P("Connectors + Internal Search", TABLE_BODY), P("Tự chọn nguồn và truy vấn phù hợp với mục tiêu.", TABLE_BODY)],
    [P("Hỏi đáp", TABLE_BODY_BOLD), P("RAG + citations + Deep Research", TABLE_BODY), P("Tìm nhiều vòng, tổng hợp và đối chiếu bằng chứng.", TABLE_BODY)],
    [P("Soạn thảo / tổng hợp", TABLE_BODY_BOLD), P("Chat + Craft", TABLE_BODY), P("Tự thu thập dữ liệu và tạo sản phẩm công việc.", TABLE_BODY)],
    [P("Phân tích", TABLE_BODY_BOLD), P("Code Execution", TABLE_BODY), P("Tự viết/chạy code, tính toán và trực quan hóa.", TABLE_BODY)],
    [P("Thực hiện tác vụ", TABLE_BODY_BOLD), P("MCP + OpenAPI Actions", TABLE_BODY), P("Tự chọn và gọi công cụ trong phạm vi được cấp.", TABLE_BODY)],
]
mapping = Table(mapping_data, colWidths=[100, 145, CONTENT_W - 245], repeatRows=1)
mapping.setStyle(
    TableStyle(
        [
            ("BACKGROUND", (0, 0), (-1, 0), NAVY),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [WHITE, OFFWHITE]),
            ("GRID", (0, 0), (-1, -1), 0.5, LINE),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (-1, -1), 6),
            ("RIGHTPADDING", (0, 0), (-1, -1), 6),
            ("TOPPADDING", (0, 0), (-1, -1), 5),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ]
    )
)
story.extend(
    [
        mapping,
        Spacer(1, 7),
        P("Nguồn chính: Onyx Chat, Actions & MCP, Code Execution và Craft. Xem danh mục tài liệu tại trang 4.", SMALL),
        PageBreak(),
    ]
)

# PAGE 3 - Domain Agents
story.extend(
    [
        P("02  |  AGENT CHUYÊN BIỆT THEO NGHIỆP VỤ", H1),
        P("Onyx cho phép tạo Agent riêng cho từng khối bằng cách kết hợp mục tiêu và hướng dẫn, phạm vi tri thức, công cụ hành động và chính sách chia sẻ.", BODY),
        Spacer(1, 8),
    ]
)

pillars = Table(
    [[
        [P("INSTRUCTIONS", TABLE_HEAD), Spacer(1, 4), P("Vai trò, mục tiêu, nguyên tắc và cách xử lý nghiệp vụ.", CARD_BODY)],
        [P("KNOWLEDGE", TABLE_HEAD), Spacer(1, 4), P("Connector, thư mục, file hoặc Document Set được phép sử dụng.", CARD_BODY)],
        [P("ACTIONS", TABLE_HEAD), Spacer(1, 4), P("Built-in tool, MCP hoặc OpenAPI để truy vấn và thực hiện tác vụ.", CARD_BODY)],
    ]],
    colWidths=[CONTENT_W / 3 - 4, CONTENT_W / 3 - 4, CONTENT_W / 3 - 4],
)
pillars.setStyle(
    TableStyle(
        [
            ("BACKGROUND", (0, 0), (0, 0), PALE_BLUE),
            ("BACKGROUND", (1, 0), (1, 0), PALE_CYAN),
            ("BACKGROUND", (2, 0), (2, 0), PALE_PURPLE),
            ("LINEABOVE", (0, 0), (0, 0), 4, BLUE),
            ("LINEABOVE", (1, 0), (1, 0), 4, CYAN),
            ("LINEABOVE", (2, 0), (2, 0), 4, PURPLE),
            ("BOX", (0, 0), (-1, -1), 0.6, LINE),
            ("INNERGRID", (0, 0), (-1, -1), 0.6, LINE),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (-1, -1), 9),
            ("RIGHTPADDING", (0, 0), (-1, -1), 9),
            ("TOPPADDING", (0, 0), (-1, -1), 9),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 9),
        ]
    )
)
story.extend([pillars, Spacer(1, 11), P("ỨNG DỤNG CHO BỐN KHỐI ƯU TIÊN", H2)])

domain_data = [
    [P("Agent", TABLE_HEAD), P("Nhiệm vụ Agentic tiêu biểu", TABLE_HEAD), P("Tri thức và công cụ", TABLE_HEAD)],
    [P("Tài chính - Kế toán", TABLE_BODY_BOLD), P("Đối chiếu số liệu, phân tích biến động, phát hiện bất thường và lập báo cáo.", TABLE_BODY), P("Báo cáo tài chính, dữ liệu kỳ trước, ERP/kế toán, Code Execution.", TABLE_BODY)],
    [P("Thuế", TABLE_BODY_BOLD), P("Xác định quy định áp dụng, kiểm tra hồ sơ, tính toán và cảnh báo rủi ro.", TABLE_BODY), P("Văn bản theo hiệu lực, hồ sơ giao dịch, công cụ tính và API nghiệp vụ.", TABLE_BODY)],
    [P("Pháp chế", TABLE_BODY_BOLD), P("Rà soát hợp đồng, đối chiếu điều khoản, phát hiện rủi ro và đề xuất sửa đổi.", TABLE_BODY), P("Mẫu chuẩn, quy định nội bộ, văn bản pháp luật và kho hợp đồng.", TABLE_BODY)],
    [P("Ngân hàng đầu tư", TABLE_BODY_BOLD), P("Tổng hợp dữ liệu doanh nghiệp, phân tích tài chính và chuẩn bị investment memo.", TABLE_BODY), P("Hồ sơ doanh nghiệp, dữ liệu tài chính, nghiên cứu thị trường và mô hình tính.", TABLE_BODY)],
]
domain_table = Table(domain_data, colWidths=[105, 205, CONTENT_W - 310], repeatRows=1)
domain_table.setStyle(
    TableStyle(
        [
            ("BACKGROUND", (0, 0), (-1, 0), NAVY),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [WHITE, OFFWHITE]),
            ("GRID", (0, 0), (-1, -1), 0.5, LINE),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (-1, -1), 7),
            ("RIGHTPADDING", (0, 0), (-1, -1), 7),
            ("TOPPADDING", (0, 0), (-1, -1), 6),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
        ]
    )
)
story.extend([domain_table, Spacer(1, 10), P("BẢO MẬT VÀ KIỂM SOÁT", H2)])
security = Table(
    [[
        bullet_lines(
            [
                "Giới hạn Knowledge vào nguồn dữ liệu cần thiết cho từng Agent.",
                "Chia sẻ Agent theo người dùng hoặc nhóm phù hợp.",
            ],
            CARD_BODY,
            BLUE,
        ),
        bullet_lines(
            [
                "Ưu tiên OAuth hoặc xác thực riêng theo từng người dùng.",
                "Chỉ công bố MCP tool/API endpoint thực sự cần thiết.",
            ],
            CARD_BODY,
            PURPLE,
        ),
    ]],
    colWidths=[CONTENT_W / 2, CONTENT_W / 2],
)
security.setStyle(
    TableStyle(
        [
            ("BACKGROUND", (0, 0), (0, 0), PALE_BLUE),
            ("BACKGROUND", (1, 0), (1, 0), PALE_PURPLE),
            ("BOX", (0, 0), (-1, -1), 0.6, LINE),
            ("INNERGRID", (0, 0), (-1, -1), 0.6, LINE),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (-1, -1), 9),
            ("RIGHTPADDING", (0, 0), (-1, -1), 9),
            ("TOPPADDING", (0, 0), (-1, -1), 7),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
        ]
    )
)
story.extend(
    [
        security,
        Spacer(1, 9),
        info_box(
            "Ranh giới quan trọng",
            "Một Agent chỉ có prompt và bộ tài liệu riêng vẫn là trợ lý chuyên môn. Agent thực sự mang tính agentic khi chủ động tìm kiếm, sử dụng công cụ, kiểm tra kết quả và thực hiện nhiều bước để hoàn thành mục tiêu.",
            AMBER,
            PALE_AMBER,
        ),
        PageBreak(),
    ]
)

# PAGE 4 - Architecture, limits, conclusion, references
story.extend(
    [
        P("03  |  KIẾN TRÚC NĂNG LỰC VÀ GIỚI HẠN", H1),
        P("Các năng lực của Onyx tạo thành một chuỗi từ trải nghiệm người dùng, Agent, tri thức, tích hợp đến kiểm soát truy cập.", BODY),
        Spacer(1, 7),
        CapabilityStack(CONTENT_W),
        Spacer(1, 4),
        P("ONYX ĐÁP ỨNG ĐẾN ĐÂU?", H2),
    ]
)

coverage_data = [
    [P("Phạm vi", TABLE_HEAD), P("Mức đáp ứng", TABLE_HEAD), P("Nhận định", TABLE_HEAD)],
    [P("AI Workspace", TABLE_BODY_BOLD), P("Đáp ứng tốt", TABLE_BODY_BOLD), P("Tra cứu, hỏi đáp, nghiên cứu, phân tích, tạo đầu ra và gọi công cụ đều có nền tảng sẵn.", TABLE_BODY)],
    [P("Domain Agents", TABLE_BODY_BOLD), P("Đáp ứng có điều kiện", TABLE_BODY_BOLD), P("Có khung Agent đầy đủ; doanh nghiệp vẫn phải cấu hình tri thức, tool, quyền và logic nghiệp vụ.", TABLE_BODY)],
]
coverage = Table(coverage_data, colWidths=[105, 105, CONTENT_W - 210])
coverage.setStyle(
    TableStyle(
        [
            ("BACKGROUND", (0, 0), (-1, 0), NAVY),
            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [PALE_GREEN, PALE_AMBER]),
            ("GRID", (0, 0), (-1, -1), 0.5, LINE),
            ("VALIGN", (0, 0), (-1, -1), "TOP"),
            ("LEFTPADDING", (0, 0), (-1, -1), 7),
            ("RIGHTPADDING", (0, 0), (-1, -1), 7),
            ("TOPPADDING", (0, 0), (-1, -1), 6),
            ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
        ]
    )
)
story.extend(
    [
        coverage,
        Spacer(1, 9),
        info_box(
            "Giới hạn hiện tại",
            "Onyx Workflows vẫn được công bố là Coming Soon. Quy trình chạy dài hạn, theo lịch/sự kiện, batch, phê duyệt nhiều cấp, phục hồi sau lỗi hoặc nhiều ngoại lệ cần thêm workflow engine, MCP/API và cơ chế kiểm soát bên ngoài.",
            RED,
            PALE_RED,
        ),
        Spacer(1, 9),
        Table(
            [[P("KẾT LUẬN", TABLE_HEAD), P("Onyx đã có nền tảng Agentic AI đủ mạnh để giải quyết hai bài toán: một AI Workspace dựa trên tri thức doanh nghiệp và các Agent chuyên biệt theo nghiệp vụ. Giá trị agentic nằm ở khả năng tự tìm kiếm, nghiên cứu nhiều bước, sử dụng công cụ, phân tích và tạo đầu ra hoặc hành động. Phần doanh nghiệp phải bổ sung là dữ liệu, tích hợp, chính sách và workflow chuyên sâu.", QUOTE)]],
            colWidths=[65, CONTENT_W - 65],
            style=TableStyle(
                [
                    ("BACKGROUND", (0, 0), (0, 0), GREEN),
                    ("BACKGROUND", (1, 0), (1, 0), PALE_GREEN),
                    ("BOX", (0, 0), (-1, -1), 0.7, LINE),
                    ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                    ("LEFTPADDING", (0, 0), (-1, -1), 9),
                    ("RIGHTPADDING", (0, 0), (-1, -1), 9),
                    ("TOPPADDING", (0, 0), (-1, -1), 8),
                    ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
                ]
            ),
        ),
        Spacer(1, 9),
        P("TÀI LIỆU THAM CHIẾU CHÍNH THỨC", H2),
        P('[1] <link href="https://docs.onyx.app/overview/core_features/chat" color="#2563EB">Onyx Chat UI & Deep Research</link>  |  [2] <link href="https://docs.onyx.app/overview/core_features/agents" color="#2563EB">Custom Agents</link>  |  [3] <link href="https://docs.onyx.app/overview/core_features/actions" color="#2563EB">Actions & MCP</link>', REF),
        P('[4] <link href="https://docs.onyx.app/overview/core_features/code_interpreter" color="#2563EB">Code Execution</link>  |  [5] <link href="https://docs.onyx.app/overview/core_features/craft" color="#2563EB">Craft (beta)</link>  |  [6] <link href="https://docs.onyx.app/admins/connectors/overview" color="#2563EB">Connector permissions</link>  |  [7] <link href="https://docs.onyx.app/overview/core_features/workflows" color="#2563EB">Workflows (Coming Soon)</link>', REF),
    ]
)

doc.build(story)
print(OUTPUT)
