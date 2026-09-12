package io.memoryos.provider;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.provider.file.DoclingSourceContentExtractor;
import io.memoryos.provider.file.SpreadsheetSourceContentExtractor;
import io.memoryos.provider.google.GoogleDocsSourceContentExtractor;
import io.memoryos.provider.google.GoogleSheetsSourceContentExtractor;
import java.io.InputStream;
import java.util.Locale;
import org.apache.tika.Tika;
import tools.jackson.databind.ObjectMapper;

public final class SourceContentExtractorRouter implements SourceContentExtractor {
    private final DoclingSourceContentExtractor docling;
    private final SpreadsheetSourceContentExtractor spreadsheets;
    private final GoogleSheetsSourceContentExtractor sheets;
    private final GoogleDocsSourceContentExtractor docs;

    public SourceContentExtractorRouter(DoclingSourceContentExtractor docling, ObjectMapper mapper) {
        this.docling = docling;
        spreadsheets = new SpreadsheetSourceContentExtractor(mapper);
        sheets = new GoogleSheetsSourceContentExtractor(mapper);
        docs = new GoogleDocsSourceContentExtractor(mapper);
    }

    @Override public DocumentContent extract(InputStream content, long size, String filename,
                                              SourceInputDescriptor input) throws ExtractionException {
        return switch (input.format()) {
            case GOOGLE_SHEETS -> sheets.extract(content, size, filename, input);
            case GOOGLE_DOCS -> docs.extract(content, size, filename, input);
            case BINARY -> binary(content, size, filename, input);
        };
    }

    private DocumentContent binary(InputStream content, long size, String filename,
                                    SourceInputDescriptor input) throws ExtractionException {
        byte[] bytes = StructuredContent.read(content, size, Math.toIntExact(ObjectUploadSpecification.MAX_SIZE_BYTES));
        String mediaType = new Tika().detect(bytes, filename);
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
