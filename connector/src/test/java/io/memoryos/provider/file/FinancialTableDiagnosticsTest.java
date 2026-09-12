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

    @Test
    void recognizesBeginningOfYearAbbreviationWithoutInventingMissingExchangeRow() {
        var blocks = validTable();
        cellAt(blocks, 2, 1).put("text", "Cash and cash equivalents at BOY");
        cellAt(blocks, 0, 5).put("text", "Currency: VND\nPrevious year");
        removeCell(blocks, 3, 1);
        removeCell(blocks, 3, 2);

        var checks = FinancialTableDiagnostics.assess(blocks, mapper);

        assertEquals(1, checks.size());
        assertEquals("INCOMPLETE", checks.get(0).path("status").asString());
        assertEquals(mapper.createObjectNode().put("reason", "MISSING_ROW_IDENTITY")
                .put("row_code", 61), checks.get(0).path("structure_issues").get(0));
        assertEquals(1, checks.get(0).path("structure_issues").size());
    }

    @Test
    void recognizesHeaderEdgeRulesWithoutCleaningNumericCells() {
        var blocks = table(ENGLISH, "—== Current year", "Previous year|",
                new String[]{"1", "2", "3", "6"}, new String[]{"1", "2", "3", "— 6"});
        var original = blocks.deepCopy();

        var checks = FinancialTableDiagnostics.assess(blocks, mapper);

        assertEquals("CONSISTENT", checks.get(0).path("status").asString());
        assertEquals("CURRENT", checks.get(0).path("period_identity").asString());
        assertEquals("INVALID_NUMERIC", checks.get(1).path("status").asString());
        assertEquals("PRIOR", checks.get(1).path("period_identity").asString());
        assertEquals(mapper.createArrayNode().add(70), checks.get(1).path("invalid_row_codes"));
        assertEquals(original, blocks);
    }

    @Test
    void reportsMissingCodeAndDamagedPeriodTogetherWithoutGuessingEither() {
        var blocks = table(VIETNAMESE, "Nama", "Năm trước",
                new String[]{"1", "2", "3", "6"}, new String[]{"1", "2", "3", "6"});
        cellAt(blocks, 4, 2).put("text", "7O");

        var checks = FinancialTableDiagnostics.assess(blocks, mapper);
        var issues = checks.get(0).path("structure_issues");

        assertEquals(1, checks.size());
        assertEquals("INCOMPLETE", checks.get(0).path("status").asString());
        assertEquals(2, issues.size());
        assertEquals(70, issues.get(0).path("row_code").asInt());
        assertEquals("MISSING_ROW_CODE", issues.get(0).path("reason").asString());
        assertEquals(4, issues.get(1).path("column_index").asInt());
        assertEquals("MISSING_PERIOD_HEADERS", issues.get(1).path("reason").asString());
    }

    @Test
    void recognizesExplicitVietnameseQuarterButDoesNotRepairMissingQuarterDigits() {
        var blocks = table(VIETNAMESE, "Quý 1 năm 2026", "Đơn vị tính: VND\nQuy 1 nam 2025",
                new String[]{"1", "2", "3", "6"}, new String[]{"1", "2", "3", "6"});
        var checks = FinancialTableDiagnostics.assess(blocks, mapper);
        assertEquals("CONSISTENT", checks.get(0).path("status").asString());
        assertEquals("2026-Q1", checks.get(0).path("period_identity").asString());
        assertEquals("2025-Q1", checks.get(1).path("period_identity").asString());

        cellAt(blocks, 0, 4).put("text", "Quý | năm 2026");
        cellAt(blocks, 0, 5).put("text", "Quý năm 2025");
        var damaged = FinancialTableDiagnostics.assess(blocks, mapper);
        assertEquals(1, damaged.size());
        assertEquals("INCOMPLETE", damaged.get(0).path("status").asString());
        assertEquals(2, damaged.get(0).path("structure_issues").size());
        assertEquals(4, damaged.get(0).path("structure_issues").get(0).path("column_index").asInt());
        assertEquals(5, damaged.get(0).path("structure_issues").get(1).path("column_index").asInt());
    }

    @Test
    void followsOnlyContiguousExplicitHeaderAncestryWithinOneColumn() {
        var blocks = validTable();
        shiftRows(blocks, 2);
        cells(blocks).add(cell(0, 4, "Currency: VND", true).put("end_col_offset_idx", 6));
        cells(blocks).add(cell(1, 4, "Current", true));
        cellAt(blocks, 2, 4).put("text", "year");
        cellAt(blocks, 2, 5).put("start_row_offset_idx", 1);
        var original = blocks.deepCopy();

        var checks = FinancialTableDiagnostics.assess(blocks, mapper);

        assertEquals(2, checks.size());
        assertEquals("CONSISTENT", checks.get(0).path("status").asString());
        assertEquals("CURRENT", checks.get(0).path("period_identity").asString());
        assertEquals("CONSISTENT", checks.get(1).path("status").asString());
        assertEquals("PRIOR", checks.get(1).path("period_identity").asString());
        assertEquals(original, blocks);
    }

    @Test
    void refusesMergedPeriodAncestorsAndConflictingSingleColumnAncestors() {
        var merged = validTable();
        shiftRows(merged, 1);
        cells(merged).add(cell(0, 4, "Current year", true).put("end_col_offset_idx", 6));
        var conflicting = validTable();
        shiftRows(conflicting, 1);
        cells(conflicting).add(cell(0, 4, "Previous year", true));

        for (var blocks : new ArrayNode[]{merged, conflicting}) {
            var checks = FinancialTableDiagnostics.assess(blocks, mapper);
            assertEquals(1, checks.size());
            assertEquals("AMBIGUOUS", checks.get(0).path("status").asString());
            assertEquals("AMBIGUOUS_PERIOD_HEADERS",
                    checks.get(0).path("structure_issues").get(0).path("reason").asString());
            assertFalse(checks.get(0).has("column_index"));
        }
    }

    @Test
    void refusesHeaderGapsAndUnrecognizedQualifiersRatherThanDroppingThem() {
        var gap = validTable();
        shiftRows(gap, 2);
        cells(gap).add(cell(0, 4, "Currency: VND", true));
        var qualified = validTable();
        shiftRows(qualified, 1);
        cells(qualified).add(cell(0, 4, "Unaudited forecast", true));

        assertEquals("AMBIGUOUS", FinancialTableDiagnostics.assess(gap, mapper).get(0).path("status").asString());
        var result = FinancialTableDiagnostics.assess(qualified, mapper);
        assertEquals(1, result.size());
        assertEquals("INCOMPLETE", result.get(0).path("status").asString());
        assertEquals(4, result.get(0).path("structure_issues").get(0).path("column_index").asInt());
    }

    @Test
    void reportsDuplicateIdentityAndAmbiguousCodeEvenWhenAnotherIdentityIsAbsent() {
        var blocks = validTable();
        cells(blocks).add(cellAt(blocks, 1, 1).deepCopy());
        removeCell(blocks, 3, 1);
        cellAt(blocks, 4, 2).put("column_header", true);

        var result = FinancialTableDiagnostics.assess(blocks, mapper).get(0);
        var issues = result.path("structure_issues");

        assertEquals("AMBIGUOUS", result.path("status").asString());
        assertEquals(3, issues.size());
        assertEquals("AMBIGUOUS_ROW_IDENTITY", issues.get(0).path("reason").asString());
        assertEquals(50, issues.get(0).path("row_code").asInt());
        assertEquals("MISSING_ROW_IDENTITY", issues.get(1).path("reason").asString());
        assertEquals(61, issues.get(1).path("row_code").asInt());
        assertEquals("AMBIGUOUS_ROW_CODE", issues.get(2).path("reason").asString());
        assertEquals(70, issues.get(2).path("row_code").asInt());
    }

    @Test
    void refusesBeginningAndEndingInOneLabelRatherThanChoosingFirstMatch() {
        var blocks = validTable();
        cellAt(blocks, 2, 1).put("text", "Cash and cash equivalents at the beginning and at the end of the year");

        var result = FinancialTableDiagnostics.assess(blocks, mapper).get(0);

        assertEquals("AMBIGUOUS", result.path("status").asString());
        assertEquals(60, result.path("structure_issues").get(0).path("row_code").asInt());
        assertEquals("AMBIGUOUS_ROW_IDENTITY", result.path("structure_issues").get(0).path("reason").asString());
        assertEquals(70, result.path("structure_issues").get(1).path("row_code").asInt());
    }

    @Test
    void boundsHeaderAncestryAndTableDimensionsBeforeAssessment() {
        var deep = validTable();
        shiftRows(deep, 4);
        for (int row = 0; row < 4; row++) cells(deep).add(cell(row, 4, "Currency: VND", true));
        var wide = validTable();
        ((ObjectNode) wide.get(0).path("table")).put("num_cols", Integer.MAX_VALUE);

        for (var blocks : new ArrayNode[]{deep, wide}) {
            var result = FinancialTableDiagnostics.assess(blocks, mapper);
            assertEquals(1, result.size());
            assertEquals("INCOMPLETE", result.get(0).path("status").asString());
            assertEquals("ASSESSMENT_LIMIT", result.get(0).path("reason").asString());
            assertFalse(result.get(0).has("column_index"));
        }
    }

    private void shiftRows(ArrayNode blocks, int offset) {
        var table = (ObjectNode) blocks.get(0).path("table");
        table.put("num_rows", table.path("num_rows").asInt() + offset);
        for (var raw : cells(blocks)) {
            var cell = (ObjectNode) raw;
            cell.put("start_row_offset_idx", cell.path("start_row_offset_idx").asInt() + offset);
            cell.put("end_row_offset_idx", cell.path("end_row_offset_idx").asInt() + offset);
        }
    }

    private void removeCell(ArrayNode blocks, int row, int column) {
        var target = cellAt(blocks, row, column);
        var cells = cells(blocks);
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i) == target) {
                cells.remove(i);
                return;
            }
        }
        throw new AssertionError("Missing fixture cell");
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
