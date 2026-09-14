# -*- coding: utf-8 -*-
from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak

OUT = r"output/pdf/Bao_cao_vai_tro_AWS_services_MemoryOS.pdf"
pdfmetrics.registerFont(TTFont("Arial", r"C:\Windows\Fonts\arial.ttf"))
pdfmetrics.registerFont(TTFont("Arial-Bold", r"C:\Windows\Fonts\arialbd.ttf"))

PAGE_W, PAGE_H = A4
navy, light_blue, line = colors.HexColor("#132B4F"), colors.HexColor("#EAF2FB"), colors.HexColor("#C9D6E4")
text, muted = colors.HexColor("#1E293B"), colors.HexColor("#526275")
styles = getSampleStyleSheet()
styles.add(ParagraphStyle(name="ReportTitle", fontName="Arial-Bold", fontSize=19, leading=24, textColor=navy, alignment=TA_LEFT, spaceAfter=5))
styles.add(ParagraphStyle(name="SubTitle", fontName="Arial", fontSize=9.5, leading=14, textColor=muted, spaceAfter=10))
styles.add(ParagraphStyle(name="Section", fontName="Arial-Bold", fontSize=11.5, leading=15, textColor=navy, spaceBefore=3, spaceAfter=5))
styles.add(ParagraphStyle(name="Body", fontName="Arial", fontSize=8.6, leading=12, textColor=text))
styles.add(ParagraphStyle(name="Service", fontName="Arial-Bold", fontSize=8.4, leading=11.2, textColor=navy))
styles.add(ParagraphStyle(name="Cell", fontName="Arial", fontSize=8.2, leading=11.1, textColor=text))
styles.add(ParagraphStyle(name="Header", fontName="Arial-Bold", fontSize=8.2, leading=10, textColor=colors.white, alignment=TA_CENTER))

def p(value, style="Cell"):
    return Paragraph(value, styles[style])

