package io.memoryos.chat.interpreter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SpreadsheetPreviewTest {
    @Test void eachSheetBecomesQuotedCsvWithCachedFormulaValuesInWorkbookOrder() throws IOException {
        byte[] xlsx;
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sales = workbook.createSheet("Doanh thu");
            var header = sales.createRow(0);
            header.createCell(0).setCellValue("Tên");
            header.createCell(1).setCellValue("Ghi chú");
            var row = sales.createRow(1);
            row.createCell(0).setCellValue("Hà Nội, VN");
            row.createCell(2).setCellValue("nói \"xin chào\"\nlần 2");
            var total = sales.createRow(3);
            total.createCell(1).setCellFormula("1+2");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.createSheet("Trống");
            workbook.write(out);
            xlsx = out.toByteArray();
        }

        var sheets = SpreadsheetPreview.parse(new ByteArrayInputStream(xlsx));

        assertEquals(List.of(
                new SpreadsheetPreview.Sheet("Doanh thu",
                        "Tên,Ghi chú\n\"Hà Nội, VN\",,\"nói \"\"xin chào\"\"\nlần 2\"\n,3\n", false),
                new SpreadsheetPreview.Sheet("Trống", "", false)), sheets);
    }

    @Test void aSheetPastTheBudgetIsCutAtARowBoundaryLikeOnyx() throws IOException {
        byte[] xlsx;
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Data");
            for (int i = 0; i < 10; i++) sheet.createRow(i).createCell(0).setCellValue("row" + i);
            workbook.write(out);
            xlsx = out.toByteArray();
        }

        var cut = SpreadsheetPreview.parse(new ByteArrayInputStream(xlsx), 13).getFirst();
        assertEquals(new SpreadsheetPreview.Sheet("Data", "row0\nrow1\n", true), cut);
        // No complete row fits: an empty preview rather than a malformed row.
        assertEquals("", SpreadsheetPreview.parse(new ByteArrayInputStream(xlsx), 3).getFirst().csv());
    }

    @Test void anythingButAWorkbookIsRejected() {
        assertThrows(IOException.class, () -> SpreadsheetPreview.parse(new ByteArrayInputStream("a,b\n".getBytes())));
    }
}
