package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "DOCLING_TEST_ENDPOINT", matches = "https?://.+")
class DoclingServeIntegrationTest {
    @Test
    void realServiceReadsPptxSlideText() throws Exception {
        byte[] input;
        try (var out = new ByteArrayOutputStream(); var zip = new ZipOutputStream(out)) {
            write(zip, "[Content_Types].xml", """
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
                    <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                    </Types>
                    """);
            write(zip, "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                    </Relationships>
                    """);
            write(zip, "ppt/presentation.xml", """
                    <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/>
                    </p:presentation>
                    """);
            write(zip, "ppt/_rels/presentation.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                    </Relationships>
                    """);
            write(zip, "ppt/slides/slide1.xml", """
                    <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                    <p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
                    <p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
                    <p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="8000000" cy="1000000"/></a:xfrm></p:spPr>
                    <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>MemoryOS slide 127</a:t></a:r></a:p></p:txBody></p:sp>
                    </p:spTree></p:cSld></p:sld>
                    """);
            zip.finish();
            input = out.toByteArray();
        }
        try (var extractor = new DoclingSourceContentExtractor(new DoclingProperties(
                URI.create(System.getenv("DOCLING_TEST_ENDPOINT")), null, Duration.ofMinutes(5), 200), new ObjectMapper())) {
            var result = extractor.extract(new ByteArrayInputStream(input), input.length, "slide.pptx");
            assertTrue(result.normalizedText().contains("MemoryOS slide 127"));
        }
    }

    @Test
    void realServiceExtractsScannedPdfWithOcrAndPageProvenance() throws Exception {
        var image = new java.awt.image.BufferedImage(1200, 600, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, 1200, 600);
        graphics.setColor(java.awt.Color.BLACK);
        graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 56));
        graphics.drawString("MemoryOS OCR verification 127", 70, 200);
        graphics.dispose();
        byte[] input;
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                stream.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(pdf, image),
                        0, 400, 600, 300);
            }
            pdf.save(output);
            input = output.toByteArray();
        }
        try (var extractor = new DoclingSourceContentExtractor(new DoclingProperties(
                URI.create(System.getenv("DOCLING_TEST_ENDPOINT")), null, Duration.ofMinutes(5), 200), new ObjectMapper())) {
            var result = extractor.extract(new ByteArrayInputStream(input), input.length, "scan.pdf");
            assertTrue(result.normalizedText().contains("127"));
            assertTrue(result.structuredJson().contains("page_no"));
        }
    }

    @Test
    void realServicePreservesVietnameseTextAndTableCells() throws Exception {
        byte[] input;
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
            write(zip, "[Content_Types].xml", """
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            write(zip, "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """);
            write(zip, "word/document.xml", """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Báo cáo nhân sự HROD</w:t></w:r></w:p>
                    <w:tbl><w:tblGrid><w:gridCol w:w="4000"/><w:gridCol w:w="4000"/></w:tblGrid>
                    <w:tr><w:tc><w:p><w:r><w:t>Chỉ tiêu</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Giá trị</w:t></w:r></w:p></w:tc></w:tr>
                    <w:tr><w:tc><w:p><w:r><w:t>Nhân sự</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>127</w:t></w:r></w:p></w:tc></w:tr>
                    </w:tbl></w:body></w:document>
                    """);
            zip.finish();
            input = output.toByteArray();
        }
        try (var extractor = new DoclingSourceContentExtractor(new DoclingProperties(
                URI.create(System.getenv("DOCLING_TEST_ENDPOINT")), null, Duration.ofMinutes(5), 200),
                new ObjectMapper())) {
            var result = extractor.extract(new ByteArrayInputStream(input), input.length, "hrod.docx");
            assertTrue(result.normalizedText().contains("Báo cáo nhân sự HROD"));
            var blocks = new ObjectMapper().readTree(result.structuredJson()).path("blocks");
            assertTrue(blocks.valueStream().anyMatch(block -> "TABLE".equals(block.path("kind").asString())));
            assertTrue(result.structuredJson().contains("Nhân sự"));
            assertTrue(result.structuredJson().contains("127"));
        }
    }

    private static void write(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
