package io.memoryos.provider.file;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class FinancialTableDiagnostics {
    private static final int MAX_CHECKS = 128;
    private static final int MAX_BLOCKS = 20_000;
    private static final int MAX_TOTAL_CELLS = 100_000;
    private static final int MAX_TABLE_CELLS = 4_096;
    private static final int MAX_COLUMNS = 64;
    private static final int[] ROW_CODES = {50, 60, 61, 70};
    private static final String[] OFFSETS = {
            "start_row_offset_idx", "end_row_offset_idx", "start_col_offset_idx", "end_col_offset_idx"
    };
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern INTEGER = Pattern.compile(
            "(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,3}(?: [0-9]{3})+)");
    private static final Pattern YEAR = Pattern.compile("(?:19|20)[0-9]{2}");

    private FinancialTableDiagnostics() {}

    static ArrayNode assess(ArrayNode blocks, ObjectMapper mapper) {
        var checks = mapper.createArrayNode();
        int totalCells = 0;
        for (int position = 0; position < blocks.size(); position++) {
            var block = blocks.get(position);
            int index = block.path("index").asInt(position);
            if (position >= MAX_BLOCKS) {
                add(checks, diagnostic(mapper, index, "INCOMPLETE", "ASSESSMENT_LIMIT"));
                break;
            }
            var table = block.path("table");
            var rawCells = table.path("table_cells");
            if (!rawCells.isArray()) continue;
            totalCells += rawCells.size();
            if (totalCells > MAX_TOTAL_CELLS) {
                add(checks, diagnostic(mapper, index, "INCOMPLETE", "ASSESSMENT_LIMIT"));
                break;
            }
            if (rawCells.size() > MAX_TABLE_CELLS) {
                if (!add(checks, diagnostic(mapper, index, "INCOMPLETE", "ASSESSMENT_LIMIT"))) break;
                continue;
            }
            if (!assessTable(table, rawCells, index, checks, mapper)) break;
        }
        return checks;
    }

    private static boolean assessTable(JsonNode table, JsonNode rawCells, int index,
            ArrayNode checks, ObjectMapper mapper) {
        boolean cashBalanceLabel = false;
        for (var raw : rawCells) {
            int code = rowCode(normalize(raw.path("text").asString("")));
            if (code == 60 || code == 70) {
                cashBalanceLabel = true;
                break;
            }
        }
        if (!cashBalanceLabel) return true;
        int rows = table.path("num_rows").asInt(-1);
        int columns = table.path("num_cols").asInt(-1);
        if (rows > MAX_TABLE_CELLS || columns > MAX_COLUMNS) {
            return add(checks, diagnostic(mapper, index, "INCOMPLETE", "ASSESSMENT_LIMIT"));
        }
        var cells = new ArrayList<Cell>(rawCells.size());
        for (var raw : rawCells) {
            var cell = cell(raw);
            if (cell == null || cell.row < 0 || cell.column < 0 || cell.endRow <= cell.row
                    || cell.endColumn <= cell.column || cell.endRow > rows || cell.endColumn > columns) {
                return add(checks, diagnostic(mapper, index, "AMBIGUOUS", "INVALID_CELL_GEOMETRY"));
            }
            cells.add(cell);
        }
        var labels = new Cell[ROW_CODES.length];
        for (var cell : cells) {
            int code = rowCode(normalize(cell.text()));
            for (int i = 0; i < ROW_CODES.length; i++) {
                if (code != ROW_CODES[i]) continue;
                if (labels[i] != null || !isolated(cell, cells)) {
                    return add(checks, diagnostic(mapper, index, "AMBIGUOUS", "AMBIGUOUS_ROW_IDENTITY"));
                }
                labels[i] = cell;
            }
        }
        int codeColumn = -1;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) {
                return add(checks, diagnostic(mapper, index, "INCOMPLETE", "MISSING_ROW_IDENTITY"));
            }
            var label = labels[i];
            if (i > 0 && (label.row <= labels[i - 1].row || label.column != labels[0].column)) {
                return add(checks, diagnostic(mapper, index, "AMBIGUOUS", "AMBIGUOUS_ROW_IDENTITY"));
            }
            Cell codeCell = null;
            for (var cell : cells) {
                if (cell.row != label.row || !cell.text().strip().equals(Integer.toString(ROW_CODES[i]))) continue;
                if (codeCell != null || !isolated(cell, cells) || cell.column <= label.column) {
                    return add(checks, diagnostic(mapper, index, "AMBIGUOUS", "AMBIGUOUS_ROW_CODE"));
                }
                codeCell = cell;
            }
            if (codeCell == null) {
                return add(checks, diagnostic(mapper, index, "INCOMPLETE", "MISSING_ROW_CODE"));
            }
            if (codeColumn >= 0 && codeColumn != codeCell.column) {
                return add(checks, diagnostic(mapper, index, "AMBIGUOUS", "AMBIGUOUS_ROW_CODE"));
            }
            codeColumn = codeCell.column;
        }
        var periods = new ArrayList<Cell>();
        var identities = new HashSet<String>();
        var periodColumns = new HashSet<Integer>();
        for (var cell : cells) {
            if (cell.row >= labels[0].row || cell.endColumn <= codeColumn + 1) continue;
            String identity = periodIdentity(normalize(cell.text()));
            if (identity.isEmpty()) continue;
            if (!cell.raw.path("column_header").asBoolean(false) || !isolated(cell, cells)
                    || cell.endRow > labels[0].row || cell.column <= codeColumn
                    || !identities.add(identity) || !periodColumns.add(cell.column)) {
                return add(checks, diagnostic(mapper, index, "AMBIGUOUS", "AMBIGUOUS_PERIOD_HEADERS"));
            }
            periods.add(cell);
        }
        if (periods.isEmpty()) {
            return add(checks, diagnostic(mapper, index, "INCOMPLETE", "MISSING_PERIOD_HEADERS"));
        }
        // A period must be explicitly named; never borrow a header from a previous table/page.
        for (var period : periods) {
            if (!add(checks, assessPeriod(cells, labels, period.column, index, mapper))) return false;
        }
        return true;
    }

    private static ObjectNode assessPeriod(List<Cell> cells, Cell[] labels, int column,
            int index, ObjectMapper mapper) {
        var result = diagnostic(mapper, index, "INCOMPLETE", "MISSING_AMOUNT");
        result.put("column_index", column);
        var missing = mapper.createArrayNode();
        var invalid = mapper.createArrayNode();
        var ambiguous = mapper.createArrayNode();
        var amounts = new BigInteger[ROW_CODES.length];
        for (int i = 0; i < labels.length; i++) {
            Cell amount = null;
            int matches = 0;
            for (var cell : cells) {
                if (cell.covers(labels[i].row, column)) {
                    amount = cell;
                    matches++;
                }
            }
            if (matches > 1 || amount != null && (!amount.single() || amount.raw.path("column_header").asBoolean(false))) {
                ambiguous.add(ROW_CODES[i]);
            } else if (amount == null || missingAmount(amount.text())) {
                missing.add(ROW_CODES[i]);
            } else {
                amounts[i] = parseInteger(amount.text());
                if (amounts[i] == null) invalid.add(ROW_CODES[i]);
            }
        }
        if (!missing.isEmpty()) result.set("missing_row_codes", missing);
        if (!invalid.isEmpty()) result.set("invalid_row_codes", invalid);
        if (!ambiguous.isEmpty()) result.set("ambiguous_row_codes", ambiguous);
        if (!ambiguous.isEmpty()) {
            result.put("status", "AMBIGUOUS").put("reason", "MERGED_OR_OVERLAPPING_AMOUNT");
        } else if (!invalid.isEmpty()) {
            result.put("status", "INVALID_NUMERIC").put("reason", "MALFORMED_INTEGER");
        } else if (missing.isEmpty()) {
            boolean consistent = amounts[0].add(amounts[1]).add(amounts[2]).equals(amounts[3]);
            result.put("status", consistent ? "CONSISTENT" : "INCONSISTENT")
                    .put("reason", consistent ? "IDENTITY_MATCH" : "IDENTITY_MISMATCH");
        }
        return result;
    }

    private static boolean missingAmount(String text) {
        String value = text.strip();
        return value.isEmpty() || value.equals("-") || value.equals("—") || value.equals("–");
    }

    private static BigInteger parseInteger(String text) {
        if (text.length() > 80) return null;
        String value = text.strip().replace('\u00a0', ' ').replace('\u202f', ' ');
        boolean negative = value.startsWith("-") || value.startsWith("(") && value.endsWith(")");
        if (value.startsWith("-")) value = value.substring(1);
        else if (negative) value = value.substring(1, value.length() - 1);
        if (!INTEGER.matcher(value).matches()) return null;
        var number = new BigInteger(value.replace(".", "").replace(",", "").replace(" ", ""));
        return negative ? number.negate() : number;
    }

    private static int rowCode(String label) {
        if (label.contains("cashandcashequivalents") || label.contains("tienvatuongduongtien")) {
            if (label.contains("beginning") || label.contains("daunam") || label.contains("dauky")) return 60;
            if (label.contains("attheend") || label.contains("cuoinam") || label.contains("cuoiky")) return 70;
        }
        if (label.contains("netcashflow") && (label.contains("duringtheyear") || label.contains("duringtheperiod"))
                || label.contains("luuchuyentienthuantrongnam") || label.contains("luuchuyentienthuantrongky")) return 50;
        if (label.contains("impact") && (label.contains("exchangerate") || label.contains("foreignexchange"))
                || label.contains("anhhuong") && label.contains("tygia")) return 61;
        return -1;
    }

    private static String periodIdentity(String header) {
        if (header.startsWith("unit:vnd")) header = header.substring("unit:vnd".length());
        return switch (header) {
            case "currentyear", "currentperiod", "namnay", "kynay" -> "CURRENT";
            case "previousyear", "prioryear", "previousperiod", "priorperiod", "namtruoc", "kytruoc" -> "PRIOR";
            default -> YEAR.matcher(header).matches() ? header : "";
        };
    }

    private static String normalize(String text) {
        if (text.length() > 512) return "";
        String folded = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT).replace('đ', 'd');
        return SPACES.matcher(folded).replaceAll("");
    }

    private static Cell cell(JsonNode raw) {
        for (var offset : OFFSETS) {
            if (!raw.path(offset).isIntegralNumber() || !raw.path(offset).canConvertToInt()) return null;
        }
        return new Cell(raw, raw.path(OFFSETS[0]).asInt(), raw.path(OFFSETS[1]).asInt(),
                raw.path(OFFSETS[2]).asInt(), raw.path(OFFSETS[3]).asInt());
    }

    private static boolean isolated(Cell target, List<Cell> cells) {
        if (!target.single()) return false;
        for (var other : cells) {
            if (other != target && other.covers(target.row, target.column)) return false;
        }
        return true;
    }

    private static ObjectNode diagnostic(ObjectMapper mapper, int index, String status, String reason) {
        return mapper.createObjectNode().put("check", "CASH_FLOW_CLOSING_BALANCE")
                .put("block_index", index).put("status", status).put("reason", reason);
    }

    private static boolean add(ArrayNode checks, ObjectNode check) {
        if (checks.size() == MAX_CHECKS) {
            check.put("status", "INCOMPLETE").put("reason", "ASSESSMENT_LIMIT");
            check.remove("column_index");
            check.remove("missing_row_codes");
            check.remove("invalid_row_codes");
            check.remove("ambiguous_row_codes");
            checks.set(MAX_CHECKS - 1, check);
            return false;
        }
        checks.add(check);
        return true;
    }

    private record Cell(JsonNode raw, int row, int endRow, int column, int endColumn) {
        String text() { return raw.path("text").asString(""); }
        boolean single() { return endRow == row + 1 && endColumn == column + 1; }
        boolean covers(int targetRow, int targetColumn) {
            return row <= targetRow && targetRow < endRow && column <= targetColumn && targetColumn < endColumn;
        }
    }
}
