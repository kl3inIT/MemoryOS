package io.memoryos.provider.file;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
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
    private static final int MAX_HEADER_DEPTH = 4;
    private static final int AMBIGUOUS_CASH_BALANCE = -2;
    private static final int[] ROW_CODES = {50, 60, 61, 70};
    private static final String[] OFFSETS = {
            "start_row_offset_idx", "end_row_offset_idx", "start_col_offset_idx", "end_col_offset_idx"
    };
    private static final String[] UNIT_PREFIXES = {"unit:vnd", "currency:vnd", "donvitinh:vnd"};
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern INTEGER = Pattern.compile(
            "(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+|[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,3}(?: [0-9]{3})+)");
    private static final Pattern YEAR = Pattern.compile("(?:19|20)[0-9]{2}");
    private static final Pattern QUARTER = Pattern.compile("quy([1-4])nam((?:19|20)[0-9]{2})");
    private static final Pattern HEADER_RULES = Pattern.compile("^[|=—–-]+|[|=—–-]+$");

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
            if (rawCells.size() > MAX_TOTAL_CELLS - totalCells) {
                add(checks, diagnostic(mapper, index, "INCOMPLETE", "ASSESSMENT_LIMIT"));
                break;
            }
            totalCells += rawCells.size();
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
        int rows = table.path("num_rows").asInt(-1);
        int columns = table.path("num_cols").asInt(-1);
        if (rows > MAX_TABLE_CELLS || columns > MAX_COLUMNS) {
            return add(checks, diagnostic(mapper, index, "INCOMPLETE", "ASSESSMENT_LIMIT"));
        }
        boolean cashBalanceLabel = false;
        boolean incomeLabels = false;
        for (var raw : rawCells) {
            String label = normalize(raw.path("text").asString(""));
            int code = rowCode(label);
            incomeLabels = incomeLabels || incomeIdentity(label) != 0;
            if (code == 60 || code == 70 || code == AMBIGUOUS_CASH_BALANCE) {
                cashBalanceLabel = true;
                break;
            }
        }
        if (!cashBalanceLabel) {
            return !incomeLabels || assessIncomeRows(rawCells, rows, columns, index, checks, mapper);
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
        var issues = mapper.createArrayNode();
        var labels = new Cell[ROW_CODES.length];
        var duplicateLabels = new boolean[ROW_CODES.length];
        for (var cell : cells) {
            int code = rowCode(normalize(cell.text()));
            for (int i = 0; i < ROW_CODES.length; i++) {
                boolean ambiguousBalance = code == AMBIGUOUS_CASH_BALANCE && (ROW_CODES[i] == 60 || ROW_CODES[i] == 70);
                if (code != ROW_CODES[i] && !ambiguousBalance) continue;
                if (ambiguousBalance || labels[i] != null || !isolated(cell, cells)
                        || cell.raw.path("column_header").asBoolean(false)) duplicateLabels[i] = true;
                labels[i] = cell;
            }
        }
        int firstRow = rows;
        int codeColumn = -1;
        Cell previousLabel = null;
        for (int i = 0; i < labels.length; i++) {
            var label = labels[i];
            if (label == null) {
                rowIssue(issues, "MISSING_ROW_IDENTITY", ROW_CODES[i]);
                continue;
            }
            firstRow = Math.min(firstRow, label.row);
            if (duplicateLabels[i] || previousLabel != null
                    && (label.row <= previousLabel.row || label.column != previousLabel.column)) {
                rowIssue(issues, "AMBIGUOUS_ROW_IDENTITY", ROW_CODES[i]);
            }
            previousLabel = label;
            Cell codeCell = null;
            boolean ambiguousCode = false;
            for (var cell : cells) {
                if (cell.row != label.row || cell.text().length() > 80
                        || !cell.text().strip().equals(Integer.toString(ROW_CODES[i]))) continue;
                if (codeCell != null || !isolated(cell, cells) || cell.column <= label.column
                        || cell.raw.path("column_header").asBoolean(false)) ambiguousCode = true;
                codeCell = cell;
            }
            if (codeCell == null) {
                rowIssue(issues, "MISSING_ROW_CODE", ROW_CODES[i]);
            } else if (ambiguousCode || codeColumn >= 0 && codeColumn != codeCell.column) {
                rowIssue(issues, "AMBIGUOUS_ROW_CODE", ROW_CODES[i]);
            } else {
                codeColumn = codeCell.column;
            }
        }
        var periods = new ArrayList<Period>();
        var identities = new HashSet<String>();
        if (codeColumn < 0) {
            issues.addObject().put("reason", "UNRESOLVED_PERIOD_COLUMNS");
        } else {
            int rowIssueCount = issues.size();
            for (int column = codeColumn + 1; column < columns; column++) {
                var headers = new ArrayList<Cell>(MAX_HEADER_DEPTH);
                boolean occupied = false;
                boolean tooDeep = false;
                for (var cell : cells) {
                    if (cell.column > column || cell.endColumn <= column) continue;
                    if (cell.row < firstRow && cell.raw.path("column_header").asBoolean(false)) {
                        if (headers.size() == MAX_HEADER_DEPTH) {
                            tooDeep = true;
                            break;
                        }
                        headers.add(cell);
                    } else {
                        for (var label : labels) {
                            if (label != null && cell.covers(label.row, column)) occupied = true;
                        }
                    }
                }
                if (tooDeep) return add(checks, diagnostic(mapper, index, "INCOMPLETE", "ASSESSMENT_LIMIT"));
                if (headers.isEmpty() && !occupied) continue;
                headers.sort(Comparator.comparingInt(Cell::row));
                String identity = headerIdentity(headers, cells, firstRow, codeColumn);
                if (identity.equals("NOTE")) continue;
                if (identity.equals("AMBIGUOUS") || !identity.isEmpty() && !identities.add(identity)) {
                    columnIssue(issues, "AMBIGUOUS_PERIOD_HEADERS", column);
                } else if (identity.isEmpty()) {
                    columnIssue(issues, "MISSING_PERIOD_HEADERS", column);
                } else {
                    periods.add(new Period(column, identity));
                }
            }
            if (periods.isEmpty() && issues.size() == rowIssueCount) {
                issues.addObject().put("reason", "MISSING_PERIOD_HEADERS");
            }
        }
        if (!issues.isEmpty()) {
            String reason = issues.get(0).path("reason").asString();
            String status = "INCOMPLETE";
            for (var issue : issues) {
                if (issue.path("reason").asString().startsWith("AMBIGUOUS")) {
                    status = "AMBIGUOUS";
                    reason = issue.path("reason").asString();
                    break;
                }
            }
            var result = diagnostic(mapper, index, status, reason);
            result.set("structure_issues", issues);
            return add(checks, result);
        }
        // Every period belongs to this table; absent structures never come from another page.
        for (var period : periods) {
            var result = assessPeriod(cells, labels, period.column, index, mapper);
            result.put("period_identity", period.identity);
            if (!add(checks, result)) return false;
        }
        return true;
    }

    private static boolean assessIncomeRows(JsonNode rawCells, int rows, int columns, int index,
            ArrayNode checks, ObjectMapper mapper) {
        if (rows < 1 || columns < 1) {
            return add(checks, diagnostic(mapper, index, "AMBIGUOUS", "INVALID_CELL_GEOMETRY")
                    .put("check", "INCOME_STATEMENT_ROW_IDENTITY"));
        }
        int[] codes = new int[columns];
        int codeRow = -1;
        int codeIdentities = 0;
        for (var raw : rawCells) {
            if (raw.path("column_header").asBoolean(false)) continue;
            String text = raw.path("text").asString("");
            if (text.length() > 80) continue;
            int identity = switch (text.strip()) {
                case "10" -> 1;
                case "20" -> 2;
                case "50" -> 4;
                default -> 0;
            };
            if (identity == 0) continue;
            var cell = cell(raw);
            if (cell == null || cell.row < 0 || cell.column < 0 || cell.endRow <= cell.row
                    || cell.endColumn <= cell.column || cell.endRow > rows || cell.endColumn > columns) {
                return add(checks, diagnostic(mapper, index, "AMBIGUOUS", "INVALID_CELL_GEOMETRY")
                        .put("check", "INCOME_STATEMENT_ROW_IDENTITY"));
            }
            if (!cell.single()) continue;
            if ((codeRow >= 0 && codeRow != cell.row) || codes[cell.column] != 0) return true;
            codeRow = cell.row;
            codes[cell.column] = identity;
            codeIdentities |= identity;
        }
        if (Integer.bitCount(codeIdentities) < 2) return true;
        int[] identities = new int[rows];
        for (var raw : rawCells) {
            if (raw.path("column_header").asBoolean(false)) continue;
            int identity = incomeIdentity(normalize(raw.path("text").asString("")));
            if (identity == 0) continue;
            var cell = cell(raw);
            if (cell == null || cell.row < 0 || cell.column < 0 || cell.endRow <= cell.row
                    || cell.endColumn <= cell.column || cell.endRow > rows || cell.endColumn > columns) {
                return add(checks, diagnostic(mapper, index, "AMBIGUOUS", "INVALID_CELL_GEOMETRY")
                        .put("check", "INCOME_STATEMENT_ROW_IDENTITY"));
            }
            if (!cell.single() || cell.row <= codeRow || codes[cell.column] != identity) continue;
            identities[cell.row] |= identity;
            if (Integer.bitCount(identities[cell.row]) >= 2) {
                return add(checks, diagnostic(mapper, index, "INCOMPLETE", "NON_ROW_ORIENTED_INCOME_LABELS")
                        .put("check", "INCOME_STATEMENT_ROW_IDENTITY")
                        .put("label_row_index", cell.row).put("code_row_index", codeRow));
            }
        }
        return true;
    }

    private static int incomeIdentity(String label) {
        if (label.contains("netrevenue") || label.contains("doanhthuthuan")) return 1;
        if (label.contains("grossprofit") || label.contains("loinhuangop")) return 2;
        if (label.contains("profitbeforetax") || label.contains("loinhuanketoantruocthue")) return 4;
        return 0;
    }

    private static void rowIssue(ArrayNode issues, String reason, int code) {
        issues.addObject().put("reason", reason).put("row_code", code);
    }

    private static void columnIssue(ArrayNode issues, String reason, int column) {
        issues.addObject().put("reason", reason).put("column_index", column);
    }

    private static String headerIdentity(List<Cell> headers, List<Cell> cells, int firstRow, int codeColumn) {
        var text = new StringBuilder();
        int endRow = -1;
        boolean namedPeriod = false;
        for (var header : headers) {
            if (header.endRow > firstRow || header.column <= codeColumn
                    || endRow >= 0 && header.row != endRow) return "AMBIGUOUS";
            for (var other : cells) {
                if (other != header && other.row < header.endRow && header.row < other.endRow
                        && other.column < header.endColumn && header.column < other.endColumn) return "AMBIGUOUS";
            }
            endRow = header.endRow;
            if (header.text().length() > 512) return "";
            String part = headerText(header.text());
            // A shared currency ancestor does not identify a period; its explicit leaves must.
            if (unitHeader(part)) continue;
            if (header.endColumn != header.column + 1) return "AMBIGUOUS";
            if (text.length() + part.length() > 512) return "";
            if (!periodIdentity(part).isEmpty()) {
                if (namedPeriod) return "AMBIGUOUS";
                namedPeriod = true;
            }
            text.append(part);
        }
        String value = text.toString();
        if (value.equals("note") || value.equals("notes") || value.equals("thuyetminh")) return "NOTE";
        return periodIdentity(value);
    }

    private static String headerText(String text) {
        return HEADER_RULES.matcher(normalize(text)).replaceAll("");
    }

    private static boolean unitHeader(String text) {
        return text.equals("unit:vnd") || text.equals("currency:vnd") || text.equals("donvitinh:vnd");
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
        if (text.length() > 80) return false;
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
            if (label.equals("cashandcashequivalentsatboy")) return 60;
            boolean beginning = label.contains("beginning") || label.contains("daunam") || label.contains("dauky");
            boolean ending = label.contains("attheend") || label.contains("cuoinam") || label.contains("cuoiky");
            if (beginning && ending) return AMBIGUOUS_CASH_BALANCE;
            if (beginning != ending) return beginning ? 60 : 70;
        }
        if (label.contains("netcashflow") && (label.contains("duringtheyear") || label.contains("duringtheperiod"))
                || label.contains("luuchuyentienthuantrongnam") || label.contains("luuchuyentienthuantrongky")) return 50;
        if (label.contains("impact") && (label.contains("exchangerate") || label.contains("foreignexchange"))
                || label.contains("anhhuong") && label.contains("tygia")) return 61;
        return -1;
    }

    private static String periodIdentity(String header) {
        for (var prefix : UNIT_PREFIXES) {
            if (header.startsWith(prefix)) {
                header = header.substring(prefix.length());
                break;
            }
        }
        var quarter = QUARTER.matcher(header);
        if (quarter.matches()) return quarter.group(2) + "-Q" + quarter.group(1);
        return switch (header) {
            case "currentyear", "currentperiod", "namnay", "kynay" -> "CURRENT";
            case "previousyear", "prioryear", "previousperiod", "priorperiod", "namtruoc", "kytruoc" -> "PRIOR";
            default -> YEAR.matcher(header).matches() ? header : "";
        };
    }

    private static String normalize(String text) {
        if (text.length() > 512) return "";
        String folded = MARKS.matcher(Normalizer.normalize(
                        text.replace('\u00a0', ' ').replace('\u202f', ' '), Normalizer.Form.NFD)).replaceAll("")
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
            check.remove("structure_issues");
            check.remove("period_identity");
            check.remove("label_row_index");
            check.remove("code_row_index");
            return false;
        }
        checks.add(check);
        return true;
    }

    private record Period(int column, String identity) {}

    private record Cell(JsonNode raw, int row, int endRow, int column, int endColumn) {
        String text() { return raw.path("text").asString(""); }
        boolean single() { return endRow == row + 1 && endColumn == column + 1; }
        boolean covers(int targetRow, int targetColumn) {
            return row <= targetRow && targetRow < endRow && column <= targetColumn && targetColumn < endColumn;
        }
    }
}
