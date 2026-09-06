package io.memoryos.document.application;

import io.memoryos.document.DocumentContent;
import io.memoryos.document.ExtractionArtifactPort;
import io.memoryos.document.persistence.JdbcExtractionArtifactRepository;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class DefaultExtractionArtifactService implements ExtractionArtifactPort {
    private final JdbcExtractionArtifactRepository repository;
    private final ObjectStorage storage;
    private final ObjectMapper mapper;

    public DefaultExtractionArtifactService(JdbcExtractionArtifactRepository repository,
            ObjectStorage storage, ObjectMapper mapper) {
        this.repository = repository;
        this.storage = storage;
        this.mapper = mapper;
    }

    @Override
    public DocumentContent stage(TenantId tenantId, DocumentContent content) {
        String json = content.structuredJson().isEmpty()
                ? mapper.writeValueAsString(Map.of("schema", "memoryos-extraction-v1", "blocks",
                    java.util.List.of(Map.of("kind", "PARAGRAPH", "text", content.normalizedText(), "index", 0))))
                : content.structuredJson();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > 33_554_432) throw new IllegalArgumentException("artifact exceeds limit");
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        UUID id = UUID.randomUUID();
        ObjectKey key = new ObjectKey("extracted/" + tenantId.value() + "/" + id + "/document.json");
        repository.stage(tenantId, id, key.value(), hash, bytes.length);
        storage.write(key, bytes, "application/json");
        var actual = storage.inspect(key);
        if (actual.sizeBytes() != bytes.length || !actual.checksum().value().equals(hash)) {
            throw new IllegalStateException("artifact integrity mismatch");
        }
        repository.finishWrite(tenantId, id);
        return content.withArtifact(id);
    }

    @Override
    public int cleanup() {
        int count = 0;
        for (var artifact : repository.claimCleanup()) {
            try {
                storage.delete(new ObjectKey(artifact.key()));
                repository.remove(artifact);
                count++;
            } catch (RuntimeException ignored) {
                // The durable deletion claim becomes eligible again after its lease.
            }
        }
        return count;
    }
}
