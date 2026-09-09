package io.memoryos.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.document.application.DocumentChunkService;
import io.memoryos.document.application.StructuredDocumentChunker;
import io.memoryos.document.persistence.JdbcDocumentChunkRepository;
import io.memoryos.iam.TenantId;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DocumentChunkServiceTest {
    private final JdbcDocumentChunkRepository repository = mock(JdbcDocumentChunkRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final DocumentChunkService service = new DocumentChunkService(
            repository, storage, new StructuredDocumentChunker(new ObjectMapper()));
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final DocumentId document = new DocumentId(UUID.randomUUID());
    private final UUID generation = UUID.randomUUID();

    @Test
    void rejectsOversizedArtifactBeforeOpeningObjectAndReleasesReader() {
        var reader = reader("0".repeat(64), 33_554_433L);
        when(repository.load(tenant, document, generation)).thenReturn(Optional.empty());
        when(repository.openReader(tenant, document, generation)).thenReturn(Optional.of(reader));

        var failure = assertThrows(IllegalStateException.class,
                () -> service.prepare(tenant, document, generation));

        assertEquals("artifact exceeds limit", failure.getMessage());
        verify(storage, never()).open(any());
        verify(repository).closeReader(reader.readerId());
    }

    @Test
    void rejectsChecksumMismatchAndReleasesObjectAndReader() {
        byte[] bytes = "{\"schema\":\"memoryos-extraction-v1\",\"blocks\":[]}".getBytes(StandardCharsets.UTF_8);
        var reader = reader("0".repeat(64), bytes.length);
        var object = mock(ObjectContent.class);
        when(object.inputStream()).thenReturn(new ByteArrayInputStream(bytes));
        when(storage.open(any())).thenReturn(object);
        when(repository.load(tenant, document, generation)).thenReturn(Optional.empty());
        when(repository.openReader(tenant, document, generation)).thenReturn(Optional.of(reader));

        var failure = assertThrows(IllegalStateException.class,
                () -> service.prepare(tenant, document, generation));

        assertEquals("artifact integrity mismatch", failure.getMessage());
        verify(object).close();
        verify(repository).closeReader(reader.readerId());
        verify(repository, never()).publish(any(), any());
    }

    private JdbcDocumentChunkRepository.ArtifactReader reader(String hash, long size) {
        return new JdbcDocumentChunkRepository.ArtifactReader(tenant, document, generation, UUID.randomUUID(),
                UUID.randomUUID(), "extracted/test/document.json", hash, size, "Tài liệu", "application/json",
                Instant.parse("2026-09-09T00:00:00Z"));
    }
}
