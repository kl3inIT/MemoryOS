package io.memoryos.provider.sharepoint;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.provider.StructuredContent;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a SharePoint page snapshot into canonical blocks. A text web part carries HTML, so its headings,
 * paragraphs, lists and tables are kept as blocks rather than flattened; the other web parts carry the
 * searchable text Microsoft prepared, which becomes plain paragraphs.
 */
public final class SharePointPageSourceContentExtractor {
    static final String MEDIA_TYPE = "application/vnd.memoryos.sharepoint-page+json";
    private static final int MAX_DEPTH = 64;

    private final ObjectMapper mapper;

    public SharePointPageSourceContentExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public DocumentContent extract(InputStream content, long size, String filename, SourceInputDescriptor input)
            throws ExtractionException {
        byte[] bytes = StructuredContent.read(content, size, StructuredContent.MAX_BYTES);
        JsonNode snapshot;
        try {
            snapshot = mapper.readTree(bytes);
        } catch (JacksonException exception) {
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
        if (snapshot == null || !RestSharePointProvider.PAGE_SCHEMA.equals(snapshot.path("schema").asString(""))
                || !snapshot.path("content").isObject()
                || input.providerFileId() == null || input.providerVersion() == null
                || !input.providerFileId().equals(snapshot.path("source").path("id").asString(""))
                || !input.providerVersion().equals(snapshot.path("source").path("version").asString(""))) {
            throw StructuredContent.failure(ExtractionFailure.MALFORMED);
        }
        JsonNode page = snapshot.path("content");
        String title = page.path("title").asString("");
        if (title.isBlank()) throw StructuredContent.failure(ExtractionFailure.MALFORMED);

        var output = new StructuredContent(mapper, input);
        var properties = output.canonical().putObject("pageProperties");
        properties.put("title", title);
        properties.put("webUrl", page.path("webUrl").asString(""));
        heading(output, title, 1);
        paragraph(output, page.path("textAboveTitle").asString(""));
        paragraph(output, page.path("description").asString(""));
        for (JsonNode part : page.path("parts")) {
            output.checkTime();
            if ("text".equals(part.path("kind").asString(""))) {
                html(output, part.path("html").asString(""));
            } else {
                paragraph(output, part.path("title").asString(""));
                for (JsonNode text : part.path("texts")) paragraph(output, text.asString(""));
            }
        }
        return output.finish(MEDIA_TYPE, title.isBlank() ? filename : title, "sharepoint-page-v1");
    }

    /** Keeps the shape of a text web part: headings, paragraphs, list items and table cells. */
    private void html(StructuredContent output, String value) throws ExtractionException {
        if (value.isBlank()) return;
        var body = Jsoup.parse(value, "", Parser.htmlParser()).body();
        for (Element element : body.select("h1, h2, h3, h4, h5, h6, p, li, table")) {
            output.checkTime();
            if (element.parents().size() > MAX_DEPTH) continue;
            String tag = element.tagName().toLowerCase(Locale.ROOT);
            if ("table".equals(tag)) {
                table(output, element);
            } else if (tag.length() == 2 && tag.charAt(0) == 'h') {
                heading(output, element.text(), Character.getNumericValue(tag.charAt(1)));
            } else if ("li".equals(tag)) {
                block(output, "listItem", element.text());
            } else if (element.select("table").isEmpty()) {
                paragraph(output, element.text());
            }
        }
        if (body.select("h1, h2, h3, h4, h5, h6, p, li, table").isEmpty()) paragraph(output, body.text());
    }

    private void table(StructuredContent output, Element table) throws ExtractionException {
        var block = output.block("table");
        var rows = block.putArray("rows");
        for (Element row : table.select("tr")) {
            output.checkTime();
            var cells = rows.addArray();
            for (Element cell : row.select("th, td")) {
                output.cell();
                String text = cell.text().strip();
                cells.add(text);
                output.append(text);
                output.append("\t");
            }
            output.append("\n");
        }
    }

    private void heading(StructuredContent output, String text, int level) throws ExtractionException {
        if (text.isBlank()) return;
        var block = output.block("heading");
        block.put("level", Math.clamp(level, 1, 6));
        block.put("text", text.strip());
        output.append(text.strip());
        output.append("\n\n");
    }

    private void paragraph(StructuredContent output, String text) throws ExtractionException {
        block(output, "paragraph", text);
    }

    private void block(StructuredContent output, String kind, String text) throws ExtractionException {
        if (text == null || text.isBlank()) return;
        var block = output.block(kind);
        block.put("text", text.strip());
        output.append(text.strip());
        output.append("\n");
    }

    /** The bytes a snapshot is stored as; the reader only accepts this media type. */
    public static byte[] snapshotOf(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
