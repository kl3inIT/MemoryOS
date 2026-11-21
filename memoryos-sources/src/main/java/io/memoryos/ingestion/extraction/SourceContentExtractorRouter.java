package io.memoryos.ingestion.extraction;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.connector.SourceSnapshotReader;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.StructuredContent;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.shared.TenantId;
import java.io.InputStream;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.apache.tika.Tika;
import tools.jackson.databind.ObjectMapper;

public final class SourceContentExtractorRouter implements SourceContentExtractor {
    /** Detection holds no per-call state, so one facade serves every document. */
    private static final Tika TIKA = new Tika();
    private final DoclingSourceContentExtractor docling;
    private final Function<TenantId, @Nullable OcrReader> ocr;
    private final SpreadsheetSourceContentExtractor spreadsheets;
    private final Map<SourceInputFormat, SourceSnapshotReader> snapshots = new EnumMap<>(SourceInputFormat.class);

    /**
     * @param ocr the Tenant's OCR reader, null when Docling reads its scans
     * @param snapshotReaders the readers the provider integrations contribute, one a format
     */
    SourceContentExtractorRouter(DoclingSourceContentExtractor docling, Function<TenantId, @Nullable OcrReader> ocr,
            ObjectMapper mapper, Collection<SourceSnapshotReader> snapshotReaders) {
        this.docling = docling;
        this.ocr = ocr;
        spreadsheets = new SpreadsheetSourceContentExtractor(mapper);
        for (SourceSnapshotReader reader : snapshotReaders) {
            if (reader.format() == SourceInputFormat.BINARY || snapshots.put(reader.format(), reader) != null)
                throw new IllegalStateException("Exactly one snapshot reader may read " + reader.format());
        }
    }

    /** Fails when a provider format has no reader, so a deployment missing an integration does not start. */
    SourceContentExtractorRouter requireEveryFormat() {
        for (SourceInputFormat format : SourceInputFormat.values())
            if (format != SourceInputFormat.BINARY && !snapshots.containsKey(format))
                throw new IllegalStateException("No snapshot reader for " + format);
        return this;
    }

    @Override public DocumentContent extract(TenantId tenant, InputStream content, long size, String filename,
                                              SourceInputDescriptor input) throws ExtractionException {
        if (input.format() == SourceInputFormat.BINARY) return binary(tenant, content, size, filename, input);
        SourceSnapshotReader reader = snapshots.get(input.format());
        if (reader == null) throw new IllegalStateException("No snapshot reader for " + input.format());
        return reader.extract(content, size, filename, input);
    }

    private DocumentContent binary(TenantId tenant, InputStream content, long size, String filename,
                                    SourceInputDescriptor input) throws ExtractionException {
        byte[] bytes = StructuredContent.read(content, size, Math.toIntExact(ObjectUploadSpecification.MAX_SIZE_BYTES));
        String mediaType = TIKA.detect(bytes, filename);
        if (DoclingSourceContentExtractor.usesDocling(mediaType)) {
            return docling.extract(bytes, filename, mediaType, input, ocr.apply(tenant));
        }
        String name = filename.toLowerCase(Locale.ROOT);
        if (SpreadsheetSourceContentExtractor.XLSX.equals(mediaType) || name.endsWith(".xlsx")) {
            return spreadsheets.extract(bytes, filename, SpreadsheetSourceContentExtractor.XLSX, input);
        }
        if ("text/csv".equals(mediaType) || name.endsWith(".csv")) {
            return spreadsheets.extract(bytes, filename, "text/csv", input);
        }
        return docling.extract(bytes, filename, mediaType, input, ocr.apply(tenant));
    }
}
