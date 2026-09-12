package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.connector.SourceInputDescriptor;

import java.io.ByteArrayInputStream;
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
    void chatDecodesPngJpegAndWebpInChildAndRejectsHeaderOnlyImage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var extractor = new TikaSourceContentExtractor()) {
            for (var format : java.util.List.of("png", "jpeg")) {
                var file = directory.resolve("wide." + format);
                var image = new java.awt.image.BufferedImage(3000, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
                assertTrue(javax.imageio.ImageIO.write(image, format, file.toFile()));
                image.flush();
                var result = extractor.extractChatFile(file, file.getFileName().toString(), "image/" + format);
                assertTrue(result.normalizedText().contains("3000x2"));
                assertEquals("chat-imageio", result.metadata().get("parser"));
            }
            var webp = directory.resolve("pixel.webp");
            java.nio.file.Files.write(webp, java.util.Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA"));
            assertTrue(extractor.extractChatFile(webp, "pixel.webp", "image/webp").normalizedText().contains("1x1"));
            var truncated = directory.resolve("truncated.png");
            java.nio.file.Files.write(truncated, java.util.Arrays.copyOf(java.nio.file.Files.readAllBytes(directory.resolve("wide.png")), 33));
            assertEquals(ExtractionFailure.MALFORMED, assertThrows(ExtractionException.class,
                    () -> extractor.extractChatFile(truncated, "truncated.png", "image/png")).failure());
        }
    }

    @Test
    void chatMarkupReusesIsolatedProcessAndCanonicalResponse(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var file = directory.resolve("mail.html");
        java.nio.file.Files.writeString(file, "<html><body><h1>Private note</h1><script>secretScript()</script><p>Body text</p></body></html>");
        try (var extractor = new TikaSourceContentExtractor()) {
            var result = extractor.extractChatFile(file, "mail.html", "text/html");
            assertTrue(result.normalizedText().contains("Private note"));
            assertTrue(result.normalizedText().contains("Body text"));
            org.junit.jupiter.api.Assertions.assertFalse(result.normalizedText().contains("secretScript"));
            assertTrue(result.structuredJson().contains("memoryos-extraction-v1"));
        }
        try (var extractor = new TikaSourceContentExtractor(Duration.ofMillis(1))) {
            assertEquals(ExtractionFailure.TIMEOUT, assertThrows(ExtractionException.class,
                    () -> extractor.extractChatFile(file, "mail.html", "text/html")).failure());
        }
        assertTrue(java.nio.file.Files.exists(file), "The caller, not the child process, owns the Chat spool");
    }

    @Test
    void extractsUtf8TextAndMarkdownWithoutTrustingTheExtension() throws Exception {
        try (var extractor = new TikaSourceContentExtractor()) {
            var text = extract(extractor, "MemoryOS plain text".getBytes(StandardCharsets.UTF_8), "wrong.pdf");
            assertEquals("text/plain", text.mediaType());
            assertTrue(text.normalizedText().contains("MemoryOS plain text"));

            var markdown = extract(extractor, "# MemoryOS\nConnector content".getBytes(StandardCharsets.UTF_8), "notes.md");
            assertTrue(markdown.mediaType().startsWith("text/"));
            assertTrue(markdown.normalizedText().contains("Connector content"));
        }
    }

    @Test
    void extractsPdfAndDocxVisibleText() throws Exception {
        try (var extractor = new TikaSourceContentExtractor()) {
            var pdf = extract(extractor, pdf(), "document.bin");
            assertEquals("application/pdf", pdf.mediaType());
            assertTrue(pdf.normalizedText().contains("MemoryOS PDF content"));

            var docx = extract(extractor, docx(), "document.bin");
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
                    () -> extract(extractor, new byte[]{0x50, 0x4b, 0x03, 0x04}, "archive.zip")
            );
            assertEquals(ExtractionFailure.UNSUPPORTED, exception.failure());
        }
    }

    @Test
    void classifiesTimeoutEncryptedMalformedAndWriteLimitedDocuments() throws Exception {
        try (var extractor = new TikaSourceContentExtractor(Duration.ZERO)) {
            ExtractionException timeout = assertThrows(
                    ExtractionException.class,
                    () -> extract(extractor, "content".getBytes(StandardCharsets.UTF_8), "timeout.txt")
            );
            assertEquals(ExtractionFailure.TIMEOUT, timeout.failure());
        }

        try (var extractor = new TikaSourceContentExtractor()) {
            ExtractionException encrypted = assertThrows(
                    ExtractionException.class,
                    () -> extract(extractor, encryptedPdf(), "encrypted.pdf")
            );
            assertEquals(ExtractionFailure.ENCRYPTED, encrypted.failure());

            byte[] validPdf = pdf();
            ExtractionException malformed = assertThrows(
                    ExtractionException.class,
                    () -> extract(extractor, Arrays.copyOf(validPdf, validPdf.length / 2), "truncated.pdf")
            );
            assertEquals(ExtractionFailure.MALFORMED, malformed.failure());

            ExtractionException limited = assertThrows(
                    ExtractionException.class,
                    () -> extract(extractor, "x".repeat(2_000_100).getBytes(StandardCharsets.UTF_8), "large.txt")
            );
            assertEquals(ExtractionFailure.WRITE_LIMIT, limited.failure());
        }
    }
    @Test
    void timeoutTerminatesTheChildAndCloseRemainsBounded() {
        try (var extractor = new TikaSourceContentExtractor(Duration.ofMillis(1))) {
            ExtractionException timeout = assertThrows(
                    ExtractionException.class,
                    () -> extract(extractor, pdf(), "timeout.pdf")
            );

            assertEquals(ExtractionFailure.TIMEOUT, timeout.failure());
            assertTimeoutPreemptively(Duration.ofSeconds(2), extractor::close);
        }
    }


    private static DocumentContent extract(
            TikaSourceContentExtractor extractor,
            byte[] content,
            String filename
    ) throws ExtractionException {
        return extractor.extract(new ByteArrayInputStream(content), content.length, filename, SourceInputDescriptor.binary());
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
