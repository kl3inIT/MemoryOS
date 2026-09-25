package io.memoryos.document.application;

import io.memoryos.document.DocumentChunk;
import io.memoryos.document.DocumentContentException;
import io.memoryos.document.ExtractedDocument;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.document.ExtractedDocument.Table;
import io.memoryos.shared.Sha256;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/** Preserves structural boundaries and table labels before applying token limits. */
@Component
public final class StructuredDocumentChunker {
    public static final int MAX_TOKENS = 768;
    private static final int MAX_CHUNKS = 10_000;
    private final ObjectMapper mapper;
    private final JTokkitTokenCountEstimator tokens = new JTokkitTokenCountEstimator();

    public StructuredDocumentChunker(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<DocumentChunk> chunk(String title, String canonicalJson) {
        ExtractedDocument document = ExtractionArtifactReader.read(mapper, canonicalJson);
        var result = new ArrayList<DocumentChunk>();
        var headings = new ArrayList<String>();
        // Adjacent text blocks share one chunk up to the token bound; reports with thousands of
        // one-line paragraphs would otherwise exhaust MAX_CHUNKS. Headings and tables flush the
        // buffer so a merged chunk never crosses a section boundary.
        var pending = new StringBuilder();
        var pendingProvenance = mapper.createArrayNode();
        // Chat citations cap provenance at 8192 chars; keep the leading locations and drop the tail.
        int pendingProvenanceChars = 0;
        int pendingIndex = -1;
        for (Block block : document.blocks()) {
            int blockIndex = block.index();
            Kind kind = block.kind();
            String text = block.text().strip();
            if ((kind == Kind.HEADING || kind == Kind.TABLE) && !pending.isEmpty()) {
                append(result, pending.toString(), prefix(title, headings), headings, pendingIndex,
                        mergedProvenance(pendingProvenance), 0);
                pending.setLength(0);
                pendingProvenance.removeAll();
                pendingProvenanceChars = 0;
            }
            if (kind == Kind.HEADING) {
                int level = Math.clamp(block.headingLevel() == null ? 1 : block.headingLevel(), 1, 8);
                while (headings.size() >= level) headings.removeLast();
                if (!text.isEmpty()) headings.add(text);
            }
            String prefix = prefix(title, headings);
            JsonNode location = provenance(block);
            String provenance = mapper.writeValueAsString(location);
            if (kind == Kind.TABLE && block.table() != null) {
                // A sheet names its own addressed rows; labelled tables sit under the heading trail.
                String sheet = block.sheetName() == null ? text : block.sheetName();
                String tablePrefix = block.table().hasColumnHeaders() || sheet.isBlank()
                        ? prefix : prefix(title, List.of(sheet));
                appendTable(result, block.table(), tablePrefix, headings, blockIndex, provenance, 0);
            } else if (kind == Kind.HEADING) {
                if (!text.isEmpty()) append(result, text, prefix, headings, blockIndex, provenance, 0);
            } else if (kind != Kind.IMAGE && !text.isEmpty()) {
                if (pending.isEmpty()) {
                    pendingIndex = blockIndex;
                } else if (tokens.estimate(prefix + pending + "\n\n" + text) > MAX_TOKENS) {
                    append(result, pending.toString(), prefix, headings, pendingIndex,
                            mergedProvenance(pendingProvenance), 0);
                    pending.setLength(0);
                    pendingProvenance.removeAll();
                    pendingProvenanceChars = 0;
                    pendingIndex = blockIndex;
                }
                if (!pending.isEmpty()) pending.append("\n\n");
                pending.append(text);
                if (pendingProvenanceChars < 6000) {
                    pendingProvenance.add(location);
                    pendingProvenanceChars += provenance.length() + 1;
                }
            }
        }
        if (!pending.isEmpty()) {
            append(result, pending.toString(), prefix(title, headings), headings, pendingIndex,
                    mergedProvenance(pendingProvenance), 0);
        }
        if (result.isEmpty()) throw new DocumentContentException("SEARCH_INDEX_NO_TEXT", "artifact has no searchable text");
        return List.copyOf(result);
    }

    /** No location is `null`, one location is that object, and several are an array of them. */
    private JsonNode provenance(Block block) {
        var locations = block.locations();
        if (locations.isEmpty()) return mapper.nullNode();
        if (locations.size() == 1) return mapper.valueToTree(locations.getFirst());
        return mapper.valueToTree(locations);
    }

    /** One block keeps its original provenance shape; merged blocks report every source location. */
    private String mergedProvenance(ArrayNode provenance) {
        if (provenance.size() == 1) return mapper.writeValueAsString(provenance.get(0));
        return mapper.writeValueAsString(provenance);
    }

    /**
     * One table branch for every provider. A table with explicit column headers reads as labelled
     * values under its row headers; any other table reads by cell address. Never guess that the
     * first row is a header.
     */
    private void appendTable(List<DocumentChunk> result, Table table, String prefix,
            List<String> headings, int blockIndex, String provenance, int depth) {
        if (table.hasColumnHeaders()) appendLabelledTable(result, table, prefix, headings, blockIndex, provenance);
        else appendAddressedTable(result, table, prefix, headings, blockIndex, provenance, depth);
    }

    private void appendAddressedTable(List<DocumentChunk> result, Table table, String prefix,
            List<String> headings, int blockIndex, String provenance, int depth) {
        if (depth > 100 || table.cells().size() > 200000) throw new DocumentContentException("SEARCH_INDEX_CONTENT_LIMIT", "table exceeds bounds");
        var rows = new TreeMap<Integer, TreeMap<Integer, Cell>>();
        for (var cell : table.cells()) {
            int row = cell.row(), column = cell.column();
            if (row < 0 || row >= 1048576 || column < 0 || column >= 16384)
                throw new DocumentContentException("SEARCH_INDEX_ARTIFACT_INVALID", "invalid cell coordinates");
            if (rows.computeIfAbsent(row, ignored -> new TreeMap<>()).put(column, cell) != null)
                throw new DocumentContentException("SEARCH_INDEX_ARTIFACT_INVALID", "duplicate cell coordinates");
        }
        int part = 0;
        for (var row : rows.entrySet()) {
            var text = new StringBuilder();
            for (var entry : row.getValue().entrySet()) {
                var cell = entry.getValue();
                String address = columnName(entry.getKey()) + (row.getKey() + 1);
                var value = new StringBuilder(cell.text());
                // Google Docs table cells contain nested blocks, not spreadsheet values.
                for (var block : cell.blocks()) {
                    if (block.table() != null) {
                        appendTable(result, block.table(), prefix, headings, blockIndex,
                                mapper.writeValueAsString(provenance(block)), depth + 1);
                    } else {
                        if (!value.isEmpty()) value.append('\n');
                        value.append(block.text());
                    }
                }
                if (value.toString().isBlank()) continue;
                String located = "[" + address + "] " + value;
                part = appendCell(result, text, located, prefix, headings, blockIndex, tableLocation(provenance, row.getKey()), part);
            }
            if (!text.isEmpty()) part = append(result, text.toString(), prefix, headings, blockIndex,
                    tableLocation(provenance, row.getKey()), part);
        }
    }

    private static String columnName(int column) {
        var value = new StringBuilder();
        for (int position = column + 1; position > 0; position = (position - 1) / 26)
            value.append((char) ('A' + (position - 1) % 26));
        return value.reverse().toString();
    }

    private String prefix(String title, List<String> headings) {
        String value = "Title: " + title.strip() + (headings.isEmpty() ? "" : "\nSection: " + String.join(" > ", headings));
        // Reserve most of each passage for actual content; truncate on code point boundaries.
        while (tokens.estimate(value) > 192) value = value.substring(0, value.offsetByCodePoints(0,
                Math.max(1, value.codePointCount(0, value.length()) * 3 / 4)));
        return value + "\n";
    }

    private void appendLabelledTable(List<DocumentChunk> result, Table table, String prefix,
            List<String> headings, int blockIndex, String provenance) {
        var cells = new ArrayList<>(table.cells());
        if (cells.size() > 100_000) throw new DocumentContentException("SEARCH_INDEX_CONTENT_LIMIT", "table exceeds cell limit");
        cells.sort(Comparator.comparingInt(Cell::row).thenComparingInt(Cell::column));
        var headers = new TreeMap<Integer, List<String>>();
        int expandedCells = 0;
        for (Cell cell : cells) {
            if (!cell.columnHeader()) continue;
            int start = cell.column();
            long end = (long) start + cell.columnSpan();
            if (start < 0 || end <= start || end - start > 2048) throw new DocumentContentException("SEARCH_INDEX_ARTIFACT_INVALID", "invalid table span");
            expandedCells += (int) (end - start);
            if (expandedCells > 100_000) throw new DocumentContentException("SEARCH_INDEX_CONTENT_LIMIT", "expanded table exceeds cell limit");
            // A blank header cell adds nothing to a stacked label; a column with none reads "Column N".
            if (cell.text().isBlank()) continue;
            for (int column = start; column < end; column++) {
                headers.computeIfAbsent(column, _ -> new ArrayList<>()).add(cell.text());
            }
        }
        var rows = new TreeMap<Integer, List<Cell>>();
        for (Cell cell : cells) {
            if (cell.columnHeader()) continue;
            int start = cell.row();
            long end = (long) start + cell.rowSpan();
            if (start < 0 || end <= start || end - start > 2048) throw new DocumentContentException("SEARCH_INDEX_ARTIFACT_INVALID", "invalid table row span");
            expandedCells += (int) (end - start);
            if (expandedCells > 100_000) throw new DocumentContentException("SEARCH_INDEX_CONTENT_LIMIT", "expanded table exceeds cell limit");
            for (int row = start; row < end; row++) rows.computeIfAbsent(row, _ -> new ArrayList<>()).add(cell);
        }
        int part = 0;
        for (var entry : rows.entrySet()) {
            // Each value repeats its column label; wide rows split only between cells.
            String rowHeader = entry.getValue().stream().filter(Cell::rowHeader)
                    .map(Cell::text).reduce((a, b) -> a + " / " + b).orElse("");
            String rowPrefix = prefix + (rowHeader.isBlank() ? "" : "Row: " + rowHeader + "\n");
            var row = new StringBuilder();
            for (Cell cell : entry.getValue()) {
                // As in addressed mode, an empty cell has no value to label.
                if (cell.text().isBlank()) continue;
                int column = cell.column();
                String label = String.join(" / ", headers.getOrDefault(column, List.of("Column " + (column + 1))));
                String value = label + ": " + cell.text();
                part = appendCell(result, row, value, rowPrefix, headings, blockIndex, tableLocation(provenance, entry.getKey()), part);
            }
            if (!row.isEmpty()) part = append(result, row.toString(), rowPrefix,
                    headings, blockIndex, tableLocation(provenance, entry.getKey()), part);
        }
    }

    private int appendCell(List<DocumentChunk> result, StringBuilder row, String value, String prefix,
            List<String> headings, int blockIndex, String provenance, int part) {
        if (!row.isEmpty() && tokens.estimate(prefix + row + "\n" + value) > MAX_TOKENS) {
            part = append(result, row.toString(), prefix, headings, blockIndex, provenance, part);
            row.setLength(0);
        }
        if (!row.isEmpty()) row.append('\n');
        row.append(value);
        return part;
    }

    private String tableLocation(String provenance, int row) {
        var location = mapper.createObjectNode();
        location.set("source", mapper.readTree(provenance));
        location.put("tableRow", row);
        return mapper.writeValueAsString(location);
    }

    private int append(List<DocumentChunk> result, String text, String prefix, List<String> headings,
            int blockIndex, String provenance, int part) {
        int offset = 0;
        // Very large row headers must obey the same bound as section context.
        if (tokens.estimate(prefix) > 256) prefix = prefix("", List.of(prefix));
        while (offset < text.length()) {
            while (offset < text.length() && Character.isWhitespace(text.codePointAt(offset))) {
                offset += Character.charCount(text.codePointAt(offset));
            }
            if (offset == text.length()) break;
            // Tokenize a bounded lookahead, never the entire shrinking remainder of a large block.
            int windowEnd = Math.min(text.length(), offset + MAX_TOKENS * 12);
            if (windowEnd < text.length() && Character.isHighSurrogate(text.charAt(windowEnd - 1))) windowEnd--;
            String window = text.substring(offset, windowEnd);
            int end = boundedEnd(prefix, window);
            String passage = prefix + window.substring(0, end).strip();
            if (result.size() >= MAX_CHUNKS) throw new DocumentContentException("SEARCH_INDEX_CONTENT_LIMIT", "document exceeds chunk limit");
            result.add(new DocumentChunk(result.size(), passage, headings, blockIndex, part++, provenance,
                    Sha256.hex(passage), tokens.estimate(passage)));
            offset += end;
        }
        return part;
    }

    private int boundedEnd(String prefix, String text) {
        if (tokens.estimate(prefix + text) <= MAX_TOKENS) return text.length();
        int low = 1;
        int high = text.codePointCount(0, text.length());
        while (low < high) {
            int mid = (low + high + 1) / 2;
            int end = text.offsetByCodePoints(0, mid);
            if (tokens.estimate(prefix + text.substring(0, end)) <= MAX_TOKENS) low = mid;
            else high = mid - 1;
        }
        int end = text.offsetByCodePoints(0, low);
        int boundary = Math.max(text.lastIndexOf('\n', end - 1), text.lastIndexOf(' ', end - 1));
        if (boundary > end / 2) end = boundary;
        if (tokens.estimate(prefix + text.substring(0, end)) > MAX_TOKENS) throw new DocumentContentException("SEARCH_INDEX_ARTIFACT_INVALID", "unbounded chunk");
        return end;
    }
}
