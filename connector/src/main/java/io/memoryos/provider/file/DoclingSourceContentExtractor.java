package io.memoryos.provider.file;

import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.request.options.ConvertDocumentOptions;
import ai.docling.serve.api.convert.request.options.ImageRefMode;
import ai.docling.serve.api.convert.request.options.OcrEngine;
import ai.docling.serve.api.convert.request.options.OutputFormat;
import ai.docling.serve.api.convert.request.options.TableFormerMode;
import ai.docling.serve.api.convert.request.source.FileSource;
import ai.docling.serve.api.convert.request.target.InBodyTarget;
import ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.ingestion.SourceContentExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.tika.Tika;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class DoclingSourceContentExtractor implements SourceContentExtractor, AutoCloseable {
    private static final Map<String, String> FORMATS = Map.of(
            "application/pdf", ".pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx");
    private final DoclingProperties properties;
    private final DoclingServeApi client;
    private final ObjectMapper mapper;
    private final TikaSourceContentExtractor nativeReader = new TikaSourceContentExtractor();

    public DoclingSourceContentExtractor(DoclingProperties properties, ObjectMapper mapper) {
        this(properties, mapper, BoundedDoclingClient.create(properties));
    }

    DoclingSourceContentExtractor(DoclingProperties properties, ObjectMapper mapper, DoclingServeApi client) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
    }

    @Override public String processingProfile() { return properties.profile(); }

    @Override
    public DocumentContent extract(InputStream content, long sizeBytes, String filename) throws ExtractionException {
        if (sizeBytes < 1 || sizeBytes > 10_485_760) throw failure(ExtractionFailure.WRITE_LIMIT);
        byte[] bytes;
        try {
            bytes = content.readNBytes((int) sizeBytes + 1);
            if (bytes.length != sizeBytes) throw failure(ExtractionFailure.MALFORMED);
        } catch (IOException e) { throw failure(ExtractionFailure.INTERNAL); }
        String mediaType = new Tika().detect(bytes, filename);
        if ("application/pdf".equals(mediaType)) {
            // Admission only: content extraction remains exclusively in Docling.
            try (var pdf = org.apache.pdfbox.Loader.loadPDF(bytes)) {
                if (pdf.isEncrypted()) throw failure(ExtractionFailure.ENCRYPTED);
                if (pdf.getNumberOfPages() > properties.maxPages()) throw failure(ExtractionFailure.WRITE_LIMIT);
            } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
                throw failure(ExtractionFailure.ENCRYPTED);
            } catch (IOException e) { throw failure(ExtractionFailure.MALFORMED); }
        }
        if (!FORMATS.containsKey(mediaType)) {
            if (!Set.of("text/plain", "text/markdown", "text/x-markdown").contains(mediaType)) {
                throw failure(ExtractionFailure.UNSUPPORTED);
            }
            return nativeReader.extract(new java.io.ByteArrayInputStream(bytes), bytes.length, filename);
        }
        // FileSource sends bounded bytes, never an arbitrary URL or provider credential.
        var request = ConvertDocumentRequest.builder()
                .source(FileSource.builder().filename("document" + FORMATS.get(mediaType))
                        .base64String(Base64.getEncoder().encodeToString(bytes)).build())
                .options(ConvertDocumentOptions.builder().toFormat(OutputFormat.JSON).toFormat(OutputFormat.TEXT)
                        .doOcr(true).forceOcr(false).ocrEngine(OcrEngine.EASYOCR).ocrLang("vi").ocrLang("en")
                        .doTableStructure(true).tableMode(TableFormerMode.ACCURATE)
                        .includeImages(true).imageExportMode(ImageRefMode.EMBEDDED)
                        .documentTimeout(properties.timeout()).abortOnError(true).build())
                .target(InBodyTarget.builder().build()).build();
        try {
            var response = client.convertSource(request);
            if (!(response instanceof InBodyConvertDocumentResponse result)
                    || !"success".equals(result.getStatus()) || !result.getErrors().isEmpty()
                    || result.getDocument() == null || result.getDocument().getJsonContent() == null) {
                throw failure(ExtractionFailure.MALFORMED);
            }
            JsonNode document = mapper.valueToTree(result.getDocument().getJsonContent());
            if (document.path("pages").size() > properties.maxPages()) throw failure(ExtractionFailure.WRITE_LIMIT);
            ObjectNode canonical = mapper.createObjectNode();
            canonical.put("schema", "memoryos-extraction-v1");
            ArrayNode blocks = canonical.putArray("blocks");
            visit(document, document.path("body"), blocks, new HashSet<>(), 0);
            canonical.set("pages", document.path("pages"));
            String text = result.getDocument().getTextContent();
            if (text == null || text.isBlank() || blocks.isEmpty()) throw failure(ExtractionFailure.MALFORMED);
            if (text.length() > 2_000_000) throw failure(ExtractionFailure.WRITE_LIMIT);
            String json = mapper.writeValueAsString(canonical);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 33_554_432) {
                throw failure(ExtractionFailure.WRITE_LIMIT);
            }
            return new DocumentContent(mediaType, filename, text, Map.of("parser", "docling"),
                    json, processingProfile(), null);
        } catch (ExtractionException e) { throw e; }
        catch (RuntimeException e) {
            if (e.getCause() instanceof java.net.http.HttpTimeoutException) throw failure(ExtractionFailure.TIMEOUT);
            // Do not propagate SDK exception messages: they can contain response bodies.
            throw new IllegalStateException("Docling request failed");
        }
    }

    private void visit(JsonNode document, JsonNode node, ArrayNode blocks, Set<String> visited, int depth)
            throws ExtractionException {
        if (depth > 100 || blocks.size() > 100_000) throw failure(ExtractionFailure.WRITE_LIMIT);
        if (node.has("$ref")) {
            String ref = node.path("$ref").asString();
            if (!ref.startsWith("#/") || !visited.add(ref)) throw failure(ExtractionFailure.MALFORMED);
            node = document.at(ref.substring(1));
            if (node.isMissingNode()) throw failure(ExtractionFailure.MALFORMED);
        }
        String label = node.path("label").asString("");
        if (node.has("text") || node.has("data") || "picture".equals(label)) {
            ObjectNode block = blocks.addObject();
            block.put("index", blocks.size() - 1);
            block.put("kind", switch (label) {
                case "title", "section_header" -> "HEADING";
                case "table" -> "TABLE";
                case "picture" -> "IMAGE";
                case "list_item" -> "LIST_ITEM";
                default -> "PARAGRAPH";
            });
            block.put("text", node.path("text").asString(""));
            block.set("provenance", node.path("prov"));
            if (node.has("level")) block.set("headingLevel", node.get("level"));
            if (node.has("data")) block.set("table", node.get("data"));
            if (node.has("image")) block.set("image", node.get("image"));
        }
        for (JsonNode child : node.path("children")) visit(document, child, blocks, visited, depth + 1);
    }

    private static ExtractionException failure(ExtractionFailure failure) {
        return new ExtractionException(failure, "Document extraction failed: " + failure.name());
    }

    @Override public void close() {
        nativeReader.close();
        if (client instanceof BoundedDoclingClient bounded) bounded.close();
    }
}
