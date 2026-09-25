package io.memoryos.ingestion.extraction;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import io.memoryos.document.StructuredContent;
import io.memoryos.ingestion.ChatFileExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** One bounded disk-backed extraction per worker; Source and native-source limits are unchanged. */
public final class BoundedChatFileExtractor implements ChatFileExtractor {
    private static final Logger LOG = LoggerFactory.getLogger(BoundedChatFileExtractor.class);
    /** Detection holds no per-call state, so one facade serves every file. */
    private static final Tika TIKA = new Tika();
    private final DoclingSourceContentExtractor docling;
    private final ObjectMapper mapper;
    private final Semaphore permit = new Semaphore(1);

    public BoundedChatFileExtractor(DoclingSourceContentExtractor docling, ObjectMapper mapper) {
        this.docling = docling; this.mapper = mapper;
    }

    @Override public DocumentContent extract(InputStream stream, long size, String filename) throws ExtractionException {
        if (size < 1 || size > 262144000) throw StructuredContent.failure(ExtractionFailure.WRITE_LIMIT);
        Path file = null;
        boolean acquired = false;
        try {
            permit.acquire(); acquired = true;
            file = Files.createTempFile("memoryos-chat-file-", ".bin");
            try (var output = Files.newOutputStream(file)) {
                byte[] buffer = new byte[65536]; long copied = 0; int read;
                long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
                while ((read = stream.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw StructuredContent.failure(ExtractionFailure.TIMEOUT);
                    copied += read;
                    if (copied > size) throw StructuredContent.failure(ExtractionFailure.MALFORMED);
                    output.write(buffer, 0, read);
                }
                if (copied != size) throw StructuredContent.failure(ExtractionFailure.MALFORMED);
            }
            String mediaType;
            try (var input = Files.newInputStream(file)) { mediaType = TIKA.detect(input, filename); }
            String name = filename.toLowerCase(Locale.ROOT);
            if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) return new SpreadsheetSourceContentExtractor(mapper).extractFile(file, filename,
                    name.endsWith(".xlsm") ? "application/vnd.ms-excel.sheet.macroEnabled.12" : SpreadsheetSourceContentExtractor.XLSX);
            if (name.endsWith(".csv") || name.endsWith(".tsv")) return new SpreadsheetSourceContentExtractor(mapper).extractFile(file, filename,
                    name.endsWith(".tsv") ? "text/tab-separated-values" : "text/csv");
            if (docling.readsImage(mediaType)) {
                // PaddleOCR-VL reads the text of a scanned page or a screenshot; a photograph without
                // text keeps the image description below. A service failure fails the attachment.
                var read = docling.readChatImage(file, filename, mediaType);
                if (read != null) return read;
            }
            if (Set.of("text/html", "application/xhtml+xml", "message/rfc822", "application/epub+zip",
                    "image/png", "image/jpeg", "image/webp").contains(mediaType)) {
                try (var tika = new TikaSourceContentExtractor()) {
                    return tika.extractChatFile(file, filename, mediaType);
                }
            }
            if (DoclingSourceContentExtractor.usesDocling(mediaType)) return docling.extractChatFile(file, filename, mediaType);
            if (mediaType.startsWith("text/") || Set.of("json", "xml", "yaml", "yml", "sql", "conf", "log", "mdx").contains(extension(name)))
                return text(file, filename, mediaType);
            throw StructuredContent.failure(ExtractionFailure.UNSUPPORTED);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw StructuredContent.failure(ExtractionFailure.TIMEOUT); }
        catch (IOException invalid) { throw StructuredContent.failure(ExtractionFailure.MALFORMED); }
        finally {
            try { if (file != null) Files.deleteIfExists(file); }
            catch (IOException cleanup) {
                LOG.atError().addKeyValue("event", "ingestion.chat_file.cleanup_failed")
                        .addKeyValue("error_type", cleanup.getClass().getName()).log("Chat extraction temporary file cleanup failed");
            }
            finally { if (acquired) permit.release(); }
        }
    }

    private DocumentContent text(Path file, String filename, String mediaType) throws IOException, ExtractionException {
        var output = new StructuredContent(mapper, SourceInputDescriptor.binary());
        var text = new StringBuilder();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192]; int read;
            while ((read = reader.read(buffer)) >= 0) {
                output.append(new String(buffer, 0, read));
                text.append(buffer, 0, read);
            }
        }
        output.add(ExtractedDocument.Kind.PARAGRAPH, text.toString());
        return output.finish(mediaType, filename, "chat-utf8");
    }

    private static String extension(String name) { return name.substring(name.lastIndexOf('.') + 1); }
}
