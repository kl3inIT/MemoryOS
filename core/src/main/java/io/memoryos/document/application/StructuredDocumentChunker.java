package io.memoryos.document.application;

import io.memoryos.document.DocumentChunk;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
        JsonNode root = mapper.readTree(canonicalJson);
        if (!"memoryos-extraction-v1".equals(root.path("schema").asString())
                || !root.path("blocks").isArray()) {
            throw new IllegalArgumentException("unsupported extraction artifact");
        }
        var result = new ArrayList<DocumentChunk>();
        var headings = new ArrayList<String>();
        int position = 0;
        for (JsonNode block : root.path("blocks")) {
            int blockIndex = block.path("index").asInt(position++);
            String kind = block.path("kind").asString();
            String text = block.path("text").asString("").strip();
            if ("HEADING".equals(kind)) {
                int level = Math.clamp(block.path("headingLevel").asInt(1), 1, 8);
                while (headings.size() >= level) headings.removeLast();
                if (!text.isEmpty()) headings.add(text);
            }
            String prefix = prefix(title, headings);
            String provenance = mapper.writeValueAsString(block.path("provenance"));
            if ("TABLE".equals(kind) && block.path("table").path("table_cells").isArray()) {
                appendTable(result, block.path("table"), prefix, headings, blockIndex, provenance);
            } else if (!"IMAGE".equals(kind) && !text.isEmpty()) {
                append(result, text, prefix, headings, blockIndex, provenance, 0);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("artifact has no searchable text");
        return List.copyOf(result);
    }

    private String prefix(String title, List<String> headings) {
        String value = "Title: " + title.strip() + (headings.isEmpty() ? "" : "\nSection: " + String.join(" > ", headings));
        // Reserve most of each passage for actual content; truncate on code point boundaries.
        while (tokens.estimate(value) > 192) value = value.substring(0, value.offsetByCodePoints(0,
                Math.max(1, value.codePointCount(0, value.length()) * 3 / 4)));
        return value + "\n";
    }

    private void appendTable(List<DocumentChunk> result, JsonNode table, String prefix,
            List<String> headings, int blockIndex, String provenance) {
        var cells = new ArrayList<JsonNode>();
        table.path("table_cells").forEach(cells::add);
        if (cells.size() > 100_000) throw new IllegalArgumentException("table exceeds cell limit");
        cells.sort(Comparator.comparingInt((JsonNode c) -> c.path("start_row_offset_idx").asInt())
                .thenComparingInt(c -> c.path("start_col_offset_idx").asInt()));
        var headers = new TreeMap<Integer, List<String>>();
        int expandedCells = 0;
        for (JsonNode cell : cells) {
            if (!cell.path("column_header").asBoolean(false)) continue;
            int start = cell.path("start_col_offset_idx").asInt();
            int end = cell.path("end_col_offset_idx").asInt(start + 1);
            if (start < 0 || end <= start || end - start > 2048) throw new IllegalArgumentException("invalid table span");
            expandedCells += end - start;
            if (expandedCells > 100_000) throw new IllegalArgumentException("expanded table exceeds cell limit");
            for (int column = start; column < end; column++) {
                headers.computeIfAbsent(column, _ -> new ArrayList<>()).add(cell.path("text").asString(""));
            }
        }
        var rows = new TreeMap<Integer, List<JsonNode>>();
        for (JsonNode cell : cells) {
            if (cell.path("column_header").asBoolean(false)) continue;
            int start = cell.path("start_row_offset_idx").asInt();
            int end = cell.path("end_row_offset_idx").asInt(start + 1);
            if (start < 0 || end <= start || end - start > 2048) throw new IllegalArgumentException("invalid table row span");
            expandedCells += end - start;
            if (expandedCells > 100_000) throw new IllegalArgumentException("expanded table exceeds cell limit");
            for (int row = start; row < end; row++) rows.computeIfAbsent(row, _ -> new ArrayList<>()).add(cell);
        }
        int part = 0;
        for (var entry : rows.entrySet()) {
            // Each value repeats its column label; wide rows split only between cells.
            String rowHeader = entry.getValue().stream().filter(c -> c.path("row_header").asBoolean(false))
                    .map(c -> c.path("text").asString("")).reduce((a, b) -> a + " / " + b).orElse("");
            String rowPrefix = prefix + (rowHeader.isBlank() ? "" : "Row: " + rowHeader + "\n");
            var row = new StringBuilder();
            for (JsonNode cell : entry.getValue()) {
                int column = cell.path("start_col_offset_idx").asInt();
                String label = String.join(" / ", headers.getOrDefault(column, List.of("Column " + (column + 1))));
                String value = label + ": " + cell.path("text").asString("");
                if (!row.isEmpty() && tokens.estimate(rowPrefix + row + "\n" + value) > MAX_TOKENS) {
                    part = append(result, row.toString(), rowPrefix, headings, blockIndex,
                            tableLocation(provenance, entry.getKey()), part);
                    row.setLength(0);
                }
                if (!row.isEmpty()) row.append('\n');
                row.append(value);
            }
            if (!row.isEmpty()) part = append(result, row.toString(), rowPrefix,
                    headings, blockIndex, tableLocation(provenance, entry.getKey()), part);
        }
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
            if (result.size() >= MAX_CHUNKS) throw new IllegalArgumentException("document exceeds chunk limit");
            result.add(new DocumentChunk(result.size(), passage, headings, blockIndex, part++, provenance,
                    sha256(passage), tokens.estimate(passage)));
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
        if (tokens.estimate(prefix + text.substring(0, end)) > MAX_TOKENS) throw new IllegalArgumentException("unbounded chunk");
        return end;
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
