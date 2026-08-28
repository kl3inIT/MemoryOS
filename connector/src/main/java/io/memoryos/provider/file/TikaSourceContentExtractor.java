package io.memoryos.provider.file;

import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.ingestion.ExtractionResult;
import io.memoryos.ingestion.SourceContentExtractor;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.tika.Tika;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.pdf.OcrConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.exception.WriteLimitReachedException;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

public final class TikaSourceContentExtractor implements SourceContentExtractor, AutoCloseable {

    private static final int MAX_TEXT_CHARACTERS = 2_000_000;
    private static final Duration DEFAULT_EXTRACTION_TIMEOUT = Duration.ofSeconds(90);
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown"
    );

    private final Tika tika = new Tika();
    private final AutoDetectParser parser = new AutoDetectParser();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Duration extractionTimeout;

    public TikaSourceContentExtractor() {
        this(DEFAULT_EXTRACTION_TIMEOUT);
    }

    TikaSourceContentExtractor(Duration extractionTimeout) {
        this.extractionTimeout = Objects.requireNonNull(
                extractionTimeout,
                "extractionTimeout must not be null"
        );
        if (extractionTimeout.isNegative()) {
            throw new IllegalArgumentException("extractionTimeout must not be negative");
        }
    }

    @Override
    public ExtractionResult extract(byte[] content, String filename) throws ExtractionException {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(filename, "filename must not be null");
        if (extractionTimeout.isZero()) {
            throw new ExtractionException(ExtractionFailure.TIMEOUT, "document extraction timed out");
        }
        var task = executor.submit(() -> extractNow(content, filename));
        try {
            return task.get(extractionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            task.cancel(true);
            throw new ExtractionException(ExtractionFailure.TIMEOUT, "document extraction timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExtractionException(ExtractionFailure.TIMEOUT, "document extraction was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ExtractionException extractionException) {
                throw extractionException;
            }
            throw new ExtractionException(ExtractionFailure.INTERNAL, "document extraction failed", exception.getCause());
        }
    }

    private ExtractionResult extractNow(byte[] content, String filename) throws ExtractionException {
        try {
            String mediaType = tika.detect(content, filename);
            if (!SUPPORTED_MEDIA_TYPES.contains(mediaType)) {
                throw new ExtractionException(
                        ExtractionFailure.UNSUPPORTED,
                        "unsupported detected media type: " + mediaType
                );
            }
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_CHARACTERS);
            ParseContext context = parseContext();
            try (var stream = TikaInputStream.get(content, metadata)) {
                parser.parse(stream, handler, metadata, context);
            }
            String title = metadata.get(TikaCoreProperties.TITLE);
            if (title == null || title.isBlank()) {
                title = filename;
            }
            String normalizedText = normalize(handler.toString());
            return new ExtractionResult(mediaType, title, normalizedText, Map.of());
        } catch (EncryptedDocumentException exception) {
            throw new ExtractionException(ExtractionFailure.ENCRYPTED, "encrypted documents are not supported", exception);
        } catch (WriteLimitReachedException exception) {
            throw new ExtractionException(ExtractionFailure.WRITE_LIMIT, "extracted text exceeds the limit", exception);
        } catch (SAXException | TikaException | IOException exception) {
            throw new ExtractionException(ExtractionFailure.MALFORMED, "document content is malformed", exception);
        }
    }

    private static ParseContext parseContext() {
        ParseContext context = new ParseContext();
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.getOcr().setStrategy(OcrConfig.Strategy.NO_OCR);
        context.set(PDFParserConfig.class, pdf);
        context.set(EmbeddedDocumentExtractor.class, new RejectingEmbeddedDocumentExtractor());
        return context;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\u000B\\f ]+", " ")
                .replaceAll(" *\n *", "\n")
                .strip();
    }

    @Override
    public void close() {
        executor.close();
    }

    private static final class RejectingEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor {

        @Override
        public boolean shouldParseEmbedded(Metadata metadata, ParseContext context) {
            return false;
        }

        @Override
        public void parseEmbedded(
                TikaInputStream stream,
                ContentHandler handler,
                Metadata metadata,
                ParseContext context,
                boolean outputHtml
        ) {
        }
    }
}
