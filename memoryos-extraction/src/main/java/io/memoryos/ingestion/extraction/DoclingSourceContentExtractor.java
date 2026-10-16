package io.memoryos.ingestion.extraction;

import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.request.source.FileSource;
import ai.docling.serve.api.convert.request.target.InBodyTarget;
import ai.docling.serve.client.DoclingServeClientException;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractedDocument.Block;
import io.memoryos.document.ExtractedDocument.BoundingBox;
import io.memoryos.document.ExtractedDocument.Cell;
import io.memoryos.document.ExtractedDocument.CoordOrigin;
import io.memoryos.document.ExtractedDocument.Kind;
import io.memoryos.document.ExtractedDocument.Location;
import io.memoryos.document.ExtractedDocument.Page;
import io.memoryos.document.ExtractedDocument.Table;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.tika.Tika;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class DoclingSourceContentExtractor implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DoclingSourceContentExtractor.class);
    /** Detection holds no per-call state, so one facade serves every document. */
    private static final Tika TIKA = new Tika();
    private static final Map<String, String> FORMATS = Map.of(
            "application/pdf", ".pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", ".pptx");
    // Failures that belong to the service rather than to the document: Docling unreachable, slow,
    // or answering with something that is not a usable conversion. The native reader can stand in
    // for those. A failure that belongs to the document itself (encrypted, over a size or page
    // limit) is not in this set, because another reader would refuse the same document.
    private static final Set<ExtractionFailure> FALLBACK_ON = EnumSet.of(
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
    private final DoclingFallbackMetrics fallbacks;

    public DoclingSourceContentExtractor(DoclingProperties properties, ObjectMapper mapper, MeterRegistry registry) {
        this(properties, mapper, BoundedDoclingClient.create(properties), registry);
    }

    DoclingSourceContentExtractor(DoclingProperties properties, ObjectMapper mapper, DoclingServeApi client,
            MeterRegistry registry) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
        this.fallbacks = new DoclingFallbackMetrics(registry, FALLBACK_ON);
    }

    public static boolean usesDocling(String mediaType) { return FORMATS.containsKey(mediaType); }

    /**
     * @param ocr the Tenant's OCR reader. With one, scans and the images it reads are its, and Docling reads text
     *            layers only; without one, Docling keeps its own OCR (an organization without an OCR service)
     */
    DocumentContent extract(InputStream content, long sizeBytes, String filename,
            SourceInputDescriptor input, @Nullable OcrReader ocr) throws ExtractionException {
        if (sizeBytes < 1 || sizeBytes > ObjectUploadSpecification.MAX_SIZE_BYTES) throw failure(ExtractionFailure.WRITE_LIMIT);
        byte[] bytes;
        try {
            bytes = content.readNBytes((int) sizeBytes + 1);
            if (bytes.length != sizeBytes) throw failure(ExtractionFailure.MALFORMED);
        } catch (IOException e) { throw failure(ExtractionFailure.INTERNAL); }
        return extract(bytes, filename, TIKA.detect(bytes, filename), input, ocr);
    }

    DocumentContent extract(byte[] bytes, String filename, String mediaType,
            SourceInputDescriptor input, @Nullable OcrReader ocr) throws ExtractionException {
        if (bytes.length < 1 || bytes.length > ObjectUploadSpecification.MAX_SIZE_BYTES) throw failure(ExtractionFailure.WRITE_LIMIT);
        PdfLayout layout = null;
        if ("application/pdf".equals(mediaType)) {
            try (var pdf = Loader.loadPDF(bytes)) {
                layout = admit(pdf, ocr != null);
            } catch (InvalidPasswordException e) {
                throw failure(ExtractionFailure.ENCRYPTED);
            } catch (IOException e) { throw failure(ExtractionFailure.MALFORMED); }
        }
        // A scan or an image is the OCR reader's, and its failure stands: no Docling OCR, no native read.
        if (ocr != null && (layout != null && layout.scanned() || ocr.readsImage(mediaType))) {
            return ocr.extract(PaddleOcrVlClient.Input.of(bytes), layout, filename, mediaType,
                    ObjectUploadSpecification.MAX_SIZE_BYTES);
        }
        int pages = layout == null ? 0 : layout.pages();
        if (!usesDocling(mediaType)) {
            if (!Set.of("text/plain", "text/markdown", "text/x-markdown").contains(mediaType)) {
                throw failure(ExtractionFailure.UNSUPPORTED);
            }
            return nativeReader.extract(new ByteArrayInputStream(bytes), bytes.length, filename, input);
        }
        if (!(client instanceof BoundedDoclingClient bounded)) throw failure(ExtractionFailure.UNSUPPORTED);
        // FileSource sends bounded bytes, never an arbitrary URL or provider credential.
        var request = ConvertDocumentRequest.builder()
                .source(FileSource.builder().filename("document" + FORMATS.get(mediaType))
                        .base64String(Base64.getEncoder().encodeToString(bytes)).build())
                .options(properties.options(ocr == null))
                .target(InBodyTarget.builder().build()).build();
        ExtractionException serviceFailure;
        try {
            var result = bounded.convertDocument(request);
            return canonical(result, filename, mediaType, ObjectUploadSpecification.MAX_SIZE_BYTES, ocr == null);
        } catch (ExtractionException e) {
            serviceFailure = e;
        } catch (RuntimeException e) {
            serviceFailure = requestFailure(e);
        }
        return fallBack(serviceFailure, mediaType, pages,
                () -> nativeReader.extract(new ByteArrayInputStream(bytes), bytes.length, filename, input));
    }

    /**
     * Admission and routing in one PDFBox pass: encryption and the page limit, each page's size, and
     * whether the text layer is thin enough to call the document a scan. Density is measured only
     * when an OCR reader is there to read a scan; it is the same threshold a native fallback must meet.
     */
    private PdfLayout admit(PDDocument pdf, boolean measureDensity) throws ExtractionException, IOException {
        if (pdf.isEncrypted()) throw failure(ExtractionFailure.ENCRYPTED);
        if (pdf.getNumberOfPages() > properties.maxPages()) throw failure(ExtractionFailure.WRITE_LIMIT);
        return PdfLayout.of(pdf, measureDensity);
    }

    /** Disk-backed multipart prevents a 250 MiB file becoming several base64/JSON heap copies. */
    DocumentContent extractChatFile(Path file, String filename, String mediaType, @Nullable OcrReader ocr)
            throws ExtractionException {
        if (!(client instanceof BoundedDoclingClient bounded) || !usesDocling(mediaType)) throw failure(ExtractionFailure.UNSUPPORTED);
        PdfLayout layout = null;
        try {
            long size = Files.size(file);
            if (size < 1 || size > 262_144_000) throw failure(ExtractionFailure.WRITE_LIMIT);
            if ("application/pdf".equals(mediaType)) {
                try (var pdf = Loader.loadPDF(file.toFile())) {
                    layout = admit(pdf, ocr != null);
                }
            }
        } catch (InvalidPasswordException encrypted) { throw failure(ExtractionFailure.ENCRYPTED); }
        catch (IOException invalid) { throw failure(ExtractionFailure.MALFORMED); }
        if (ocr != null && layout != null && layout.scanned()) {
            return ocr.extract(PaddleOcrVlClient.Input.of(file), layout, filename, mediaType, 262_144_000);
        }
        int pages = layout == null ? 0 : layout.pages();
        ExtractionException serviceFailure;
        try {
            var result = bounded.convertFile(file, FORMATS.get(mediaType), properties, ocr == null);
            return canonical(result, filename, mediaType, 262_144_000, ocr == null);
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
     * <p>The shape follows Onyx, whose better parser is tried first and whose ordinary readers take
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
            fallbacks.record(docling.failure(), DoclingFallbackMetrics.Outcome.FAILED);
            LOG.atWarn().addKeyValue("event", "extraction.fallback.failed")
                    .addKeyValue("docling_failure", docling.failure().name())
                    .addKeyValue("native_failure", nativeFailure.failure().name())
                    .log("Native reader could not stand in for Docling");
            throw docling;
        }
        long characters = content.normalizedText().codePoints().filter(c -> !Character.isWhitespace(c)).count();
        long required = "application/pdf".equals(mediaType) ? (long) MIN_FALLBACK_CHARACTERS_PER_PDF_PAGE * Math.max(1, pages) : 1;
        if (characters < required) {
            fallbacks.record(docling.failure(), DoclingFallbackMetrics.Outcome.REFUSED);
            LOG.atWarn().addKeyValue("event", "extraction.fallback.refused")
                    .addKeyValue("docling_failure", docling.failure().name())
                    .addKeyValue("characters", characters).addKeyValue("required", required)
                    .log("Native text too thin to stand in for Docling; the document needs OCR");
            throw docling;
        }
        var metadata = new HashMap<>(content.metadata());
        metadata.put("parser", "tika");
        metadata.put("fallback_from", "docling");
        metadata.put("fallback_reason", docling.failure().name());
        fallbacks.record(docling.failure(), DoclingFallbackMetrics.Outcome.READ);
        LOG.atInfo().addKeyValue("event", "extraction.fell_back")
                .addKeyValue("docling_failure", docling.failure().name())
                .addKeyValue("characters", characters)
                .log("Docling unavailable; document read natively without OCR or table structure");
        return new DocumentContent(mediaType, content.title(), content.normalizedText(), metadata,
                content.structuredJson(), null);
    }

    private DocumentContent canonical(BoundedDoclingClient.CanonicalResponse result, String filename, String mediaType,
            long maxInput, boolean doclingOcr) throws ExtractionException {
            if (result == null || !"success".equals(result.status()) || result.errors() == null
                    || !result.errors().isEmpty() || result.document() == null
                    || !result.document().path("json_content").isObject()) throw failure(ExtractionFailure.MALFORMED);
            JsonNode document = result.document().path("json_content");
            if (document.path("pages").size() > properties.maxPages()) throw failure(ExtractionFailure.WRITE_LIMIT);
            var blocks = new ArrayList<Block>();
            visit(document, document.path("body"), blocks, new HashSet<>(), 0);
            JsonNode orientation = document.path("body").path("meta").path("memoryos__orientation");
            return DocumentAssembly.publish(mapper, blocks, pages(document.path("pages")),
                    orientation.isObject() ? orientation : null, mediaType, filename,
                    Map.of("parser", "docling", "parser_configuration", properties.parserConfiguration(maxInput, doclingOcr)),
                    "docling");
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
                if (root instanceof HttpConnectTimeoutException
                        || root instanceof ConnectException || root instanceof UnknownHostException) {
                    reason = ExtractionFailure.CONNECTION_FAILED;
                    externalFailure = true;
                    break;
                }
                if (root instanceof HttpTimeoutException || root instanceof InterruptedException) {
                    reason = ExtractionFailure.TIMEOUT;
                    externalFailure = true;
                    break;
                }
            }
            if (httpStatus == 413) reason = ExtractionFailure.WRITE_LIMIT;
            else if (httpStatus == 408 || httpStatus == 504) reason = ExtractionFailure.TIMEOUT;
            var cause = e.getCause();
            LOG.atWarn().addKeyValue("event", "docling.extraction.failed")
                    .addKeyValue("error_code", reason.name()).addKeyValue("http_status", httpStatus)
                    .addKeyValue("error_type", e.getClass().getName())
                    .addKeyValue("cause_type", cause == null ? "none" : cause.getClass().getName())
                    .log("Docling extraction failed");
            // Expected external/ambiguous requests terminate; retry must not submit duplicate remote work.
            if (externalFailure) return failure(reason);
            throw new IllegalStateException("Docling request failed");
    }

    private void visit(JsonNode document, JsonNode node, List<Block> blocks, Set<String> visited, int depth)
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
            Kind kind = switch (label) {
                case "title", "section_header" -> Kind.HEADING;
                case "table" -> Kind.TABLE;
                case "picture" -> Kind.IMAGE;
                case "list_item" -> Kind.LIST_ITEM;
                default -> Kind.PARAGRAPH;
            };
            blocks.add(new Block(blocks.size(), kind, node.path("text").asString(""),
                    node.has("level") ? node.path("level").asInt() : null, locations(node.path("prov")),
                    node.has("data") ? table(node.path("data")) : null, null, node.has("image") ? node.get("image") : null));
        }
        for (JsonNode child : node.path("children")) visit(document, child, blocks, visited, depth + 1);
    }

    /** Docling `prov` items keep their page and box; `charspan` and other Docling keys are dropped. */
    private static List<Location> locations(JsonNode prov) {
        var locations = new ArrayList<Location>(prov.size());
        for (JsonNode item : prov) {
            JsonNode page = item.path("page_no");
            if (!page.isIntegralNumber() || !page.canConvertToInt() || page.asInt() < 1) continue;
            JsonNode bbox = item.path("bbox");
            boolean located = bbox.path("l").isNumber() && bbox.path("t").isNumber()
                    && bbox.path("r").isNumber() && bbox.path("b").isNumber();
            locations.add(Location.page(page.asInt(), !located ? null : new BoundingBox(bbox.path("l").asDouble(),
                    bbox.path("t").asDouble(), bbox.path("r").asDouble(), bbox.path("b").asDouble(),
                    "TOPLEFT".equals(bbox.path("coord_origin").asString("")) ? CoordOrigin.TOPLEFT : CoordOrigin.BOTTOMLEFT)));
        }
        return locations;
    }

    /** Docling's end offsets are exclusive, so a span is the end minus the start. */
    private static Table table(JsonNode data) {
        var cells = new ArrayList<Cell>(data.path("table_cells").size());
        for (JsonNode cell : data.path("table_cells")) {
            int row = cell.path("start_row_offset_idx").asInt(-1);
            int column = cell.path("start_col_offset_idx").asInt(-1);
            cells.add(new Cell(row, column, cell.path("end_row_offset_idx").asInt(row + 1) - row,
                    cell.path("end_col_offset_idx").asInt(column + 1) - column,
                    cell.path("column_header").asBoolean(false), cell.path("row_header").asBoolean(false),
                    cell.path("text").asString(""), List.of()));
        }
        JsonNode rows = data.path("num_rows");
        JsonNode columns = data.path("num_cols");
        return new Table(rows.canConvertToInt() && rows.isIntegralNumber() ? rows.asInt() : null,
                columns.canConvertToInt() && columns.isIntegralNumber() ? columns.asInt() : null, cells);
    }

    /** Docling keys pages by number; each keeps its number and size in PDF points. */
    private static List<Page> pages(JsonNode pages) {
        var result = new ArrayList<Page>(pages.size());
        for (JsonNode page : pages) {
            JsonNode size = page.path("size");
            if (!page.path("page_no").isIntegralNumber() || !size.path("width").isNumber() || !size.path("height").isNumber()) continue;
            result.add(new Page(page.path("page_no").asInt(), size.path("width").asDouble(), size.path("height").asDouble()));
        }
        result.sort(Comparator.comparingInt(Page::pageNo));
        return result;
    }

    private static ExtractionException failure(ExtractionFailure failure) {
        return new ExtractionException(failure, "Document extraction failed: " + failure.name());
    }

    @Override public void close() {
        nativeReader.close();
        if (client instanceof BoundedDoclingClient bounded) bounded.close();
    }
}