def footer(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(line); canvas.setLineWidth(0.5)
    canvas.line(doc.leftMargin, 14 * mm, PAGE_W - doc.rightMargin, 14 * mm)
    canvas.setFont("Arial", 7.5); canvas.setFillColor(muted)
    canvas.drawString(doc.leftMargin, 9.5 * mm, "MemoryOS - Báo cáo vai trò các AWS services")
    canvas.drawRightString(PAGE_W - doc.rightMargin, 9.5 * mm, f"Trang {doc.page}")
    canvas.restoreState()

def make_table(rows):
    data = [[p("Nhóm", "Header"), p("AWS service", "Header"), p("Service dùng để làm gì", "Header"), p("Dữ liệu/tài sản liên quan", "Header")]]
    for group, service, purpose, assets in rows:
        data.append([p(group), p(service, "Service"), p(purpose), p(assets)])
    table = Table(data, colWidths=[25*mm, 37*mm, 63*mm, 47*mm], repeatRows=1, hAlign="LEFT")
    commands = [
        ("BACKGROUND", (0,0), (-1,0), navy), ("TEXTCOLOR", (0,0), (-1,0), colors.white),
        ("VALIGN", (0,0), (-1,-1), "TOP"), ("GRID", (0,0), (-1,-1), 0.35, line),
        ("LEFTPADDING", (0,0), (-1,-1), 5), ("RIGHTPADDING", (0,0), (-1,-1), 5),
        ("TOPPADDING", (0,0), (-1,-1), 5), ("BOTTOMPADDING", (0,0), (-1,-1), 5),
    ]
    for index in range(1, len(data)):
        if index % 2 == 0:
            commands.append(("BACKGROUND", (0,index), (-1,index), light_blue))
    table.setStyle(TableStyle(commands))
    return table

page1 = [
    ("Vận hành", "Amazon EC2 - Application", "Chạy backend MemoryOS, API, giao diện và luồng chat/RAG.", "Mã nguồn đã đóng gói, tiến trình ứng dụng và bộ nhớ tạm thời khi chạy."),
    ("Dữ liệu & tìm kiếm", "Amazon EC2 - Data/Search", "Chạy PostgreSQL và công cụ tìm kiếm/vector index như OpenSearch.", "Metadata tài liệu, phân quyền, embeddings và chỉ mục tìm kiếm."),
    ("Lưu file", "Amazon S3", "Lưu trữ file người dùng tải lên và file phục vụ xử lý ingestion.", "PDF, Word, Excel, ảnh, file gốc và bản sao file."),
    ("Sao lưu", "AWS Backup", "Tạo và quản lý bản sao lưu khôi phục cho ổ đĩa EC2.", "EBS snapshots của máy Application và Data/Search."),
    ("Giám sát", "Amazon CloudWatch", "Theo dõi sức khỏe hệ thống, thu thập log và phát cảnh báo khi có sự cố.", "Application log, error log, CPU/RAM metrics, dashboard và alarm."),
    ("Triển khai", "Amazon ECR", "Kho lưu Docker image để EC2 pull khi deploy, cập nhật hoặc scale.", "Image backend, frontend và worker ingestion/indexing; không lưu tài liệu hay API key."),
]
page2 = [
    ("Bảo mật", "AWS Key Management Service (KMS)", "Quản lý khóa mã hóa cho các tài nguyên AWS.", "Customer managed keys để mã hóa S3, EBS, Secrets Manager; không lưu dữ liệu nghiệp vụ."),
    ("Bảo mật", "AWS Secrets Manager", "Lưu và cấp phát thông tin bí mật cho ứng dụng một cách an toàn.", "Mật khẩu database, token OAuth, SMTP và credential dịch vụ ngoài."),
    ("Mạng", "Amazon VPC / NAT Gateway / Public IPv4", "Tạo mạng riêng, kết nối Internet có kiểm soát và IP công khai cho dịch vụ.", "Cấu hình mạng, route và luồng dữ liệu; không lưu dữ liệu người dùng."),
    ("Mở rộng", "Amazon EC2 - Scale buffer 30%", "Công suất EC2 dự phòng trung bình để đáp ứng lưu lượng tải tăng 30%.", "Tài nguyên xử lý bổ sung cho Application và Data/Search, không phải kho lưu trữ riêng."),
    ("AI tạo sinh", "Amazon Bedrock - Nova Lite", "Model AI tạo câu trả lời dựa trên các đoạn tài liệu được truy xuất.", "Prompt và context của phiên gọi model; tài liệu gốc vẫn được lưu tại S3/index."),
    ("AI ingestion", "Amazon Bedrock - Titan Embeddings V2", "Chuyển nội dung tài liệu thành vector phục vụ tìm kiếm theo ngữ nghĩa.", "Embedding vectors được lưu trong vector index trên Data/Search EC2."),
]

doc = SimpleDocTemplate(OUT, pagesize=A4, leftMargin=18*mm, rightMargin=18*mm, topMargin=16*mm, bottomMargin=21*mm, title="Báo cáo vai trò AWS services - MemoryOS", author="MemoryOS")
story = [
    Paragraph("Báo cáo vai trò AWS services", styles["ReportTitle"]),
    Paragraph("Phạm vi: mô tả dịch vụ dùng để làm gì và dữ liệu/tài sản liên quan trong hệ thống MemoryOS. Không bao gồm chi phí.", styles["SubTitle"]),
    Paragraph("1. Nền tảng vận hành và lưu trữ", styles["Section"]), make_table(page1), PageBreak(),
    Paragraph("Báo cáo vai trò AWS services", styles["ReportTitle"]),
    Paragraph("2. Bảo mật, mạng, khả năng mở rộng và AI", styles["Section"]), make_table(page2), Spacer(1, 9*mm),
    Paragraph("Ghi chú bảo mật", styles["Section"]),
    Paragraph("Amazon Bedrock nên được gọi từ EC2 bằng IAM Role, vì vậy không cần tự lưu Bedrock API key. Secrets Manager chỉ lưu credential của database và các dịch vụ ngoài khi cần.", styles["Body"]),
]
doc.build(story, onFirstPage=footer, onLaterPages=footer)
print(OUT)
