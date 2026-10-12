package io.memoryos.usage.report;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.usage.persistence.UsageReportRepository.ExportRow;
import io.memoryos.usage.persistence.UsageReportRepository.Member;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class UsageReportPdfTest {
    private static final LocalDate FROM = LocalDate.parse("2026-09-01");
    private static final LocalDate TO = LocalDate.parse("2026-09-30");

    @Test void drawsVietnameseAcrossTheCoverAndTheSectionPages() throws Exception {
        var data = populated();
        byte[] pdf = UsageReportPdf.render(data);
        writeWhenAsked(pdf, "usage-report.pdf");
        try (var doc = Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() >= 2, "cover and sections");
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Báo cáo sử dụng AI"), text);
            assertTrue(text.contains("Công ty Cổ phần Tasco"));
            assertTrue(text.contains("Trần Thu Hà"));
            assertTrue(text.contains("Nguyễn Đức Quân"), "idle member listed");
            assertTrue(text.contains("Chi phí theo Group"));
            assertTrue(text.contains("Pháp chế"));
            assertTrue(text.contains("Nội bộ"));
            assertTrue(text.contains("Tác vụ hệ thống"));
            assertTrue(text.contains("Trang 2/"));
            // A character the typeface cannot draw does not fail the report.
            assertTrue(text.contains("Lê ?"), text);
        }
    }

    @Test void anEmptyPeriodIsOnePageThatSaysSo() throws Exception {
        var data = UsageReportData.builder("Tasco", FROM, TO).build(List.of());
        try (var doc = Loader.loadPDF(UsageReportPdf.render(data))) {
            assertEquals(1, doc.getNumberOfPages());
            assertTrue(new PDFTextStripper().getText(doc).contains("Không có usage nào được ghi nhận trong kỳ này."));
        }
    }

    @Test void foldsTheTailIntoOtherButNeverASingleRow() {
        var builder = UsageReportData.builder("Tasco", FROM, TO);
        for (int i = 0; i < 9; i++) builder.add(row(FROM, UUID.randomUUID(), "Người " + i, List.of(), "model-" + i, "EXTERNAL", "0.0" + (i + 1)));
        var nine = builder.build(List.of());
        assertEquals(9, nine.byModel().size(), "one leftover keeps its name");
        builder.add(row(FROM, UUID.randomUUID(), "Người 9", List.of(), "model-9", "EXTERNAL", "0.001"));
        var ten = builder.build(List.of());
        assertEquals(9, ten.byModel().size());
        assertEquals(2, ten.byModel().getLast().folded());
    }

    @Test void axisStepsAreRound() {
        assertEquals(0.5, UsageReportPdf.niceStep(0.3), 1e-9);
        assertEquals(2, UsageReportPdf.niceStep(1.7), 1e-9);
        assertEquals(50, UsageReportPdf.niceStep(31), 1e-9);
        assertEquals("0,0313 US$", UsageReportPdf.money(new BigDecimal("0.03125")));
        assertEquals("1.234,50 US$", UsageReportPdf.money(new BigDecimal("1234.5")));
    }

    static UsageReportData populated() {
        UUID ha = UUID.randomUUID(), minh = UUID.randomUUID(), le = UUID.randomUUID(), quan = UUID.randomUUID();
        var builder = UsageReportData.builder("Công ty Cổ phần Tasco", FROM, TO);
        var rows = new ArrayList<ExportRow>();
        for (int day = 0; day < 30; day++) {
            var date = FROM.plusDays(day);
            rows.add(row(date, ha, "Trần Thu Hà", List.of("Pháp chế"), "gpt-5.1", "EXTERNAL", String.valueOf(0.4 + (day % 7) * 0.11)));
            if (day % 2 == 0) rows.add(row(date, minh, "Phạm Văn Minh", List.of("Kế toán", "Pháp chế"), "qwen3-8b", "INTERNAL", "0"));
            if (day % 3 == 0) rows.add(row(date, le, "Lê 李", List.of("Kế toán"), "claude-sonnet-5", "EXTERNAL", "1.25"));
            rows.add(new ExportRow(date, null, null, null, List.of(), "EMBEDDING_INDEXING", "api.openai.com", "text-embedding-3-large",
                    null, 40, 0, 900_000, 0, 0, 0, BigDecimal.ZERO, new BigDecimal("0.117")));
        }
        rows.forEach(builder::add);
        return builder.build(List.of(
                new Member(ha, "ha@tasco.vn", "Trần Thu Hà", "ACTIVE", List.of("Pháp chế")),
                new Member(minh, "minh@tasco.vn", "Phạm Văn Minh", "ACTIVE", List.of("Kế toán", "Pháp chế")),
                new Member(quan, "quan@tasco.vn", "Nguyễn Đức Quân", "ACTIVE", List.of()),
                new Member(UUID.randomUUID(), "cu@tasco.vn", "Người đã nghỉ", "INACTIVE", List.of())));
    }

    static ExportRow row(LocalDate day, UUID actor, String name, List<String> groups, String model, String boundary, String cost) {
        return new ExportRow(day, actor, name.toLowerCase().replace(' ', '.') + "@tasco.vn", name, groups, "CHAT", "OpenAI", model,
                boundary, 12, 1, 24_000, 3_500, 1_200, 0, BigDecimal.ZERO, new BigDecimal(cost));
    }

    /** {@code MEMORYOS_REPORT_OUT=<dir>} keeps the rendered file for a visual review. */
    static void writeWhenAsked(byte[] bytes, String name) throws Exception {
        String dir = System.getenv("MEMORYOS_REPORT_OUT");
        if (dir != null) Files.write(Path.of(dir, name), bytes);
    }
}
