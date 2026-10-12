package io.memoryos.ingestion.extraction;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.adapter.googledrive.GoogleDocsSourceContentExtractor;
import io.memoryos.connector.adapter.googledrive.GoogleSheetsSourceContentExtractor;
import io.memoryos.connector.adapter.lark.LarkDocxSourceContentExtractor;
import io.memoryos.connector.adapter.lark.LarkSheetSourceContentExtractor;
import io.memoryos.connector.adapter.sharepoint.SharePointPageSourceContentExtractor;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.StructuredContent;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.shared.TenantId;
import java.io.InputStream;
import java.util.Locale;
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
    private final GoogleSheetsSourceContentExtractor sheets;
    private final GoogleDocsSourceContentExtractor docs;
    private final SharePointPageSourceContentExtractor sharePointPages;
    private final LarkDocxSourceContentExtractor larkDocuments;
    private final LarkSheetSourceContentExtractor larkSheets;

    /** @param ocr the Tenant's OCR reader, null when Docling reads its scans */
    SourceContentExtractorRouter(DoclingSourceContentExtractor docling, Function<TenantId, @Nullable OcrReader> ocr,
            ObjectMapper mapper) {
        this.docling = docling;
        this.ocr = ocr;
        spreadsheets = new SpreadsheetSourceContentExtractor(mapper);
        sheets = new GoogleSheetsSourceContentExtractor(mapper);
        docs = new GoogleDocsSourceContentExtractor(mapper);
        sharePointPages = new SharePointPageSourceContentExtractor(mapper);
        larkDocuments = new LarkDocxSourceContentExtractor(mapper);
        larkSheets = new LarkSheetSourceContentExtractor(mapper);
    }

    @Override public DocumentContent extract(TenantId tenant, InputStream content, long size, String filename,
                                              SourceInputDescriptor input) throws ExtractionException {
        return switch (input.format()) {
            case GOOGLE_SHEETS -> sheets.extract(content, size, filename, input);
            case GOOGLE_DOCS -> docs.extract(content, size, filename, input);
            case SHAREPOINT_PAGE -> sharePointPages.extract(content, size, filename, input);
            case LARK_DOCX -> larkDocuments.extract(content, size, filename, input);
            case LARK_SHEET -> larkSheets.extract(content, size, filename, input);
            case BINARY -> binary(tenant, content, size, filename, input);
        };
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
