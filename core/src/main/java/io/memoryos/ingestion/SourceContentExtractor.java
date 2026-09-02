package io.memoryos.ingestion;

import io.memoryos.document.DocumentContent;

public interface SourceContentExtractor {

    DocumentContent extract(byte[] content, String filename) throws ExtractionException;
}
