package io.memoryos.ingestion.extraction;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.adapter.googledrive.GoogleDocsSourceContentExtractor;
import io.memoryos.connector.adapter.googledrive.GoogleSheetsSourceContentExtractor;
import io.memoryos.connector.adapter.sharepoint.SharePointPageSourceContentExtractor;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.StructuredContent;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import java.io.InputStream;
import java.util.Locale;
import org.apache.tika.Tika;
import tools.jackson.databind.ObjectMapper;

public final class SourceContentExtractorRouter implements SourceContentExtractor {
    /** Detection holds no per-call state, so one facade serves every document. */
    private static final Tika TIKA = new Tika();
    private final DoclingSourceContentExtractor docling;
    private final SpreadsheetSourceContentExtractor spreadsheets;
    private final GoogleSheetsSourceContentExtractor sheets;
    private final GoogleDocsSourceContentExtractor docs;
    private final SharePointPageSourceContentExtractor sharePointPages;

    public SourceContentExtractorRouter(DoclingSourceContentExtractor docling, ObjectMapper mapper) {
        this.docling = docling;
        spreadsheets = new SpreadsheetSourceContentExtractor(mapper);
        sheets = new GoogleSheetsSourceContentExtractor(mapper);
        docs = new GoogleDocsSourceContentExtractor(mapper);
        sharePointPages = new SharePointPageSourceContentExtractor(mapper);
    }

    @Override public DocumentContent extract(InputStream content, long size, String filename,
                                              SourceInputDescriptor input) throws ExtractionException {
        return switch (input.format()) {
            case GOOGLE_SHEETS -> sheets.extract(content, size, filename, input);
            case GOOGLE_DOCS -> docs.extract(content, size, filename, input);
            case SHAREPOINT_PAGE -> sharePointPages.extract(content, size, filename, input);
            case BINARY -> binary(content, size, filename, input);
        };
    }

    private DocumentContent binary(InputStream content, long size, String filename,
                                    SourceInputDescriptor input) throws ExtractionException {
        byte[] bytes = StructuredContent.read(content, size, Math.toIntExact(ObjectUploadSpecification.MAX_SIZE_BYTES));
        String mediaType = TIKA.detect(bytes, filename);
        if (DoclingSourceContentExtractor.usesDocling(mediaType)) {
            return docling.extract(bytes, filename, mediaType, input);
        }
        String name = filename.toLowerCase(Locale.ROOT);
        if (SpreadsheetSourceContentExtractor.XLSX.equals(mediaType) || name.endsWith(".xlsx")) {
            return spreadsheets.extract(bytes, filename, SpreadsheetSourceContentExtractor.XLSX, input);
        }
        if ("text/csv".equals(mediaType) || name.endsWith(".csv")) {
            return spreadsheets.extract(bytes, filename, "text/csv", input);
        }
        return docling.extract(bytes, filename, mediaType, input);
    }
}
