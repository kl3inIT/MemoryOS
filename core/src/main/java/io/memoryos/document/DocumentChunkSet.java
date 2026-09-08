package io.memoryos.document;

import io.memoryos.iam.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentChunkSet(TenantId tenantId, DocumentId documentId, UUID generation,
        String title, String mediaType, Instant updatedAt, List<DocumentChunk> chunks) {
    public DocumentChunkSet { chunks = List.copyOf(chunks); }

    public String chunkId(int ordinal) {
        return tenantId.value() + ":" + documentId.value() + ":" + generation + ":" + ordinal;
    }
}
