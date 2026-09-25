package io.memoryos.usage.report;

import io.memoryos.usage.report.UsageReportData.DailySpend;
import io.memoryos.usage.report.UsageReportData.NamedSpend;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import io.memoryos.shared.PdfText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Draws the usage report's review pack (Onyx {@code usage_report_pdf.py}) in Vietnamese. PDFBox has no layout engine,
 * so this class keeps a cursor and breaks pages itself. The PDF standard fonts cannot draw Vietnamese, so the
 * application's typeface is embedded.
 */
public final class UsageReportPdf {
    private static final Color INK = new Color(0x1c1c1c);
    private static final Color BODY = new Color(0x54545d);
    private static final Color ACCENT = new Color(0x286df8);
    private static final Color HAIRLINE = new Color(0xe6e6e9);
    private static final Color SURFACE = new Color(0xf0f0f1);

    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 60;
    private static final float WIDTH = PAGE.getWidth() - 2 * MARGIN;
    private static final float TOP = PAGE.getHeight() - MARGIN;
    private static final float BOTTOM = MARGIN + 18;
    private static final int MAX_AXIS_LABELS = 10;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("dd/MM");
    private static final DecimalFormatSymbols VI = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN"));

    private static final Map<String, String> FLOWS = Map.of(
            "CHAT", "Trò chuyện",
            "CHAT_NAMING", "Đặt tên cuộc trò chuyện",
            "DEEP_RESEARCH", "Deep research",
            "EMBEDDING_QUERY", "Embedding khi tìm kiếm",
            "EMBEDDING_INDEXING", "Embedding khi lập chỉ mục",
            "IMAGE_GENERATION", "Tạo ảnh",
            "IMAGE_EDIT", "Sửa ảnh",
            "SPEECH_TO_TEXT", "Chuyển giọng nói thành chữ",
            "TEXT_TO_SPEECH", "Đọc văn bản");
    private static final Map<String, String> BOUNDARIES = Map.of(
            "INTERNAL", "Nội bộ",
            "EXTERNAL", "Bên ngoài",
            UsageReportData.NO_BOUNDARY, "Ngoài danh mục model");

    private UsageReportPdf() {}

