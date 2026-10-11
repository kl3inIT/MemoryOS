package io.memoryos.ingestion;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionException;
import io.memoryos.shared.TenantId;
import java.io.InputStream;

public interface SourceContentExtractor {

    /** @param tenant whose OCR reader reads a scan or an image */
    DocumentContent extract(TenantId tenant, InputStream content, long sizeBytes, String filename,
            SourceInputDescriptor input) throws ExtractionException;
}
