package io.memoryos.connector.adapter.sharepoint;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.application.StructuredDocumentChunker;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The page reader over the canvas shapes the live-tenant spike recorded. */
class SharePointPageExtractionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SharePointPageSourceContentExtractor extractor = new SharePointPageSourceContentExtractor(mapper);

    @Test
    void keepsHeadingsListsAndTablesFromATextWebPart() throws Exception {
        String html = "<h2>Báo cáo quý</h2><p>Đoạn văn bản tiếng Việt.</p>"
                + "<ul><li>Mục một</li><li>Mục hai</li></ul>"
                + "<table><tbody><tr><th>Cột A</th><th>Cột B</th></tr><tr><td>Giá trị 1</td><td>Giá trị 2</td></tr></tbody></table>";
        var content = extract(snapshot("""
                {"kind":"text","html":%s}""".formatted(mapper.writeValueAsString(html))), descriptor());

        assertEquals("Trang thử nghiệm", content.title());
        assertTrue(content.normalizedText().contains("Báo cáo quý"));
        assertTrue(content.normalizedText().contains("Mục hai"));
        assertTrue(content.normalizedText().contains("Giá trị 1"), "table cells reach the text");
        var canonical = mapper.readTree(content.structuredJson());
        var kinds = canonical.path("blocks").valueStream().map(block -> block.path("kind").asString("")).toList();
        assertEquals("memoryos-extraction-v2", canonical.path("schema").asString());
        assertEquals(List.of("HEADING", "HEADING", "PARAGRAPH", "LIST_ITEM", "LIST_ITEM", "TABLE"), kinds);
        assertEquals("Trang thử nghiệm", canonical.at("/blocks/0/text").asString());
        assertEquals(2, canonical.at("/blocks/1/headingLevel").asInt());
        var chunks = new StructuredDocumentChunker(mapper).chunk(content.title(), content.structuredJson());
        assertTrue(canonical.at("/blocks/5/table/cells/0/columnHeader").asBoolean(), "a row of th heads the columns");
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().endsWith("Cột A: Giá trị 1\nCột B: Giá trị 2")),
                "table rows are labelled by the author's column headers");
        assertTrue(chunks.getLast().content().contains("Section: Trang thử nghiệm > Báo cáo quý"));
    }

    @Test
    void readsTheSearchableTextOfOtherWebParts() throws Exception {
        var content = extract(snapshot("""
                {"kind":"standard","title":"Liên kết nhanh","texts":["Tiêu đề tìm được","Báo cáo quý 1"]}"""),
                descriptor());

        assertTrue(content.normalizedText().contains("Liên kết nhanh"));
        assertTrue(content.normalizedText().contains("Báo cáo quý 1"));
    }

    @Test
    void anEmptyPageStillCarriesItsTitle() throws Exception {
        var content = extract(snapshot(null), descriptor());

        assertEquals("Trang thử nghiệm", content.title());
        assertTrue(content.normalizedText().contains("Trang thử nghiệm"));
    }

    @Test
    void rejectsSnapshotsThatDoNotMatchTheItemBeingRead() {
        assertThrows(ExtractionException.class, () -> extract("{\"schema\":\"something-else\"}", descriptor()));
        assertThrows(ExtractionException.class, () -> extract("not json", descriptor()));
        assertThrows(ExtractionException.class, () -> extract(snapshot(null),
                new SourceInputDescriptor(SourceInputFormat.SHAREPOINT_PAGE, "another-page", "etag-1:2026-09-16T02:00:00Z",
                        "https://contoso.sharepoint.com/sites/Finance/SitePages/Home.aspx")));
    }

    private static SourceInputDescriptor descriptor() {
        return new SourceInputDescriptor(SourceInputFormat.SHAREPOINT_PAGE, "page-1", "etag-1:2026-09-16T02:00:00Z",
                "https://contoso.sharepoint.com/sites/Finance/SitePages/Home.aspx");
    }

    private static String snapshot(String part) {
        return """
                {"schema":"memoryos-sharepoint-page-v1","kind":"SHAREPOINT_PAGE",
                 "source":{"id":"page-1","version":"etag-1:2026-09-16T02:00:00Z"},
                 "content":{"title":"Trang thử nghiệm",
                   "webUrl":"https://contoso.sharepoint.com/sites/Finance/SitePages/Home.aspx",
                   "parts":[%s]}}""".formatted(part == null ? "" : part);
    }

    /** The reader is told the exact size of the snapshot, as object storage reports it. */
    private DocumentContent extract(String json, SourceInputDescriptor descriptor)
            throws ExtractionException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return extractor.extract(new ByteArrayInputStream(bytes), bytes.length, "page.json", descriptor);
    }
}
