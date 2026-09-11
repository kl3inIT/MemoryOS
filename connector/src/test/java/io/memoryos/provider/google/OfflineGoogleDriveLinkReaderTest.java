package io.memoryos.provider.google;

import static io.memoryos.connector.GoogleDriveProviderException.Failure.*;
import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.connector.GoogleDriveLinkReader.Link;
import io.memoryos.connector.GoogleDriveProvider.AcquiredContent;
import io.memoryos.connector.GoogleDriveProvider.FileMetadata;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OfflineGoogleDriveLinkReaderTest {
    private static final String DOC = "https://docs.google.com/document/d/document1/edit";
    private static final String SHEET = "https://docs.google.com/spreadsheets/d/sheet1/edit";
    private static final String DRIVE = "https://drive.google.com/file/d/file1/view";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private final ObjectMapper mapper = new ObjectMapper();
    private final OfflineGoogleDriveLinkReader reader = new OfflineGoogleDriveLinkReader(mapper);

    @Test
    void nativeSheetsReadActualTargetsFormulasRunsChipsAndRetainDistinctCellOrigins() throws Exception {
        var content = mapper.readTree("""
                {"spreadsheetId":"source1","sheets":[{"properties":{"sheetId":7,"title":"Roadmap","gridProperties":{"rowCount":2,"columnCount":2}},
                  "pages":[{"startRow":0,"endRow":2,"data":[{"startRow":0,"rowData":[{"values":[
                    {"hyperlink":"https://docs.google.com/document/d/document1/edit", "formattedValue":"friendly title",
                     "userEnteredValue":{"formulaValue":"=HYPERLINK(\\\"https://docs.google.com/document/d/document1/edit\\\",\\\"title\\\")"},
                     "effectiveValue":{"stringValue":"https://docs.google.com/document/d/document1/edit"},
                     "textFormatRuns":[{"format":{"link":{"uri":"https://docs.google.com/spreadsheets/d/sheet1/edit"}}}],
                     "chipRuns":[{"chip":{"richLinkProperties":{"uri":"https://drive.google.com/file/d/file1/view"}}}],
                     "userEnteredFormat":{"textFormat":{"link":{"uri":"https://example.test/cell-wide"}}}}
                  ]},{"values":[{"userEnteredValue":{"stringValue":"See https://docs.google.com/document/d/document1/edit"}}]}]}]}]}]}
                """);
        List<Link> result = reader.read(nativeContent(content, SourceInputFormat.GOOGLE_SHEETS));
        assertEquals(List.of(new Link(DOC, "Roadmap!A1"), new Link(SHEET, "Roadmap!A1"), new Link(DRIVE, "Roadmap!A1"),
                new Link("https://example.test/cell-wide", "Roadmap!A1"), new Link(DOC, "Roadmap!A2")), result);
    }

    @Test
    void docsReadRichLinksAndStyleTargetsAndJoinSplitTextRunsAcrossChildTabs() throws Exception {
        var content = mapper.readTree("""
                {"documentId":"source1","tabs":[{"tabProperties":{"title":"Overview"},"documentTab":{"body":{"content":[
                  {"paragraph":{"elements":[{"textRun":{"content":"Friendly label","textStyle":{"link":{"url":"https://docs.google.com/document/d/document1/edit"}}}},
                    {"richLink":{"richLinkProperties":{"uri":"https://drive.google.com/file/d/file1/view","title":"Linked file"}}}]}},
                  {"paragraph":{"elements":[{"textRun":{"content":"https://docs.google.com/spreadsheets/"}}, {"textRun":{"content":"d/sheet1/edit"}}]}}
                ]}},"childTabs":[{"tabProperties":{"title":"Details"},"documentTab":{"body":{"content":[
                  {"paragraph":{"elements":[{"textRun":{"content":"https://docs.google.com/document/d/document1/edit"}}]}}
                ]}}}]}]}
                """);
        assertEquals(Set.of(new Link(DOC, "Overview, paragraph 1"), new Link(DRIVE, "Overview, paragraph 1"),
                new Link(SHEET, "Overview, paragraph 2"), new Link(DOC, "Details, paragraph 1")),
                Set.copyOf(reader.read(nativeContent(content, SourceInputFormat.GOOGLE_DOCS))));
    }

    @Test
    void xlsxReadsSharedInlineFormulaAndRelationshipTargetsWithoutEvaluatingExternalFormulas() throws Exception {
        var parts = workbookParts();
        parts.put("xl/sharedStrings.xml", "<sst xmlns=\"urn:sheet\"><si><r><t>" + DOC + "</t></r></si></sst>");
        parts.put("xl/worksheets/sheet1.xml", """
                <worksheet xmlns="urn:sheet" xmlns:r="urn:rel"><sheetData><row r="1">
                  <c r="A1" t="s"><v>0</v></c>
                  <c r="B1" t="inlineStr"><is><t>https://drive.google.com/file/d/file1/view</t></is></c>
                  <c r="C1"><f>HYPERLINK("https://docs.google.com/spreadsheets/d/sheet1/edit","label")</f><v>0</v></c>
                  <c r="D1"><f>WEBSERVICE("https://127.0.0.1:1/must-not-fetch")</f><v>0</v></c>
                </row></sheetData><hyperlinks><hyperlink ref="A1" r:id="link1"/></hyperlinks></worksheet>
                """);
        parts.put("xl/worksheets/_rels/sheet1.xml.rels", relations("link1", DOC, "hyperlink", true));
        assertEquals(Set.of(new Link(DOC, "Budget!A1"), new Link(DRIVE, "Budget!B1"), new Link(SHEET, "Budget!C1"),
                new Link("https://127.0.0.1:1/must-not-fetch", "Budget!D1")), Set.copyOf(reader.read(binary(zip(parts), XLSX))));
    }

    @Test
    void docxUsesRelationshipTargetsRatherThanLabelsAndDoesNotLoadExternalTemplates() throws Exception {
        var parts = new LinkedHashMap<String, String>();
        parts.put("word/document.xml", """
                <w:document xmlns:w="urn:word" xmlns:r="urn:rel"><w:body><w:p>
                  <w:hyperlink r:id="link1"><w:r><w:t>Friendly label</w:t></w:r></w:hyperlink>
                </w:p><w:p><w:r><w:t>https://docs.google.com/spreadsheets/</w:t></w:r><w:r><w:t>d/sheet1/edit</w:t></w:r></w:p>
                <w:p><w:fldSimple w:instr='HYPERLINK "https://drive.google.com/file/d/file1/view"'><w:r><w:t>Field label</w:t></w:r></w:fldSimple></w:p>
                </w:body></w:document>
                """);
        parts.put("word/_rels/document.xml.rels", relations("link1", DOC, "hyperlink", true));
        parts.put("word/_rels/settings.xml.rels", relations("template", "https://127.0.0.1:1/template", "attachedTemplate", true));
        assertEquals(Set.of(new Link(DOC, "Document, paragraph 1"), new Link(SHEET, "Document, paragraph 2"),
                new Link(DRIVE, "Document, paragraph 3")), Set.copyOf(reader.read(binary(zip(parts), DOCX))));
    }

    @Test
    void pptxResolvesSlideOrderTextAndShapeHyperlinks() throws Exception {
        var parts = new LinkedHashMap<String, String>();
        parts.put("ppt/presentation.xml", "<p:presentation xmlns:p=\"urn:ppt\" xmlns:r=\"urn:rel\"><p:sldIdLst><p:sldId r:id=\"slide2\"/></p:sldIdLst></p:presentation>");
        parts.put("ppt/_rels/presentation.xml.rels", relations("slide2", "slides/slide2.xml", "slide", false));
        parts.put("ppt/slides/slide2.xml", """
                <p:sld xmlns:p="urn:ppt" xmlns:a="urn:drawing" xmlns:r="urn:rel"><p:cSld><p:spTree><p:sp>
                  <p:nvSpPr><p:cNvPr id="1" name="Linked shape"><a:hlinkClick r:id="shapeLink"/></p:cNvPr></p:nvSpPr>
                  <p:txBody><a:p><a:r><a:rPr><a:hlinkClick r:id="textLink"/></a:rPr><a:t>Label</a:t></a:r></a:p></p:txBody>
                </p:sp></p:spTree></p:cSld></p:sld>
                """);
        parts.put("ppt/slides/_rels/slide2.xml.rels", "<Relationships>"
                + relation("shapeLink", DOC, "hyperlink", true) + relation("textLink", DRIVE, "hyperlink", true) + "</Relationships>");
        assertEquals(Set.of(new Link(DOC, "Slide 1, shape 1"), new Link(DRIVE, "Slide 1, paragraph 1")),
                Set.copyOf(reader.read(binary(zip(parts), PPTX))));
    }

    @Test
    void poiGeneratedOfficePackagesResolveActualHyperlinkRelationships() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            var cell = workbook.createSheet("Budget").createRow(2).createCell(1);
            cell.setCellValue("Friendly label");
            var hyperlink = workbook.getCreationHelper().createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.URL);
            hyperlink.setAddress(DOC);
            cell.setHyperlink(hyperlink);
            workbook.write(bytes);
        }
        assertEquals(List.of(new Link(DOC, "Budget!B3")), reader.read(binary(bytes.toByteArray(), XLSX)));
        bytes.reset();
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            document.createParagraph().createHyperlinkRun(DOC).setText("Friendly label");
            document.write(bytes);
        }
        assertEquals(List.of(new Link(DOC, "Document, paragraph 1")), reader.read(binary(bytes.toByteArray(), DOCX)));
        bytes.reset();
        try (var slides = new org.apache.poi.xslf.usermodel.XMLSlideShow()) {
            slides.createSlide().createTextBox().setText("Friendly label").createHyperlink().setAddress(DOC);
            slides.write(bytes);
        }
        assertEquals(List.of(new Link(DOC, "Slide 1, paragraph 1")), reader.read(binary(bytes.toByteArray(), PPTX)));
    }

    @Test
    void pdfReadsAnnotationTargetsAndVisibleUrlsWithPageProvenance() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            var annotation = new PDAnnotationLink();
            var action = new PDActionURI();
            action.setURI(DOC);
            annotation.setAction(action);
            page.getAnnotations().add(annotation);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                stream.newLineAtOffset(10, 700);
                stream.showText(SHEET);
                stream.endText();
            }
            var payload = document.getDocument().createCOSStream();
            try (var output = payload.createOutputStream()) {
                output.write(new byte[11 * 1024 * 1024]);
            }
            document.getDocumentCatalog().getCOSObject().setItem("Payload", payload);
            document.save(bytes);
        }
        assertEquals(Set.of(new Link(DOC, "Page 1"), new Link(SHEET, "Page 1")),
                Set.copyOf(reader.read(binary(bytes.toByteArray(), "application/pdf"))));
    }

    @Test
    void passwordProtectedPdfIsExplicitlyUnsupported() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner-password", "reader-password", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            document.save(bytes);
        }
        assertFailure(UNSUPPORTED, binary(bytes.toByteArray(), "application/pdf"));
    }

    @Test
    void csvAndMarkdownKeepQuotedCellsAndLiteralUrlBoundaries() {
        String csv = "\"=HYPERLINK(\"\"" + DOC + "\"\",\"\"label\"\")\",\"see " + SHEET + "\"\r\n";
        assertEquals(List.of(new Link(DOC, "A1"), new Link(SHEET, "B1")), reader.read(binary(bytes(csv), "text/csv")));
        assertEquals(List.of(new Link(DOC, "Line 1"), new Link("https://example.test/a_(b)", "Line 2")),
                reader.read(binary(bytes("[Doc](" + DOC + ")\nSee https://example.test/a_(b)."), "text/markdown")));
    }

    @Test
    void parserReturnsUntrustedHttpsReferencesForBackendFilteringButNeverOtherSchemes() {
        String text = "https://docs.google.com.evil.test/document/d/trick/edit "
                + "https://docs.google.com@evil.test/document/d/trick/edit "
                + "https://127.0.0.1:1/private "
                + "http://docs.google.com/document/d/insecure/edit javascript:alert(1) file:///etc/passwd";
        assertEquals(List.of(new Link("https://docs.google.com.evil.test/document/d/trick/edit", "Line 1"),
                new Link("https://docs.google.com@evil.test/document/d/trick/edit", "Line 1"),
                new Link("https://127.0.0.1:1/private", "Line 1")), reader.read(binary(bytes(text), "text/plain")));
    }

    @Test
    void distinctOriginLimitFailsRatherThanPublishingPartialLinks() {
        var text = new StringBuilder();
        for (int i = 0; i < 2001; i++) text.append(DOC).append('\n');
        assertFailure(LIMIT_EXCEEDED, binary(bytes(text.toString()), "text/plain"));
        assertEquals(List.of(new Link(DOC, "Line 1")), reader.read(binary(bytes((DOC + " ").repeat(2100)), "text/plain")));
    }

    @Test
    void rejectsCorruptUnsupportedAndOversizedInputsWithTypedFailures() throws Exception {
        assertFailure(UNSUPPORTED, binary(bytes("not an image"), "image/png"));
        assertFailure(MALFORMED, binary(new byte[]{(byte) 0xc3, 0x28}, "text/plain"));
        assertFailure(MALFORMED, binary(bytes("not a PDF"), "application/pdf"));
        assertFailure(MALFORMED, binary(bytes("not a ZIP"), XLSX));
        assertFailure(LIMIT_EXCEEDED, binary(new byte[104_857_601], "text/plain"));
        var incomplete = mapper.readTree("""
                {"spreadsheetId":"source1","sheets":[{"properties":{"title":"Missing","gridProperties":{"rowCount":2,"columnCount":1}},
                  "pages":[{"startRow":0,"endRow":1,"data":[]}]}]}
                """);
        assertFailure(MALFORMED, nativeContent(incomplete, SourceInputFormat.GOOGLE_SHEETS));
        var wrongIdentity = mapper.readTree("{\"spreadsheetId\":\"someone-else\",\"sheets\":[]}");
        assertFailure(MALFORMED, nativeContent(wrongIdentity, SourceInputFormat.GOOGLE_SHEETS));
    }

    @Test
    void officeAdmissionRejectsEntitiesDepthExpansionAndBrokenRelationships() throws Exception {
        var parts = workbookParts();
        parts.put("xl/worksheets/sheet1.xml", "<!DOCTYPE worksheet [<!ENTITY x SYSTEM 'https://127.0.0.1:1/secret'>]><worksheet>&x;</worksheet>");
        assertFailure(MALFORMED, binary(zip(parts), XLSX));
        parts.put("xl/worksheets/sheet1.xml", "<x>".repeat(101) + "</x>".repeat(101));
        assertFailure(LIMIT_EXCEEDED, binary(zip(parts), XLSX));
        parts.put("xl/worksheets/sheet1.xml", "<worksheet><sheetData/></worksheet>");
        parts.put("xl/media/opaque.bin", "x".repeat(1_048_577));
        assertFailure(LIMIT_EXCEEDED, binary(zip(parts), XLSX));
        parts.remove("xl/media/opaque.bin");
        parts.put("xl/_rels/workbook.xml.rels", relations("sheet1", "https://127.0.0.1:1/sheet", "worksheet", true));
        assertFailure(MALFORMED, binary(zip(parts), XLSX));
    }

    private AcquiredContent nativeContent(tools.jackson.databind.JsonNode content, SourceInputFormat format) {
        var file = new FileMetadata("source1", "Source", "application/json", "v1", null, null, false, List.of(), null, null);
        return new AcquiredContent("Source", "application/json", mapper.writeValueAsBytes(NativeSnapshot.envelope(mapper, file, format.name(), content)),
                new SourceInputDescriptor(format, "source1", "v1", null));
    }

    private AcquiredContent binary(byte[] bytes, String mediaType) {
        return new AcquiredContent("fixture", mediaType, bytes, SourceInputDescriptor.binary());
    }

    private void assertFailure(GoogleDriveProviderException.Failure failure, AcquiredContent content) {
        assertEquals(failure, assertThrows(GoogleDriveProviderException.class, () -> reader.read(content)).failure());
    }

    private static LinkedHashMap<String, String> workbookParts() {
        var parts = new LinkedHashMap<String, String>();
        parts.put("xl/workbook.xml", "<workbook xmlns=\"urn:sheet\" xmlns:r=\"urn:rel\"><sheets><sheet name=\"Budget\" r:id=\"sheet1\"/></sheets></workbook>");
        parts.put("xl/_rels/workbook.xml.rels", relations("sheet1", "worksheets/sheet1.xml", "worksheet", false));
        return parts;
    }

    private static String relations(String id, String target, String type, boolean external) {
        return "<Relationships>" + relation(id, target, type, external) + "</Relationships>";
    }

    private static String relation(String id, String target, String type, boolean external) {
        return "<Relationship Id=\"" + id + "\" Target=\"" + target + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
                + type + "\"" + (external ? " TargetMode=\"External\"" : "") + "/>";
    }

    private static byte[] zip(Map<String, String> parts) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(bytes("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>"));
            zip.closeEntry();
            for (var part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(bytes(part.getValue()));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
