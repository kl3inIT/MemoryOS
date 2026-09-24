package io.memoryos.chat.interpreter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.interpreter.persistence.JdbcInterpreterRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InterpreterServiceSpreadsheetTest {
    private final JdbcInterpreterRepository repository = mock(JdbcInterpreterRepository.class);
    private final TenantAccessResolver tenants = mock(TenantAccessResolver.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final InterpreterService service = new InterpreterService(repository, null, null, tenants, null,
            storage, null, null, null, io.memoryos.TestDatabase.noAudit());
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID id = UUID.randomUUID();

    @Test void onlyAnOwnedXlsxHasASpreadsheetPreviewAndTheObjectIsAlwaysClosed() {
        when(tenants.findActiveTenant(actor)).thenReturn(Optional.of(tenant));
        var key = new ObjectKey("raw/chart");
        when(repository.ownedArtifact(tenant, actor, id)).thenReturn(Optional.of(
                new InterpreterArtifact(key, "chart.png", "image/png")));
        var closed = new AtomicBoolean();
        when(storage.open(key)).thenReturn(content(closed));

        var invalid = assertThrows(ChatException.class, () -> service.spreadsheet(actor, id));
        assertEquals(ChatException.invalid("x").code(), invalid.code());
        assertTrue(closed.get());

        // Another owner's file never reaches storage.
        when(repository.ownedArtifact(tenant, actor, id)).thenReturn(Optional.empty());
        assertThrows(ChatException.class, () -> service.spreadsheet(actor, id));
        verify(storage, org.mockito.Mockito.times(1)).open(any());
    }

    private static ObjectContent content(AtomicBoolean closed) {
        var input = new ByteArrayInputStream(new byte[] {1});
        return new ObjectContent() {
            @Override public ObjectMetadata metadata() { return new ObjectMetadata(1, "image/png", new ContentSha256("a".repeat(64))); }
            @Override public InputStream inputStream() { return input; }
            @Override public void close() { closed.set(true); }
        };
    }
}
