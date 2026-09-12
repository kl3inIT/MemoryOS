package io.memoryos.ingestion;

import io.memoryos.document.DocumentContent;
import java.io.InputStream;

/** Chat's independently bounded binary input, not Source FILE's 10 MiB contract. */
public interface ChatFileExtractor {
    DocumentContent extract(InputStream stream, long size, String filename) throws ExtractionException;
}
