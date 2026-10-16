package io.memoryos.ingestion;

import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionException;
import io.memoryos.shared.TenantId;
import java.io.InputStream;

/** Chat's independently bounded binary input, not Source FILE's 10 MiB contract. */
public interface ChatFileExtractor {
    /** @param tenant whose OCR reader reads a scan or an image */
    DocumentContent extract(TenantId tenant, InputStream stream, long size, String filename) throws ExtractionException;
}
