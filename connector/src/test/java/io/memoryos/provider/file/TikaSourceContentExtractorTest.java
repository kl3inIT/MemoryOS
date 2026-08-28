package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.Arrays;

import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
@SuppressWarnings("HttpUrlsUsage")

class TikaSourceContentExtractorTest {

    @Test
    void extractsUtf8TextAndMarkdownWithoutTrustingTheExtension() throws Exception {
        try (var extractor = new TikaSourceContentExtractor()) {
            var text = extractor.extract("MemoryOS plain text".getBytes(StandardCharsets.UTF_8), "wrong.pdf");
            assertEquals("text/plain", text.mediaType());
            assertTrue(text.normalizedText().contains("MemoryOS plain text"));

            var markdown = extractor.extract("# MemoryOS\nConnector content".getBytes(StandardCharsets.UTF_8), "notes.md");
            assertTrue(markdown.mediaType().startsWith("text/"));
            assertTrue(markdown.normalizedText().contains("Connector content"));
        }
    }

    @Test
    void extractsPdfAndDocxVisibleText() throws Exception {
        try (var extractor = new TikaSourceContentExtractor()) {
            var pdf = extractor.extract(pdf(), "document.bin");
            assertEquals("application/pdf", pdf.mediaType());
            assertTrue(pdf.normalizedText().contains("MemoryOS PDF content"));

            var docx = extractor.extract(docx(), "document.bin");
            assertEquals(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    docx.mediaType()
            );
            assertTrue(docx.normalizedText().contains("MemoryOS DOCX content"));
        }
    }

    @Test
    void rejectsUnsupportedContentAsTypedFailure() {
        try (var extractor = new TikaSourceContentExtractor()) {
            ExtractionException exception = assertThrows(
                    ExtractionException.class,
                    () -> extractor.extract(new byte[]{0x50, 0x4b, 0x03, 0x04}, "archive.zip")
            );
            assertEquals(ExtractionFailure.UNSUPPORTED, exception.failure());
        }
    }

    @Test
    void classifiesTimeoutEncryptedMalformedAndWriteLimitedDocuments() throws Exception {
        try (var extractor = new TikaSourceContentExtractor(Duration.ZERO)) {
            ExtractionException timeout = assertThrows(
                    ExtractionException.class,
                    () -> extractor.extract("content".getBytes(StandardCharsets.UTF_8), "timeout.txt")
            );
            assertEquals(ExtractionFailure.TIMEOUT, timeout.failure());
        }

        try (var extractor = new TikaSourceContentExtractor()) {
            ExtractionException encrypted = assertThrows(
                    ExtractionException.class,
                    () -> extractor.extract(encryptedPdf(), "encrypted.pdf")
            );
            assertEquals(ExtractionFailure.ENCRYPTED, encrypted.failure());

            byte[] validPdf = pdf();
            ExtractionException malformed = assertThrows(
                    ExtractionException.class,
                    () -> extractor.extract(
                            Arrays.copyOf(validPdf, validPdf.length / 2),
                            "truncated.pdf"
                    )
            );
            assertEquals(ExtractionFailure.MALFORMED, malformed.failure());

            ExtractionException limited = assertThrows(
                    ExtractionException.class,
                    () -> extractor.extract(
                            "x".repeat(2_000_100).getBytes(StandardCharsets.UTF_8),
                            "large.txt"
                    )
            );
            assertEquals(ExtractionFailure.WRITE_LIMIT, limited.failure());
        }
    }

    private static byte[] pdf() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("MemoryOS PDF content");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] encryptedPdf() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            var policy = new StandardProtectionPolicy("owner-password", "user-password", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] docx() throws Exception {
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
            write(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml"
                        ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """);
            write(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1"
                        Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                        Target="word/document.xml"/>
                    </Relationships>
                    """);
            write(zip, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>%s</w:t></w:r></w:p></w:body>
                    </w:document>
                    """.formatted("MemoryOS DOCX content"));
            zip.finish();
            return output.toByteArray();
        }
    }

    private static void write(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
