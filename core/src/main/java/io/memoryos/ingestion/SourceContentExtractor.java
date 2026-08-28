package io.memoryos.ingestion;

public interface SourceContentExtractor {

    ExtractionResult extract(byte[] content, String filename) throws ExtractionException;
}
