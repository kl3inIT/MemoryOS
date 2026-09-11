package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class FinancialTableDiagnosticsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String[] ENGLISH = {
            "Net cash flows during the year",
            "Cash and cash equivalents at the beginning of the year",
            "Impacts of foreign exchange differences",
            "Cash and cash equivalents at the end of the year"
    };
    private static final String[] VIETNAMESE = {
            "Lưu chuyển tiền thuần trong năm",
            "Tiền và tương đương tiền đầu năm",
            "Ảnh hưởng của thay đổi tỷ giá hối đoái quy đổi ngoại tệ",
            "Tiền và tương đương tiền cuối năm"
    };

    @Test
    void distinguishesObservedMismatchShapeFromRuleContaminationWithoutChangingInput() {
        // Synthetic amounts reproduce the observed -600 discrepancy and em-dash contamination.
        var blocks = table(VIETNAMESE, "Năm nay", "Năm trước",
                new String[]{"1.000.000", "2.000.000", "1.000", "3000400"},
                new String[]{"1.000", "2.000", "(100)", "— 2900"});
        var original = blocks.deepCopy();

        var checks = FinancialTableDiagnostics.assess(blocks, mapper);

        assertEquals("INCONSISTENT", checks.get(0).path("status").asString());
        assertEquals(4, checks.get(0).path("column_index").asInt());
        assertEquals("INVALID_NUMERIC", checks.get(1).path("status").asString());
        assertEquals(mapper.createArrayNode().add(70), checks.get(1).path("invalid_row_codes"));
        assertEquals(original, blocks);
    }

    @Test
    void handlesNegativeGroupedIntegersBeyondLongRangeExactly() {
        var blocks = table(ENGLISH, "Current year", "Previous year",
                new String[]{"(1,000)", "100,000,000,000,000,000,000", "-100", "99,999,999,999,999,998,900"},
                new String[]{"-1.000", "2.000", "(100)", "900"});

        var checks = FinancialTableDiagnostics.assess(blocks, mapper);

        assertEquals("CONSISTENT", checks.get(0).path("status").asString());
        assertEquals("CONSISTENT", checks.get(1).path("status").asString());
    }

    @Test
    void neverTreatsBlankDashOrAbsentAmountAsZero() {
        var blocks = table(ENGLISH, "Current year", "Previous year",
                new String[]{"1,000", "2,000", "-", "3,000"},
                new String[]{"1,000", "2,000", " ", null});

        var checks = FinancialTableDiagnostics.assess(blocks, mapper);

        assertEquals("INCOMPLETE", checks.get(0).path("status").asString());
        assertEquals(mapper.createArrayNode().add(61), checks.get(0).path("missing_row_codes"));
        assertEquals("INCOMPLETE", checks.get(1).path("status").asString());
        assertEquals(mapper.createArrayNode().add(61).add(70), checks.get(1).path("missing_row_codes"));
    }

    @Test
    void rejectsMalformedGroupingAndMixedSignsInsteadOfRepairingOcr() {
        var blocks = table(ENGLISH, "Current year", "Previous year",
                new String[]{"1.00.000", "2.000", "1", "102001"},
                new String[]{"-(100)", "2,000", "1", "1,901"});

        var checks = FinancialTableDiagnostics.assess(blocks, mapper);

        for (var check : checks) {
            assertEquals("INVALID_NUMERIC", check.path("status").asString());
            assertEquals(mapper.createArrayNode().add(50), check.path("invalid_row_codes"));
        }
    }

    @Test
    void refusesMergedOrDuplicatePeriodHeaders() {
        var merged = validTable();
        cellAt(merged, 0, 4).put("end_col_offset_idx", 6);
        var duplicate = table(ENGLISH, "Current year", "Current year",
                new String[]{"1", "2", "3", "6"}, new String[]{"1", "2", "3", "6"});

        for (var blocks : new ArrayNode[]{merged, duplicate}) {
            var checks = FinancialTableDiagnostics.assess(blocks, mapper);
            assertEquals(1, checks.size());
            assertEquals("AMBIGUOUS", checks.get(0).path("status").asString());
            assertEquals("AMBIGUOUS_PERIOD_HEADERS", checks.get(0).path("reason").asString());
            assertFalse(checks.get(0).has("column_index"));
        }
    }

    @Test
    void refusesMergedAndOverlappingAmountsButStillAssessesUnaffectedPeriod() {
        var merged = validTable();
        cellAt(merged, 3, 4).put("end_col_offset_idx", 6);
        var overlapping = validTable();
        cells(overlapping).add(cellAt(overlapping, 4, 4).deepCopy());

        var mergedChecks = FinancialTableDiagnostics.assess(merged, mapper);
        assertEquals("AMBIGUOUS", mergedChecks.get(0).path("status").asString());
        assertEquals("AMBIGUOUS", mergedChecks.get(1).path("status").asString());
        assertEquals(mapper.createArrayNode().add(61), mergedChecks.get(1).path("ambiguous_row_codes"));
        var overlapChecks = FinancialTableDiagnostics.assess(overlapping, mapper);
        assertEquals("AMBIGUOUS", overlapChecks.get(0).path("status").asString());
        assertEquals("CONSISTENT", overlapChecks.get(1).path("status").asString());
    }

    @Test
    void doesNotBorrowPeriodHeadersOrApplyCashFlowIdentityToOtherStatements() {
        var noHeaders = table(ENGLISH, "Amount", "Amount",
                new String[]{"1", "2", "3", "6"}, new String[]{"1", "2", "3", "6"});
        var unrelated = table(new String[]{"Profit before tax", "Profit after tax", "Parent profit", "Earnings per share"},
                "Current year", "Previous year", new String[]{"1", "2", "3", "6"}, new String[]{"1", "2", "3", "6"});
        var blocks = validTable();
        blocks.add(noHeaders.get(0));
        blocks.add(unrelated.get(0));

        var checks = FinancialTableDiagnostics.assess(blocks, mapper);

        assertEquals(3, checks.size());
        assertEquals("INCOMPLETE", checks.get(2).path("status").asString());
        assertEquals("MISSING_PERIOD_HEADERS", checks.get(2).path("reason").asString());
    }

    @Test
    void reportsAssessmentTruncationWithinOutputBound() {
        var blocks = mapper.createArrayNode();
        var table = validTable().get(0);
        for (int i = 0; i < 65; i++) blocks.add(table);

        var checks = FinancialTableDiagnostics.assess(blocks, mapper);

        assertEquals(128, checks.size());
        assertEquals("INCOMPLETE", checks.get(127).path("status").asString());
        assertEquals("ASSESSMENT_LIMIT", checks.get(127).path("reason").asString());
    }

    private ArrayNode validTable() {
        return table(ENGLISH, "Current year", "Previous year",
                new String[]{"1", "2", "3", "6"}, new String[]{"1", "2", "3", "6"});
    }

    private ArrayNode table(String[] labels, String currentHeader, String priorHeader,
            String[] current, String[] prior) {
        var blocks = mapper.createArrayNode();
        var block = blocks.addObject().put("index", 17).put("kind", "TABLE");
        var table = block.putObject("table").put("num_rows", 5).put("num_cols", 6);
        var cells = table.putArray("table_cells");
        cells.add(cell(0, 1, "Items", true));
        cells.add(cell(0, 2, "Code", true));
        cells.add(cell(0, 3, "Notes", true));
        cells.add(cell(0, 4, currentHeader, true));
        cells.add(cell(0, 5, priorHeader, true));
        int[] codes = {50, 60, 61, 70};
        for (int i = 0; i < codes.length; i++) {
            cells.add(cell(i + 1, 1, labels[i], false));
            cells.add(cell(i + 1, 2, Integer.toString(codes[i]), false));
            if (current[i] != null) cells.add(cell(i + 1, 4, current[i], false));
            if (prior[i] != null) cells.add(cell(i + 1, 5, prior[i], false));
        }
        return blocks;
    }

    private ObjectNode cell(int row, int column, String text, boolean header) {
        return mapper.createObjectNode().put("text", text).put("start_row_offset_idx", row)
                .put("end_row_offset_idx", row + 1).put("start_col_offset_idx", column)
                .put("end_col_offset_idx", column + 1).put("column_header", header)
                .put("row_header", false).put("row_section", false);
    }

    private ArrayNode cells(ArrayNode blocks) {
        return (ArrayNode) blocks.get(0).path("table").path("table_cells");
    }

    private ObjectNode cellAt(ArrayNode blocks, int row, int column) {
        for (var cell : cells(blocks)) {
            if (cell.path("start_row_offset_idx").asInt() == row
                    && cell.path("start_col_offset_idx").asInt() == column) return (ObjectNode) cell;
        }
        throw new AssertionError("Missing fixture cell");
    }
}
