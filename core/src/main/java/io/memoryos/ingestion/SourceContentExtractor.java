package io.memoryos.ingestion;

import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.document.DocumentContent;
import java.io.InputStream;

public interface SourceContentExtractor {

    DocumentContent extract(InputStream content, long sizeBytes, String filename,
            SourceInputDescriptor input) throws ExtractionException;
}
