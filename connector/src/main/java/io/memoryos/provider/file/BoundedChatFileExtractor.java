package io.memoryos.provider.file;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ChatFileExtractor;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.provider.StructuredContent;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import org.apache.tika.Tika;
import tools.jackson.databind.ObjectMapper;

/** One bounded disk-backed extraction per worker; Source and native-source limits are unchanged. */
public final class BoundedChatFileExtractor implements ChatFileExtractor {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BoundedChatFileExtractor.class);
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
                long deadline = System.nanoTime() + java.time.Duration.ofMinutes(5).toNanos();
                while ((read = stream.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw StructuredContent.failure(ExtractionFailure.TIMEOUT);
                    copied += read;
                    if (copied > size) throw StructuredContent.failure(ExtractionFailure.MALFORMED);
                    output.write(buffer, 0, read);
                }
                if (copied != size) throw StructuredContent.failure(ExtractionFailure.MALFORMED);
            }
            String mediaType;
            try (var input = Files.newInputStream(file)) { mediaType = new Tika().detect(input, filename); }
            String name = filename.toLowerCase(Locale.ROOT);
            if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) return new SpreadsheetSourceContentExtractor(mapper).extractFile(file, filename,
                    name.endsWith(".xlsm") ? "application/vnd.ms-excel.sheet.macroEnabled.12" : SpreadsheetSourceContentExtractor.XLSX);
            if (name.endsWith(".csv") || name.endsWith(".tsv")) return new SpreadsheetSourceContentExtractor(mapper).extractFile(file, filename,
                    name.endsWith(".tsv") ? "text/tab-separated-values" : "text/csv");
            if (java.util.Set.of("text/html", "application/xhtml+xml", "message/rfc822", "application/epub+zip",
                    "image/png", "image/jpeg", "image/webp").contains(mediaType)) {
                try (var tika = new TikaSourceContentExtractor()) {
                    return tika.extractChatFile(file, filename, mediaType);
                }
            }
            if (DoclingSourceContentExtractor.usesDocling(mediaType)) return docling.extractChatFile(file, filename, mediaType);
            if (mediaType.startsWith("text/") || java.util.Set.of("json", "xml", "yaml", "yml", "sql", "conf", "log", "mdx").contains(extension(name)))
                return text(file, filename, mediaType);
            throw StructuredContent.failure(ExtractionFailure.UNSUPPORTED);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw StructuredContent.failure(ExtractionFailure.TIMEOUT); }
        catch (IOException invalid) { throw StructuredContent.failure(ExtractionFailure.MALFORMED); }
        finally {
            try { if (file != null) Files.deleteIfExists(file); }
            catch (IOException cleanup) { LOG.error("Chat extraction temporary file cleanup failed"); }
            finally { if (acquired) permit.release(); }
        }
    }

    private DocumentContent text(Path file, String filename, String mediaType) throws IOException, ExtractionException {
        var output = new StructuredContent(mapper, SourceInputDescriptor.binary());
        var text = new StringBuilder();
        try (var reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            char[] buffer = new char[8192]; int read;
            while ((read = reader.read(buffer)) >= 0) {
                output.append(new String(buffer, 0, read));
                text.append(buffer, 0, read);
            }
        }
        output.block("PARAGRAPH").put("text", text.toString());
        return output.finish(mediaType, filename, "chat-utf8");
    }

    private static String extension(String name) { return name.substring(name.lastIndexOf('.') + 1); }
}
