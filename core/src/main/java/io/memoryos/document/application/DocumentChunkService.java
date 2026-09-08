package io.memoryos.document.application;

import io.memoryos.document.DocumentChunkPort;
import io.memoryos.document.DocumentChunkSet;
import io.memoryos.document.DocumentId;
import io.memoryos.document.DocumentIndexState;
import io.memoryos.document.persistence.JdbcDocumentChunkRepository;
import io.memoryos.iam.TenantId;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DocumentChunkService implements DocumentChunkPort {
    private final JdbcDocumentChunkRepository repository;
    private final ObjectStorage storage;
    private final StructuredDocumentChunker chunker;

    public DocumentChunkService(JdbcDocumentChunkRepository repository, ObjectStorage storage, StructuredDocumentChunker chunker) {
        this.repository = repository; this.storage = storage; this.chunker = chunker;
    }

    @Override
    public Optional<DocumentChunkSet> prepare(TenantId tenant, DocumentId document, UUID generation) {
        var existing = repository.load(tenant, document, generation);
        if (existing.isPresent()) return existing;
        var lease = repository.openReader(tenant, document, generation);
        if (lease.isEmpty()) return Optional.empty();
        var reader = lease.orElseThrow();
        try {
            if (reader.size() < 1 || reader.size() > 33_554_432) throw new IllegalStateException("artifact exceeds limit");
            final byte[] bytes;
            try (var object = storage.open(new ObjectKey(reader.objectKey()))) {
                bytes = object.inputStream().readNBytes(Math.toIntExact(reader.size()) + 1);
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (bytes.length != reader.size() || !hash.equals(reader.hash())) throw new IllegalStateException("artifact integrity mismatch");
            var chunks = chunker.chunk(reader.title(), new String(bytes, StandardCharsets.UTF_8));
            return repository.publish(reader, chunks)
                    ? Optional.of(new DocumentChunkSet(tenant, document, generation, reader.title(), reader.mediaType(), reader.updatedAt(), chunks))
                    : Optional.empty();
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("cannot read extraction artifact");
        } finally {
            repository.closeReader(reader.readerId());
        }
    }

    @Override
    public boolean markSearchReady(TenantId tenant, DocumentId document, UUID generation, String identity) {
        return repository.markReady(tenant, document, generation, identity);
    }

    @Override
    public List<DocumentIndexState> scan(String identity, String after, int limit) { return repository.scan(identity, after, limit); }

    @Override
    public Map<UUID, UUID> currentGenerations(TenantId tenant, List<UUID> documents, String readyIdentity) {
        return repository.currentGenerations(tenant, documents, readyIdentity);
    }

    @Override
    public Optional<DocumentChunkSet> read(TenantId tenant, DocumentId document, UUID generation) {
        return repository.load(tenant, document, generation);
    }

    @Override
    public boolean isCurrent(TenantId tenant, DocumentId document, UUID generation, String identity) {
        return repository.isCurrent(tenant, document, generation, identity);
    }

    @Override
    public void markSearchPending(TenantId tenant, DocumentId document, UUID generation) {
        repository.searchState(tenant, document, generation, null);
    }

    @Override
    public void markSearchFailed(TenantId tenant, DocumentId document, UUID generation) {
        repository.searchState(tenant, document, generation, "SEARCH_INDEX_FAILED");
    }
}
