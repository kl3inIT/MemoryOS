package io.memoryos.ingestion.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.Table;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * MEM-192. Column headers of PaddleOCR-VL tables, on the leading rows of real tables from the public
 * HUT (Tasco) reports, OCR slips included. A wrong header labels every value of its column, so the
 * rule marks nothing when a table does not fit it.
 */
class PaddleOcrVlTableHeaderTest {

    @Test
    void theBalanceSheetHeaderIsTheRowAboveTheFirstAmount() throws Exception {
        var table = table("balance-sheet");

        assertEquals(List.of("0,0 TÀI SẢN", "0,1 Mã\\nSố", "0,2 Thuyết\\nmlnh", "0,3 Số cuối kỳ", "0,4 Số đầu kỳ"), headers(table));
        assertTrue(table.cells().stream().noneMatch(Cell::rowHeader), "the item column is a column like any other");
    }

    @Test
    void aQuarterOverItsYearsIsATwoRowHeaderWithSpans() throws Exception {
        var table = table("income-statement-quarter");

        assertEquals(List.of("0,0 CHÍ TIÊU", "0,1 Mã số", "0,2 Thuyết minh", "0,3 QUỀI", "0,5 LỤY KỆ TỪ ĐAU NĂM",
                "1,3 Năm nay", "1,4 Năm trước", "1,5 Năm nay", "1,6 Năm trước"), headers(table));
    }

    @Test
    void aBlankCornerIsNoHeaderAndSpansStackAboveTheirColumns() throws Exception {
        assertEquals(List.of("0,1 Số cuối kỹ", "0,2 Số đầu kỹ"), headers(table("blank-item-header")));
        assertEquals(List.of("0,1 Số cuối kỹ", "0,3 Số đầu kỹ", "1,1 Giá trị", "1,2 Dự phòng", "1,3 Giá trị", "1,4 Dự phòng"),
                headers(table("value-and-provision")));
    }

    @Test
    void aSectionLabelUnderTheHeaderEndsIt() throws Exception {
        // The body has section labels, so "CHỈ TIẾU" over the item column could be a group name and is left.
        assertEquals(List.of("0,1 Mã số", "0,2 Thuyết minh", "0,3 Quý I năm 2026", "0,4 Quý I năm 2025"),
                headers(table("cash-flow-section-label")));
        // Paddle put "Kỳ trước" into the section row; that row stays data rather than make the section a header.
        var misplaced = table("misplaced-period-in-section-row");
        assertEquals(List.of(0), misplaced.cells().stream().filter(Cell::columnHeader).map(Cell::row).distinct().toList());
    }

    @Test
    void aCaptionOverTheWholeTableIsNotAHeader() throws Exception {
        assertEquals(List.of("1,1 Quý 1 năm 2026", "1,2 Quý 1 năm 2025"), headers(table("caption-then-header")));
    }

    @Test
    void aGroupNameInTheItemColumnOfTheHeaderIsLeftUnmarked() throws Exception {
        // "Nguyên giá" heads the first group of rows, not the column: "Giá trị hao mòn lũy kế" follows.
        var headers = headers(table("group-label-in-header"));

        assertEquals(5, headers.size());
        assertTrue(headers.stream().allMatch(header -> header.startsWith("0,")) && !headers.contains("0,0 Nguyên giá"), headers::toString);
    }

    @ParameterizedTest
    @ValueSource(strings = {"caption-then-mixed-row", "header-cell-into-data", "continuation-without-header"})
    void aTableTheRuleIsUnsureOfKeepsNoHeader(String name) throws Exception {
        assertFalse(table(name).hasColumnHeaders(), name);
    }

    @Test
    void headerTextWithDigitsIsStillAHeader() throws Exception {
        var table = PaddleOcrVlDocument.table("<table><tr><td></td><td>31/03/2026</td><td>01/01/2026</td></tr>"
                + "<tr><td>Tiền mặt</td><td>(1.234.567)</td><td>802.373,22</td></tr></table>", new int[1]);

        assertEquals(List.of("0,1 31/03/2026", "0,2 01/01/2026"), headers(table));
    }

    private static Table table(String name) throws Exception {
        return PaddleOcrVlDocument.table(PaddleOcrVlDocumentTest.hutTable(name), new int[1]);
    }

    private static List<String> headers(Table table) {
        return table.cells().stream().filter(Cell::columnHeader)
                .map(cell -> cell.row() + "," + cell.column() + " " + cell.text()).toList();
    }
}
