package io.memoryos.ingestion;

import io.memoryos.document.DocumentContent;
import java.io.InputStream;

public interface SourceContentExtractor {

    default String processingProfile() {
        return "legacy-tika-v1";
    }

    DocumentContent extract(InputStream content, long sizeBytes, String filename) throws ExtractionException;
}