    public static byte[] render(UsageReportData data) {
        try (var doc = new PDDocument()) {
            var page = new Canvas(doc);
            if (data.hasUsage()) full(page, data);
            else empty(page, data);
            page.finish();
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Báo cáo sử dụng AI · " + data.tenantName());
            info.setAuthor(data.tenantName());
            info.setCreator("MemoryOS");
            var out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void empty(Canvas page, UsageReportData data) throws IOException {
        cover(page, data);
        page.gap(24);
        page.paragraph("Không có usage nào được ghi nhận trong kỳ này.", page.regular, 11.5f, INK, 17);
    }

    private static void full(Canvas page, UsageReportData data) throws IOException {
        cover(page, data);
        page.gap(26);
        page.rule(INK);
        page.gap(22);
        headline(page, data);
        page.gap(28);
        if (data.members() > 0) {
            seats(page, data);
            page.gap(26);
        }
        page.paragraph(summary(data), page.regular, 11.5f, INK, 17);
        if (data.unknownCostCalls() > 0) {
            page.gap(8);
            page.paragraph(count(data.unknownCostCalls()) + " lượt gọi chưa có giá hoặc không báo số token, nên không tính vào chi phí.",
                    page.regular, 9.5f, BODY, 13);
        }
        // Onyx closes the pack with this note; on the cover it is read before the figures and never strands on a page.
        page.gap(8);
        page.paragraph("Chi phí của tác vụ hệ thống và của người đã rời Tenant được tính vào mọi tổng, nhưng không tính là một "
                        + "người dùng. Chi phí tính bằng USD theo giá model tại thời điểm gọi. Ngày theo giờ UTC.",
                page.regular, 9.5f, BODY, 13);
        if (!data.byModel().isEmpty()) {
            page.gap(24);
            page.label("Model tốn nhiều nhất");
            spendTable(page, "Model", data.byModel().subList(0, Math.min(3, data.byModel().size())), Function.identity());
        }

        page.newPage();
        section(page, "Mức độ sử dụng", "Số người khác nhau dùng AI mỗi ngày.", 170);
        lineChart(page, data.daily(), point -> point.activePeople(), INK, false);
        section(page, "Chi phí theo thời gian", "Chi phí mỗi ngày trên mọi model và tác vụ.", 170);
        lineChart(page, data.daily(), point -> point.cost().doubleValue(), ACCENT, true);
        section(page, "Chi phí theo model", "Chi phí của từng model trong kỳ.", 90);
        barTable(page, "Model", data.byModel(), Function.identity());
        section(page, "Người dùng nhiều nhất", "Những người tạo ra phần lớn chi phí.", 90);
        spendTable(page, "Người dùng", data.topPeople(),
                name -> UsageReportData.SYSTEM.equals(name) ? "Tác vụ hệ thống" : name);
        section(page, "Chi phí theo tác vụ", "AI được dùng vào việc gì.", 90);
        spendTable(page, "Tác vụ", data.byFlow(), name -> FLOWS.getOrDefault(name, name));
        if (!data.byGroup().isEmpty()) {
            section(page, "Chi phí theo Group",
                    "Một người thuộc nhiều Group được tính vào từng Group, nên tổng các Group có thể lớn hơn tổng chi phí.", 90);
            spendTable(page, "Group", data.byGroup(), Function.identity());
        }
        section(page, "Nội bộ và Bên ngoài",
                "Chi phí theo nơi model chạy: nhà cung cấp Nội bộ, Bên ngoài, hoặc ngoài danh mục model như embedding.", 90);
        spendTable(page, "Ranh giới dữ liệu", data.byBoundary(), name -> BOUNDARIES.getOrDefault(name, name));
        idle(page, data);
    }

    private static void cover(Canvas page, UsageReportData data) throws IOException {
        page.line(data.tenantName(), page.bold, 19, INK, 23);
        page.gap(24);
        page.line("Báo cáo sử dụng AI", page.bold, 30, INK, 34);
        page.gap(4);
        page.line(DAY.format(data.from()) + " – " + DAY.format(data.to()) + " (UTC)", page.regular, 13, BODY, 18);
    }

    private static void headline(Canvas page, UsageReportData data) throws IOException {
        String[][] figures = {
                {count(data.activePeople()), "Người đang dùng"},
                {money(data.totalCost()), "Tổng chi phí"},
                {money(data.costPerActivePerson()), "Chi phí mỗi người dùng"}};
        page.ensure(44);
        float column = WIDTH / 3;
        for (int i = 0; i < figures.length; i++) {
            page.text(MARGIN + i * column, page.y - 22, page.bold, 22, INK, page.fit(figures[i][0], page.bold, 22, column - 12));
            page.text(MARGIN + i * column, page.y - 38, page.regular, 9, BODY, figures[i][1]);
        }
        page.y -= 42;
    }

    /** Onyx's seat meter: members who used AI against active members. */
    private static void seats(Canvas page, UsageReportData data) throws IOException {
        page.ensure(56);
        int active = data.activeMembers();
        int members = data.members();
        double ratio = Math.min(1.0, (double) active / members);
        page.text(MARGIN, page.y - 11, page.bold, 11, INK, active + "/" + members + " thành viên đã dùng AI");
        float barY = page.y - 34;
        page.fill(MARGIN, barY, WIDTH, 12, SURFACE);
        if (ratio > 0) page.fill(MARGIN, barY, Math.max(3f, (float) (WIDTH * ratio)), 12, ACCENT);
        int idle = members - active;
        String caption = percent(ratio) + " đang dùng" + (idle > 0 ? " · " + idle + " thành viên chưa dùng" : "");
        int departed = data.activePeople() - active;
        if (departed > 0) caption += " · " + departed + " người không còn là thành viên vẫn có usage";
        page.text(MARGIN, barY - 14, page.regular, 9, BODY, caption);
        page.y = barY - 18;
    }

    private static String summary(UsageReportData data) {
        var parts = new ArrayList<String>();
        parts.add(count(data.activePeople()) + " người đã dùng AI của " + data.tenantName() + " trong kỳ này, tổng chi phí "
                + money(data.totalCost()) + ".");
        if (!data.byFlow().isEmpty() && !data.byFlow().getFirst().isOther())
            parts.add("Nhiều chi phí nhất là " + FLOWS.getOrDefault(data.byFlow().getFirst().name(), data.byFlow().getFirst().name())
                    .toLowerCase(Locale.forLanguageTag("vi")) + ".");
        int idle = data.idleMembers().size();
        if (idle > 0 && data.members() > 0)
            parts.add(idle + "/" + data.members() + " thành viên (" + percent((double) idle / data.members()) + ") chưa dùng AI lần nào.");
        return String.join(" ", parts);
    }

    private static void idle(Canvas page, UsageReportData data) throws IOException {
        section(page, "Thành viên chưa dùng AI", "Thành viên đang hoạt động không có lượt dùng nào trong kỳ.", 60);
        if (data.idleMembers().isEmpty()) {
            page.paragraph("Mọi thành viên đều đã dùng AI trong kỳ này.", page.regular, 11.5f, INK, 17);
            return;
        }
        var shown = data.idleMembers().subList(0, Math.min(UsageReportData.IDLE_SHOWN, data.idleMembers().size()));
        var rows = new ArrayList<String[]>();
        for (String name : shown) rows.add(new String[]{name});
        page.table(new String[]{"Thành viên"}, rows, new float[]{WIDTH}, new boolean[]{false});
        int remaining = data.idleMembers().size() - shown.size();
        if (remaining > 0) {
            page.gap(4);
            page.paragraph("Còn " + remaining + " thành viên chưa dùng nữa, xem đủ danh sách trong users.csv.", page.regular, 8.5f, BODY, 12);
        }
    }

    private static void section(Canvas page, String heading, String subheading, float keepWith) throws IOException {
        float lines = page.wrap(subheading, page.regular, 9.5f, WIDTH).size() * 13f;
        page.gap(22);
        page.ensure(18 + lines + 10 + keepWith);
        page.line(heading, page.bold, 14, INK, 18);
        page.gap(2);
        page.paragraph(subheading, page.regular, 9.5f, BODY, 13);
        page.gap(10);
    }

    private static void spendTable(Canvas page, String label, List<NamedSpend> entries, Function<String, String> name) throws IOException {
        var rows = new ArrayList<String[]>();
        for (var entry : entries)
            rows.add(new String[]{entry.isOther() ? "Khác (" + entry.folded() + ")" : name.apply(entry.name()), money(entry.cost()),
                    count(entry.totalTokens())});
        page.table(new String[]{label, "Chi phí (USD)", "Token"}, rows, new float[]{WIDTH * 0.5f, WIDTH * 0.25f, WIDTH * 0.25f},
                new boolean[]{false, true, true});
    }

    /** Onyx draws a bar chart above the model table; one table with a bar per row says the same in less space. */
    private static void barTable(Canvas page, String label, List<NamedSpend> entries, Function<String, String> name) throws IOException {
        double max = entries.stream().mapToDouble(entry -> entry.cost().doubleValue()).max().orElse(0);
        var rows = new ArrayList<String[]>();
        for (var entry : entries)
            rows.add(new String[]{entry.isOther() ? "Khác (" + entry.folded() + ")" : name.apply(entry.name()), "", money(entry.cost()),
                    count(entry.totalTokens())});
        float[] widths = {WIDTH * 0.34f, WIDTH * 0.26f, WIDTH * 0.2f, WIDTH * 0.2f};
        page.table(new String[]{label, "", "Chi phí (USD)", "Token"}, rows, widths, new boolean[]{false, false, true, true},
                (index, x, top, height) -> {
                    double value = entries.get(index).cost().doubleValue();
                    if (max <= 0 || value <= 0) return;
                    float length = Math.max(2f, (float) (value / max) * (widths[1] - 12));
                    page.fill(x, top - height / 2 - 4, length, 8, entries.get(index).isOther() ? HAIRLINE : ACCENT);
                });
    }

    private static void lineChart(Canvas page, List<DailySpend> days, ToDoubleFunction<DailySpend> value, Color color, boolean money)
            throws IOException {
        float height = 150;
        page.ensure(height);
        float left = MARGIN + 44, right = MARGIN + WIDTH, bottom = page.y - height + 26, top = page.y - 8;
        double max = days.stream().mapToDouble(value).max().orElse(0);
        // People come in whole numbers, so their axis never steps by a fraction.
        double step = money ? niceStep(max / 4) : Math.max(1, niceStep(max / 4));
        double ceiling = Math.max(step * 4, step * Math.ceil(max / step));
        int ticks = (int) Math.round(ceiling / step);
        for (int i = 0; i <= ticks; i++) {
            float y = (float) (bottom + (top - bottom) * i / ticks);
            page.stroke(left, y, right, y, HAIRLINE, 0.5f);
            String tick = money ? axisMoney(step * i) : count(Math.round(step * i));
            page.text(left - 6 - page.width(tick, page.regular, 7), y - 2.5f, page.regular, 7, BODY, tick);
        }
        int n = days.size();
        float span = right - left;
        Function<Integer, Float> xAt = i -> n == 1 ? left + span / 2 : left + span * i / (n - 1);
        int labelStep = Math.max(1, (n + MAX_AXIS_LABELS - 1) / MAX_AXIS_LABELS);
        for (int i = 0; i < n; i += labelStep) {
            String tick = SHORT_DAY.format(days.get(i).day());
            page.text(xAt.apply(i) - page.width(tick, page.regular, 7) / 2, bottom - 12, page.regular, 7, BODY, tick);
        }
        var cs = page.cs;
        cs.setStrokingColor(color);
        cs.setLineWidth(1.6f);
        for (int i = 0; i < n; i++) {
            float x = xAt.apply(i);
            float y = (float) (bottom + (top - bottom) * value.applyAsDouble(days.get(i)) / ceiling);
            if (i == 0) cs.moveTo(x, y); else cs.lineTo(x, y);
        }
        if (n > 1) cs.stroke();
        else {
            float y = (float) (bottom + (top - bottom) * value.applyAsDouble(days.getFirst()) / ceiling);
            page.fill(xAt.apply(0) - 3, y - 3, 6, 6, color);
        }
        page.y -= height;
    }

    /** 1, 2 or 5 times a power of ten, so the axis reads in round numbers. */
    static double niceStep(double raw) {
        if (raw <= 0) return 1;
        double power = Math.pow(10, Math.floor(Math.log10(raw)));
        double fraction = raw / power;
        return (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * power;
    }

    static String money(BigDecimal value) {
        int scale = value.signum() != 0 && value.abs().compareTo(BigDecimal.ONE) < 0 ? 4 : 2;
        var format = new DecimalFormat(scale == 4 ? "#,##0.00##" : "#,##0.00", VI);
        return format.format(value.setScale(scale, RoundingMode.HALF_UP)) + " US$";
    }

    private static String axisMoney(double value) {
        var format = new DecimalFormat(value >= 10 ? "#,##0" : "#,##0.##", VI);
        return format.format(value) + " $";
    }

    static String count(long value) {
        return new DecimalFormat("#,##0", VI).format(value);
    }

    private static String percent(double ratio) {
        return Math.round(ratio * 100) + "%";
    }

    /** Draws a row's extra mark, such as a bar, inside the column that has no text. */
    @FunctionalInterface
    interface CellMark {
        void draw(int row, float x, float top, float height) throws IOException;
    }

    /** A page cursor over PDFBox: text, rules, fills, tables that repeat their header, and page numbers. */
    static final class Canvas {
        final PDDocument doc;
        final PDType0Font regular;
        final PDType0Font bold;
        final List<PDPage> pages = new ArrayList<>();
        private final PdfText text;
        PDPageContentStream cs;
        float y;

        Canvas(PDDocument doc) throws IOException {
            this.doc = doc;
            this.text = new PdfText(doc);
            this.regular = text.font("HankenGrotesk-Regular.ttf");
            this.bold = text.font("HankenGrotesk-Bold.ttf");
            newPage();
        }

        void newPage() throws IOException {
            if (cs != null) cs.close();
            var page = new PDPage(PAGE);
            doc.addPage(page);
            pages.add(page);
            cs = new PDPageContentStream(doc, page);
            y = TOP;
        }

        void ensure(float height) throws IOException {
            if (y - height < BOTTOM && y < TOP) newPage();
        }

        void gap(float height) { y -= height; }

        /** Numbers every page after the cover ("Trang 2/5"), once the total is known. */
        void finish() throws IOException {
            cs.close();
            int total = pages.size();
            for (int i = 1; i < total; i++) {
                try (var stream = new PDPageContentStream(doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String folio = "Trang " + (i + 1) + "/" + total;
                    stream.beginText();
                    stream.setFont(regular, 8.5f);
                    stream.setNonStrokingColor(BODY);
                    stream.newLineAtOffset(MARGIN + WIDTH - width(folio, regular, 8.5f), MARGIN - 20);
                    stream.showText(safe(folio, regular));
                    stream.endText();
                }
            }
        }

        void text(float x, float baseline, PDType0Font font, float size, Color color, String value) throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.setNonStrokingColor(color);
            cs.newLineAtOffset(x, baseline);
            cs.showText(safe(value, font));
            cs.endText();
        }

        void line(String value, PDType0Font font, float size, Color color, float leading) throws IOException {
            ensure(leading);
            text(MARGIN, y - size, font, size, color, fit(value, font, size, WIDTH));
            y -= leading;
        }

        void label(String value) throws IOException {
            ensure(20);
            text(MARGIN, y - 9.5f, regular, 9.5f, BODY, value);
            y -= 20;
        }

        void paragraph(String value, PDType0Font font, float size, Color color, float leading) throws IOException {
            for (String line : wrap(value, font, size, WIDTH)) {
                ensure(leading);
                text(MARGIN, y - size, font, size, color, line);
                y -= leading;
            }
        }

        void rule(Color color) throws IOException {
            stroke(MARGIN, y, MARGIN + WIDTH, y, color, 1);
        }

        void stroke(float x1, float y1, float x2, float y2, Color color, float weight) throws IOException {
            cs.setStrokingColor(color);
            cs.setLineWidth(weight);
            cs.moveTo(x1, y1);
            cs.lineTo(x2, y2);
            cs.stroke();
        }

        void fill(float x, float bottom, float width, float height, Color color) throws IOException {
            cs.setNonStrokingColor(color);
            cs.addRect(x, bottom, width, height);
            cs.fill();
        }

        void table(String[] header, List<String[]> rows, float[] widths, boolean[] numeric) throws IOException {
            table(header, rows, widths, numeric, null);
        }

        /** Rows never split across pages; the header repeats at the top of each continued page. */
        void table(String[] header, List<String[]> rows, float[] widths, boolean[] numeric, CellMark mark) throws IOException {
            float rowHeight = 21;
            ensure(rowHeight * Math.min(2, rows.size() + 1));
            headerRow(header, widths, numeric, rowHeight);
            for (int r = 0; r < rows.size(); r++) {
                if (y - rowHeight < BOTTOM) {
                    newPage();
                    headerRow(header, widths, numeric, rowHeight);
                }
                float x = MARGIN;
                for (int c = 0; c < widths.length; c++) {
                    String cell = rows.get(r)[c];
                    if (cell.isEmpty() && mark != null) mark.draw(r, x, y, rowHeight);
                    else cell(x, widths[c], numeric[c], cell, c == 0 ? INK : BODY, regular);
                    x += widths[c];
                }
                y -= rowHeight;
                if (r < rows.size() - 1) stroke(MARGIN, y, MARGIN + WIDTH, y, HAIRLINE, 0.5f);
            }
        }

        private void headerRow(String[] header, float[] widths, boolean[] numeric, float rowHeight) throws IOException {
            float x = MARGIN;
            for (int c = 0; c < widths.length; c++) {
                cell(x, widths[c], numeric[c], header[c], INK, bold);
                x += widths[c];
            }
            y -= rowHeight;
            stroke(MARGIN, y, MARGIN + WIDTH, y, INK, 1);
        }

        private void cell(float x, float width, boolean numeric, String value, Color color, PDType0Font font) throws IOException {
            float size = 9;
            String shown = fit(value, font, size, width - 10);
            float left = numeric ? x + width - width(shown, font, size) : x;
            text(left, y - 14, font, size, color, shown);
        }

        float width(String value, PDType0Font font, float size) {
            return PdfText.width(safe(value, font), font, size);
        }

        /** Cuts a value that would overflow its column, with an ellipsis, rather than overprinting the next one. */
        String fit(String value, PDType0Font font, float size, float max) throws IOException {
            if (width(value, font, size) <= max) return value;
            String cut = value;
            while (!cut.isEmpty() && width(cut + "…", font, size) > max) cut = cut.substring(0, cut.length() - 1);
            return cut.stripTrailing() + "…";
        }

        /** Wrapped to the width; a word longer than a line, such as a long model id, is cut rather than overprinted. */
        List<String> wrap(String value, PDType0Font font, float size, float max) {
            if (value.isBlank()) return List.of();
            return PdfText.lines(safe(value, font), font, size, max, max);
        }

        /** Names and models are data; see {@link PdfText#safe}. */
        String safe(String value, PDType0Font font) {
            return text.safe(value, font);
        }
    }
}
