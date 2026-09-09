package io.memoryos.provider.google;

import static io.memoryos.connector.GoogleDriveProviderException.Failure.*;

import io.memoryos.connector.GoogleDriveLinkReader;
import io.memoryos.connector.GoogleDriveProvider.AcquiredContent;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.provider.StructuredContent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class OfflineGoogleDriveLinkReader implements GoogleDriveLinkReader {
    static final int MAX_PAGES = 200;
    private final ObjectMapper mapper;

    public OfflineGoogleDriveLinkReader(ObjectMapper mapper) { this.mapper = mapper; }

    @Override public List<Link> read(AcquiredContent input) {
        var links = new Links();
        boolean nativeInput = input.descriptor().format() != io.memoryos.connector.SourceInputFormat.BINARY;
        if (input.bytes().length == 0) throw failure(MALFORMED);
        if (input.bytes().length > (nativeInput ? StructuredContent.MAX_BYTES : 10_485_760)) throw failure(LIMIT_EXCEEDED);
        try {
            switch (input.descriptor().format()) {
                case GOOGLE_SHEETS -> sheets(snapshot(input, "GOOGLE_SHEETS"), links);
                case GOOGLE_DOCS -> docs(snapshot(input, "GOOGLE_DOCS"), input, links);
                case BINARY -> binary(input, links);
            }
            links.checkTime();
            return List.copyOf(links.values);
        } catch (GoogleDriveProviderException exception) {
            throw exception;
        } catch (ExtractionException exception) {
            throw failure(switch (exception.failure()) {
                case WRITE_LIMIT, TIMEOUT -> LIMIT_EXCEEDED;
                case UNSUPPORTED, ENCRYPTED -> UNSUPPORTED;
                default -> MALFORMED;
            });
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException exception) {
            throw failure(UNSUPPORTED);
        } catch (IOException | RuntimeException exception) {
            throw failure(MALFORMED);
        }
    }

    private JsonNode snapshot(AcquiredContent input, String kind) throws ExtractionException {
        JsonNode content = NativeSnapshot.read(mapper, new ByteArrayInputStream(input.bytes()), input.bytes().length,
                input.descriptor(), kind).path("content");
        String idField = "GOOGLE_SHEETS".equals(kind) ? "spreadsheetId" : "documentId";
        if (!input.descriptor().providerFileId().equals(content.path(idField).asString())) throw failure(MALFORMED);
        return content;
    }

    private void sheets(JsonNode content, Links links) {
        JsonNode sheets = content.path("sheets");
        if (!sheets.isArray() || sheets.isEmpty()) throw failure(MALFORMED);
        if (sheets.size() > StructuredContent.MAX_TABS) throw failure(LIMIT_EXCEEDED);
        long represented = 0;
        Set<Integer> ids = new HashSet<>();
        for (JsonNode sheet : sheets) {
            JsonNode properties = sheet.path("properties");
            if (!ids.add(properties.path("sheetId").asInt(0))) throw failure(MALFORMED);
            if (!"GRID".equals(properties.path("sheetType").asString("GRID"))) throw failure(UNSUPPORTED);
            int rows = properties.path("gridProperties").path("rowCount").asInt(-1);
            int columns = properties.path("gridProperties").path("columnCount").asInt(-1);
            if (rows < 1 || columns < 1 || (represented += (long) rows * columns) > StructuredContent.MAX_CELLS) {
                throw failure(LIMIT_EXCEEDED);
            }
            String title = properties.path("title").asString("");
            if (title.isBlank()) throw failure(MALFORMED);
            sheetPages(sheet.path("pages"), rows, columns, label(title), links);
        }
    }

    private void sheetPages(JsonNode pages, int rows, int columns, String title, Links links) {
        if (!pages.isArray() || pages.size() > 256) throw failure(MALFORMED);
        int expected = 0;
        for (JsonNode page : pages) {
            int start = page.path("startRow").asInt(-1);
            int end = page.path("endRow").asInt(-1);
            if (start != expected || end <= start || end > rows || end - start > 500) throw failure(MALFORMED);
            expected = end;
            JsonNode grids = page.path("data");
            if (!grids.isArray()) throw failure(MALFORMED);
            Set<Long> coordinates = new HashSet<>();
            for (JsonNode grid : grids) {
                int firstRow = grid.path("startRow").asInt(0);
                int firstColumn = grid.path("startColumn").asInt(0);
                JsonNode rowData = grid.path("rowData");
                if (rowData.isMissingNode()) continue;
                if (!rowData.isArray() || firstRow < start || firstColumn < 0
                        || (long) firstRow + rowData.size() > end || firstColumn >= columns) throw failure(MALFORMED);
                for (int r = 0; r < rowData.size(); r++) {
                    JsonNode values = rowData.get(r).path("values");
                    if (values.isMissingNode()) continue;
                    if (!values.isArray() || (long) firstColumn + values.size() > columns) throw failure(MALFORMED);
                    for (int c = 0; c < values.size(); c++) {
                        if (!coordinates.add((long) (firstRow + r) * columns + firstColumn + c)
                                || !values.get(c).isObject()) throw failure(MALFORMED);
                        String location = title + "!" + RestGoogleDriveProvider.columnName(firstColumn + c + 1) + (firstRow + r + 1);
                        strings(values.get(c), location, links);
                    }
                }
            }
        }
        if (expected != rows) throw failure(MALFORMED);
    }

    private void docs(JsonNode document, AcquiredContent input, Links links) {
        if (!input.descriptor().providerFileId().equals(document.path("documentId").asString())) throw failure(MALFORMED);
        JsonNode tabs = document.path("tabs");
        if (!tabs.isArray() || tabs.isEmpty()) throw failure(MALFORMED);
        docTabs(tabs, links, new int[1]);
    }

    private void docTabs(JsonNode tabs, Links links, int[] count) {
        for (JsonNode tab : tabs) {
            if (++count[0] > StructuredContent.MAX_TABS) throw failure(LIMIT_EXCEEDED);
            if (!tab.path("documentTab").isObject()) throw failure(UNSUPPORTED);
            String title = label(tab.path("tabProperties").path("title").asString("Tab " + count[0]));
            docNodes(tab.path("documentTab"), title, links, new int[1]);
            JsonNode children = tab.path("childTabs");
            if (!children.isMissingNode()) {
                if (!children.isArray()) throw failure(MALFORMED);
                docTabs(children, links, count);
            }
        }
    }

    private void docNodes(JsonNode node, String title, Links links, int[] paragraphs) {
        if (node.has("paragraph")) {
            String location = title + ", paragraph " + ++paragraphs[0];
            var text = new StringBuilder();
            for (JsonNode element : node.path("paragraph").path("elements")) {
                text.append(element.path("textRun").path("content").asString(""));
                for (String field : element.propertyNames()) {
                    strings("textRun".equals(field) ? element.path(field).path("textStyle") : element.path(field), location, links);
                }
            }
            links.text(text.toString(), location);
            return;
        }
        if (node.isArray() || node.isObject()) for (JsonNode child : node) docNodes(child, title, links, paragraphs);
    }

    private void strings(JsonNode node, String location, Links links) {
        links.checkTime();
        if (node.isString()) links.text(node.asString(), location);
        else if (node.isObject()) {
            for (String field : node.propertyNames()) {
                JsonNode child = node.path(field);
                if (child.isString() && ("uri".equals(field) || "url".equals(field) || "hyperlink".equals(field))) links.target(child.asString(), location);
                else strings(child, location, links);
            }
        } else if (node.isArray()) for (JsonNode child : node) strings(child, location, links);
    }

    private void binary(AcquiredContent input, Links links) throws IOException {
        switch (input.mediaType()) {
            case "text/plain", "text/markdown", "text/x-markdown" -> {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input.bytes())).toString();
                int start = 0;
                int line = 1;
                for (int end = 0; end <= text.length(); end++) {
                    if (end == text.length() || text.charAt(end) == '\n') {
                        links.text(text.substring(start, end), "Line " + line++);
                        start = end + 1;
                    }
                }
            }
            case "text/csv", "application/csv" -> csv(input.bytes(), links);
            case "application/pdf" -> pdf(input.bytes(), links);
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                 "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                    OfficeGoogleDriveLinks.read(input.bytes(), input.mediaType(), links);
            default -> throw failure(UNSUPPORTED);
        }
    }

    private void csv(byte[] bytes, Links links) throws IOException {
        try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
             var records = CSVFormat.RFC4180.parse(reader)) {
            int cells = 0;
            for (var record : records) {
                if ((cells += record.size()) > StructuredContent.MAX_CELLS) throw failure(LIMIT_EXCEEDED);
                for (int c = 0; c < record.size(); c++) links.text(record.get(c),
                        RestGoogleDriveProvider.columnName(c + 1) + record.getRecordNumber());
            }
        }
    }

    private void pdf(byte[] bytes, Links links) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw failure(UNSUPPORTED);
            if (document.getNumberOfPages() > MAX_PAGES) throw failure(LIMIT_EXCEEDED);
            var stripper = new PDFTextStripper() {
                private final StringBuilder pageText = new StringBuilder();
                private int glyphCharacters;
                private int renderedCharacters;
                @Override protected void processOperator(org.apache.pdfbox.contentstream.operator.Operator operator,
                        List<org.apache.pdfbox.cos.COSBase> operands) throws IOException {
                    links.checkTime();
                    super.processOperator(operator, operands);
                }
                @Override protected void processTextPosition(org.apache.pdfbox.text.TextPosition position) {
                    links.checkTime();
                    if ((glyphCharacters += position.getUnicode().length()) > StructuredContent.MAX_TEXT) throw failure(LIMIT_EXCEEDED);
                    super.processTextPosition(position);
                }
                @Override protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    append(text);
                }
                @Override protected void writeWordSeparator() { append(" "); }
                @Override protected void writeLineSeparator() { append("\n"); }
                private void append(String text) {
                    links.checkTime();
                    if ((renderedCharacters += text.length()) > StructuredContent.MAX_TEXT) throw failure(LIMIT_EXCEEDED);
                    pageText.append(text);
                }
                @Override protected void endPage(org.apache.pdfbox.pdmodel.PDPage page) {
                    links.text(pageText.toString(), "Page " + getCurrentPageNo());
                    pageText.setLength(0);
                }
            };
            stripper.writeText(document, java.io.Writer.nullWriter());
            int number = 0;
            for (var page : document.getPages()) {
                String location = "Page " + ++number;
                for (var annotation : page.getAnnotations()) {
                    links.checkTime();
                    if (annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI action) {
                        links.target(action.getURI(), location);
                    }
                }
            }
        }
    }

    static String label(String value) { return value.length() > 180 ? value.substring(0, 180) : value; }
    static GoogleDriveProviderException failure(GoogleDriveProviderException.Failure failure) {
        return new GoogleDriveProviderException(failure);
    }

    static final class Links {
        private static final Pattern URL = Pattern.compile("https://[^\\s\\p{Cntrl}<>\\\"'`\\[\\]{}]+", Pattern.CASE_INSENSITIVE);
        private final LinkedHashSet<Link> values = new LinkedHashSet<>();
        private final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(120).toNanos();
        private long characters;

        void text(String text, String location) {
            checkTime();
            if ((characters += text.length()) > NativeSnapshot.MAX_RAW_TEXT) throw failure(LIMIT_EXCEEDED);
            var matcher = URL.matcher(text);
            while (matcher.find()) {
                if (matcher.end() - matcher.start() > 8192) throw failure(LIMIT_EXCEEDED);
                String candidate = matcher.group();
                int end = candidate.length();
                while (end > 0 && ".,;:!?".indexOf(candidate.charAt(end - 1)) >= 0) end--;
                long extraClosers = candidate.chars().filter(c -> c == ')').count()
                        - candidate.chars().filter(c -> c == '(').count();
                while (end > 0 && candidate.charAt(end - 1) == ')' && extraClosers-- > 0) end--;
                target(candidate.substring(0, end), location);
            }
        }

        void target(String url, String location) {
            checkTime();
            if (url == null || url.isBlank()) return;
            if (url.length() > 8192) throw failure(LIMIT_EXCEEDED);
            try {
                URI uri = URI.create(url);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return;
                if (values.add(new Link(url, location)) && values.size() > 2000) throw failure(LIMIT_EXCEEDED);
            } catch (IllegalArgumentException ignored) { /* Invalid references are not navigable HTTPS links. */ }
        }

        void checkTime() { if (System.nanoTime() - deadline >= 0) throw failure(LIMIT_EXCEEDED); }
    }
}
