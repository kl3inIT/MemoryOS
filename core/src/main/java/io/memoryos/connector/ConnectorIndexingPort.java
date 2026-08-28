package io.memoryos.connector;

import io.memoryos.document.DocumentId;

import java.util.List;
import java.util.Optional;

public interface ConnectorIndexingPort {

    List<IndexWork> claim(int batchSize);

    Optional<DocumentId> findMappedDocument(IndexWork work);

    boolean complete(IndexWork work, DocumentId documentId);

    void supersede(IndexWork work);

    boolean fail(IndexWork work, String errorCode);
}
