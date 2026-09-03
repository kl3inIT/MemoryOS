package io.memoryos.provider.file;

import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.tika.Tika;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.OcrConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

final class TikaExtractionProcess {

    private static final int MAX_TEXT_CHARACTERS = 2_000_000;
    private static final int MAX_REQUEST_BYTES = 10 * 1024 * 1024;
    private static final int MAX_RESPONSE_STRING_BYTES = 8 * 1024 * 1024;
    private static final Set<String> SUPPORTED_MEDIA_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown"
    );

    private TikaExtractionProcess() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("expected request and response paths");
        }
        Path requestPath = Path.of(arguments[0]);
        Path responsePath = Path.of(arguments[1]);
        Request request = readRequest(requestPath);
        try {
            writeSuccess(responsePath, extract(request.content(), request.filename()));
        } catch (ExtractionException exception) {
            writeFailure(responsePath, exception.failure(), exception.getMessage());
        } catch (RuntimeException exception) {
            writeFailure(responsePath, ExtractionFailure.INTERNAL, "document extraction failed");
        }
    }

    static void writeRequest(Path path, InputStream content, long sizeBytes, String filename) throws IOException {
        Objects.requireNonNull(content, "content must not be null");
        if (sizeBytes < 1 || sizeBytes > MAX_REQUEST_BYTES) {
            throw new IOException("invalid extraction request size");
        }
        try (var output = new DataOutputStream(Files.newOutputStream(path))) {
            writeString(output, filename);
            output.writeInt(Math.toIntExact(sizeBytes));
            long copied = content.transferTo(output);
            if (copied != sizeBytes) {
                throw new IOException("extraction content size changed while streaming");
            }
        }
    }

    static DocumentContent readResponse(Path path) throws IOException, ExtractionException {
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            if (!input.readBoolean()) {
                ExtractionFailure failure = ExtractionFailure.valueOf(readString(input));
                throw new ExtractionException(failure, readString(input));
            }
            String mediaType = readString(input);
            String title = readString(input);
            String normalizedText = readString(input);
            int metadataSize = input.readInt();
            if (metadataSize < 0 || metadataSize > 1_000) {
                throw new IOException("invalid extraction metadata size");
            }
            Map<String, String> metadata = HashMap.newHashMap(metadataSize);
            for (int index = 0; index < metadataSize; index++) {
                metadata.put(readString(input), readString(input));
            }
            return new DocumentContent(mediaType, title, normalizedText, metadata);
        }
    }

    private static Request readRequest(Path path) throws IOException {
        try (var input = new DataInputStream(Files.newInputStream(path))) {
            String filename = readString(input);
            int contentLength = input.readInt();
            if (contentLength < 1 || contentLength > MAX_REQUEST_BYTES) {
                throw new IOException("invalid extraction request size");
            }
            return new Request(filename, readBytes(input, contentLength));
        }
    }

    private static DocumentContent extract(byte[] content, String filename) throws ExtractionException {
        var tika = new Tika();
        var parser = new AutoDetectParser();
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
            try (var stream = TikaInputStream.get(content, metadata)) {
                parser.parse(stream, handler, metadata, parseContext());
            }
            String title = metadata.get(TikaCoreProperties.TITLE);
            if (title == null || title.isBlank()) {
                title = filename;
            }
            return new DocumentContent(mediaType, title, normalize(handler.toString()), Map.of());
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

    private static void writeSuccess(Path path, DocumentContent result) throws IOException {
        try (var output = new DataOutputStream(Files.newOutputStream(path))) {
            output.writeBoolean(true);
            writeString(output, result.mediaType());
            writeString(output, result.title());
            writeString(output, result.normalizedText());
            output.writeInt(result.metadata().size());
            for (var entry : result.metadata().entrySet()) {
                writeString(output, entry.getKey());
                writeString(output, entry.getValue());
            }
        }
    }

    private static void writeFailure(Path path, ExtractionFailure failure, String message) throws IOException {
        try (var output = new DataOutputStream(Files.newOutputStream(path))) {
            output.writeBoolean(false);
            writeString(output, failure.name());
            writeString(output, message == null ? "document extraction failed" : message);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_RESPONSE_STRING_BYTES) {
            throw new IOException("invalid extraction string length");
        }
        return new String(readBytes(input, length), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(DataInputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("truncated extraction protocol payload");
        }
        return bytes;
    }

    private record Request(String filename, byte[] content) {
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
