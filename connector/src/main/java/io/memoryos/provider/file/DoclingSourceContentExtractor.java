package io.memoryos.provider.file;

import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.request.source.FileSource;
import ai.docling.serve.api.convert.request.target.InBodyTarget;
import ai.docling.serve.client.DoclingServeClientException;
import io.memoryos.document.DocumentContent;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.tika.Tika;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class DoclingSourceContentExtractor implements AutoCloseable {
    private static final int MAX_TEXT_CHARACTERS = 2_000_000;
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DoclingSourceContentExtractor.class);
    private static final Map<String, String> FORMATS = Map.of(
            "application/pdf", ".pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx");
    // Failures that belong to the service rather than to the document: Docling unreachable, slow,
    // or answering with something that is not a usable conversion. The native reader can stand in
    // for those. A failure that belongs to the document itself (encrypted, over a size or page
    // limit) is not in this set, because another reader would refuse the same document.
    private static final Set<ExtractionFailure> FALLBACK_ON = java.util.EnumSet.of(
            ExtractionFailure.CONNECTION_FAILED, ExtractionFailure.TIMEOUT,
            ExtractionFailure.INTERNAL, ExtractionFailure.MALFORMED);
    // A PDF with a text layer carries hundreds of characters on a page. A scan carries none, or a
    // signature on one page: the Tasco financial reports have text on 6 of 260 pages. Below this
    // density the native reader has read the scan's margins, not the document, and indexing that
    // would publish a document that answers no question while reporting success.
    static final int MIN_FALLBACK_CHARACTERS_PER_PDF_PAGE = 100;
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

    public static boolean usesDocling(String mediaType) { return FORMATS.containsKey(mediaType); }

    public DocumentContent extract(InputStream content, long sizeBytes, String filename,
            SourceInputDescriptor input) throws ExtractionException {
        if (sizeBytes < 1 || sizeBytes > ObjectUploadSpecification.MAX_SIZE_BYTES) throw failure(ExtractionFailure.WRITE_LIMIT);
        byte[] bytes;
        try {
            bytes = content.readNBytes((int) sizeBytes + 1);
            if (bytes.length != sizeBytes) throw failure(ExtractionFailure.MALFORMED);
        } catch (IOException e) { throw failure(ExtractionFailure.INTERNAL); }
        return extract(bytes, filename, new Tika().detect(bytes, filename), input);
    }

    public DocumentContent extract(byte[] bytes, String filename, String mediaType,
            SourceInputDescriptor input) throws ExtractionException {
        if (bytes.length < 1 || bytes.length > ObjectUploadSpecification.MAX_SIZE_BYTES) throw failure(ExtractionFailure.WRITE_LIMIT);
        int pages = 0;
        if ("application/pdf".equals(mediaType)) {
            // Admission only; the page count also sets how much text a native fallback must find.
            try (var pdf = org.apache.pdfbox.Loader.loadPDF(bytes)) {
                if (pdf.isEncrypted()) throw failure(ExtractionFailure.ENCRYPTED);
                pages = pdf.getNumberOfPages();
                if (pages > properties.maxPages()) throw failure(ExtractionFailure.WRITE_LIMIT);
            } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
                throw failure(ExtractionFailure.ENCRYPTED);
            } catch (IOException e) { throw failure(ExtractionFailure.MALFORMED); }
        }
        if (!usesDocling(mediaType)) {
            if (!Set.of("text/plain", "text/markdown", "text/x-markdown").contains(mediaType)) {
                throw failure(ExtractionFailure.UNSUPPORTED);
            }
            return nativeReader.extract(new java.io.ByteArrayInputStream(bytes), bytes.length, filename, input);
        }
        if (!(client instanceof BoundedDoclingClient bounded)) throw failure(ExtractionFailure.UNSUPPORTED);
        // FileSource sends bounded bytes, never an arbitrary URL or provider credential.
        var request = ConvertDocumentRequest.builder()
                .source(FileSource.builder().filename("document" + FORMATS.get(mediaType))
                        .base64String(Base64.getEncoder().encodeToString(bytes)).build())
                .options(properties.options())
                .target(InBodyTarget.builder().build()).build();
        ExtractionException serviceFailure;
        try {
            var result = bounded.convertDocument(request);
            return canonical(result, filename, mediaType, ObjectUploadSpecification.MAX_SIZE_BYTES);
        } catch (ExtractionException e) {
            serviceFailure = e;
        } catch (RuntimeException e) {
            serviceFailure = requestFailure(e);
        }
        return fallBack(serviceFailure, mediaType, pages,
                () -> nativeReader.extract(new java.io.ByteArrayInputStream(bytes), bytes.length, filename, input));
    }

    /** Disk-backed multipart prevents a 250 MiB file becoming several base64/JSON heap copies. */
    public DocumentContent extractChatFile(java.nio.file.Path file, String filename, String mediaType) throws ExtractionException {
        if (!(client instanceof BoundedDoclingClient bounded) || !usesDocling(mediaType)) throw failure(ExtractionFailure.UNSUPPORTED);
        int pages = 0;
        try {
            long size = java.nio.file.Files.size(file);
            if (size < 1 || size > 262_144_000) throw failure(ExtractionFailure.WRITE_LIMIT);
            if ("application/pdf".equals(mediaType)) {
                try (var pdf = org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
                    if (pdf.isEncrypted()) throw failure(ExtractionFailure.ENCRYPTED);
                    pages = pdf.getNumberOfPages();
                    if (pages > properties.maxPages()) throw failure(ExtractionFailure.WRITE_LIMIT);
                }
            }
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException encrypted) { throw failure(ExtractionFailure.ENCRYPTED); }
        catch (IOException invalid) { throw failure(ExtractionFailure.MALFORMED); }
        ExtractionException serviceFailure;
        try {
            var result = bounded.convertFile(file, FORMATS.get(mediaType), properties);
            return canonical(result, filename, mediaType, 262_144_000);
        } catch (ExtractionException e) {
            serviceFailure = e;
        } catch (IOException transport) {
            serviceFailure = failure(ExtractionFailure.MALFORMED);
        } catch (RuntimeException e) {
            serviceFailure = requestFailure(e);
        }
        return fallBack(serviceFailure, mediaType, pages, () -> nativeReader.extractChatFile(file, filename, mediaType));
    }

    @FunctionalInterface
    private interface NativeRead { DocumentContent read() throws ExtractionException; }

    /**
     * Reads the document natively when Docling failed for a reason of its own, and says so.
     *
     * The shape follows Onyx, whose better parser is tried first and whose ordinary readers take
     * over on any failure. Two things differ, both because silence is the actual risk: a native
     * result that is empty or scan-thin is refused and the original failure stands, and the
     * document records which reader produced it and why, so it can be found and extracted again
     * once Docling is back. Any refusal rethrows Docling's own failure, so a caller sees exactly
     * what it saw before this path existed.
     */
    private DocumentContent fallBack(ExtractionException docling, String mediaType, int pages, NativeRead nativeRead)
            throws ExtractionException {
        if (!FALLBACK_ON.contains(docling.failure())) throw docling;
        DocumentContent content;
        try {
            content = nativeRead.read();
        } catch (ExtractionException nativeFailure) {
            LOG.atWarn().addKeyValue("event", "extraction.fallback.failed")
                    .addKeyValue("docling_failure", docling.failure().name())
                    .addKeyValue("native_failure", nativeFailure.failure().name())
                    .log("Native reader could not stand in for Docling");
            throw docling;
        }
        long characters = content.normalizedText().codePoints().filter(c -> !Character.isWhitespace(c)).count();
        long required = "application/pdf".equals(mediaType) ? (long) MIN_FALLBACK_CHARACTERS_PER_PDF_PAGE * Math.max(1, pages) : 1;
        if (characters < required) {
            LOG.atWarn().addKeyValue("event", "extraction.fallback.refused")
                    .addKeyValue("docling_failure", docling.failure().name())
                    .addKeyValue("characters", characters).addKeyValue("required", required)
                    .log("Native text too thin to stand in for Docling; the document needs OCR");
            throw docling;
        }
        var metadata = new java.util.HashMap<>(content.metadata());
        metadata.put("parser", "tika");
        metadata.put("fallback_from", "docling");
        metadata.put("fallback_reason", docling.failure().name());
        LOG.atInfo().addKeyValue("event", "extraction.fell_back")
                .addKeyValue("docling_failure", docling.failure().name())
                .addKeyValue("characters", characters)
                .log("Docling unavailable; document read natively without OCR or table structure");
        return new DocumentContent(mediaType, content.title(), content.normalizedText(), metadata,
                content.structuredJson(), null);
    }

    private DocumentContent canonical(BoundedDoclingClient.CanonicalResponse result, String filename, String mediaType, long maxInput) throws ExtractionException {
            if (result == null || !"success".equals(result.status()) || result.errors() == null
                    || !result.errors().isEmpty() || result.document() == null
                    || !result.document().path("json_content").isObject()) throw failure(ExtractionFailure.MALFORMED);
            JsonNode document = result.document().path("json_content");
            if (document.path("pages").size() > properties.maxPages()) throw failure(ExtractionFailure.WRITE_LIMIT);
            ObjectNode canonical = mapper.createObjectNode();
            canonical.put("schema", "memoryos-extraction-v1");
            ArrayNode blocks = canonical.putArray("blocks");
            visit(document, document.path("body"), blocks, new HashSet<>(), 0);
            canonical.set("pages", document.path("pages"));
            JsonNode orientation = document.path("body").path("meta").path("memoryos__orientation");
            if (orientation.isObject()) canonical.set("page_orientation", orientation);
            String text = semanticText(blocks);
            if (text.isBlank()) throw failure(ExtractionFailure.MALFORMED);
            var financialChecks = FinancialTableDiagnostics.assess(blocks, mapper);
            canonical.set("financial_checks", financialChecks);
            if (!financialChecks.isEmpty()) {
                int reviewChecks = 0;
                for (var check : financialChecks) {
                    if (!"CONSISTENT".equals(check.path("status").asString())) reviewChecks++;
                }
                LOG.atInfo().addKeyValue("event", "docling.financial_checks.completed")
                        .addKeyValue("check_count", financialChecks.size()).addKeyValue("review_count", reviewChecks)
                        .log("Scoped financial checks completed; source values unchanged");
            }
            String json = mapper.writeValueAsString(canonical);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 33_554_432) {
                throw failure(ExtractionFailure.WRITE_LIMIT);
            }
            return new DocumentContent(mediaType, filename, text,
                    Map.of("parser", "docling", "parser_configuration", properties.parserConfiguration(maxInput)), json, null);
    }

    private static ExtractionException requestFailure(RuntimeException e) {
            ExtractionFailure reason = ExtractionFailure.INTERNAL;
            boolean externalFailure = false;
            int httpStatus = -1;
            Throwable root = e;
            for (int depth = 0; root != null && depth < 16; depth++, root = root.getCause()) {
                if (root instanceof BoundedDoclingClient.ResponseFailure response) {
                    reason = response.failure;
                    externalFailure = true;
                    break;
                }
                if (root instanceof DoclingServeClientException sdk) {
                    externalFailure = true;
                    if (sdk.getStatusCode() > 0) httpStatus = sdk.getStatusCode();
                }
                if (root instanceof java.net.http.HttpConnectTimeoutException
                        || root instanceof java.net.ConnectException || root instanceof java.net.UnknownHostException) {
                    reason = ExtractionFailure.CONNECTION_FAILED;
                    externalFailure = true;
                    break;
                }
                if (root instanceof java.net.http.HttpTimeoutException || root instanceof InterruptedException) {
                    reason = ExtractionFailure.TIMEOUT;
                    externalFailure = true;
                    break;
                }
            }
            if (httpStatus == 413) reason = ExtractionFailure.WRITE_LIMIT;
            else if (httpStatus == 408 || httpStatus == 504) reason = ExtractionFailure.TIMEOUT;
            LOG.atWarn().addKeyValue("event", "docling.extraction.failed")
                    .addKeyValue("error_code", reason.name()).addKeyValue("http_status", httpStatus)
                    .addKeyValue("error_type", e.getClass().getName())
                    .addKeyValue("cause_type", e.getCause() == null ? "none" : e.getCause().getClass().getName())
                    .log("Docling extraction failed");
            // Expected external/ambiguous requests terminate; retry must not submit duplicate remote work.
            if (externalFailure) return failure(reason);
            throw new IllegalStateException("Docling request failed");
    }

    private String semanticText(ArrayNode blocks) throws ExtractionException {
        var text = new StringBuilder();
        for (JsonNode block : blocks) {
            String kind = block.path("kind").asString("");
            if ("IMAGE".equals(kind)) continue;
            if ("TABLE".equals(kind) && block.path("table").path("table_cells").isArray()) {
                appendTableText(text, block.path("table").path("table_cells"));
            } else {
                String value = block.path("text").asString("").strip();
                if (!value.isEmpty()) appendText(text, text.isEmpty() ? "" : "\n\n", value);
            }
        }
        return text.toString();
    }

    private void appendTableText(StringBuilder text, JsonNode tableCells) throws ExtractionException {
        if (tableCells.size() > 100_000) throw failure(ExtractionFailure.WRITE_LIMIT);
        var cells = new ArrayList<JsonNode>(tableCells.size());
        tableCells.forEach(cells::add);
        cells.sort(Comparator.comparingInt((JsonNode cell) -> cell.path("start_row_offset_idx").asInt())
                .thenComparingInt(cell -> cell.path("start_col_offset_idx").asInt()));
        int previousRow = -1;
        int previousColumn = 0;
        for (JsonNode cell : cells) {
            int row = cell.path("start_row_offset_idx").asInt(-1);
            int column = cell.path("start_col_offset_idx").asInt(-1);
            if (row < 0 || column < 0 || (row == previousRow && column <= previousColumn)) {
                throw failure(ExtractionFailure.MALFORMED);
            }
            if (previousRow < 0 && !text.isEmpty()) appendText(text, "\n\n", "");
            if (row != previousRow) {
                appendTablePadding(text, '\n', previousRow < 0 ? row : row - previousRow);
                previousColumn = 0;
            }
            appendTablePadding(text, '\t', column - previousColumn);
            appendText(text, "", cell.path("text").asString("").strip());
            previousRow = row;
            previousColumn = column;
        }
    }

    private void appendTablePadding(StringBuilder text, char separator, int count) throws ExtractionException {
        if ((long) text.length() + count > MAX_TEXT_CHARACTERS) throw failure(ExtractionFailure.WRITE_LIMIT);
        text.repeat(separator, count);
    }

    private void appendText(StringBuilder text, String separator, String value) throws ExtractionException {
        if ((long) text.length() + separator.length() + value.length() > MAX_TEXT_CHARACTERS) {
            throw failure(ExtractionFailure.WRITE_LIMIT);
        }
        text.append(separator).append(value);
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
