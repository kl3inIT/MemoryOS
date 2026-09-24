package io.memoryos.chat.interpreter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.interpreter.persistence.JdbcInterpreterRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PresentationPreviewServiceTest {
    private static final byte[] PDF = "%PDF-1.7 preview".getBytes(StandardCharsets.US_ASCII);
    private final InterpreterService files = mock(InterpreterService.class);
    private final InterpreterClient client = mock(InterpreterClient.class);
    private final PresentationPreviewService service = new PresentationPreviewService(files, client);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID id = UUID.randomUUID();
    private final ObjectKey deck = new ObjectKey("raw/deck");
    private final ObjectKey preview = new ObjectKey("raw/preview");

    @BeforeEach
    void available() throws Exception {
        when(files.activeTenant(actor)).thenReturn(tenant);
        when(files.enabled(tenant)).thenReturn(true);
        when(client.configured()).thenReturn(true);
        when(client.healthy()).thenReturn(true);
        when(files.open(actor, id)).thenAnswer(ignored -> new InterpreterService.Served(content(new byte[] {1}),
                "Báo cáo quý 3.pptx", InterpreterService.PPTX));
        when(files.openObject(preview)).thenAnswer(ignored -> content(PDF));
        when(client.upload(eq("deck.pptx"), eq(InterpreterService.PPTX), any())).thenReturn("svc-deck");
    }

    private JdbcInterpreterRepository.Artifact artifact(ObjectKey cached) {
        return new JdbcInterpreterRepository.Artifact(deck, "Báo cáo quý 3.pptx", InterpreterService.PPTX, cached);
    }

    @Test void aCachedPreviewIsServedWithoutTheInterpreter() throws Exception {
        when(files.presentation(actor, id)).thenReturn(artifact(preview));

        var served = service.pdf(actor, id);

        assertEquals("Báo cáo quý 3.pdf", served.filename());
        assertEquals("application/pdf", served.mediaType());
        assertArrayEquals(PDF, served.content().inputStream().readAllBytes());
        verify(client, never()).upload(anyString(), anyString(), any());
    }

    @Test void theFirstPreviewConvertsInTheExecutorStoresThePdfAndDeletesServiceCopies() throws Exception {
        when(files.presentation(actor, id)).thenReturn(artifact(null), artifact(preview));
        when(client.execute(eq(PresentationPreviewService.CODE), eq(PresentationPreviewService.TIMEOUT_MS),
                eq(List.of(new InterpreterClient.StagedFile("deck.pptx", "svc-deck")))))
                .thenReturn(new InterpreterClient.Execution("{\"converted\": true, \"pages\": 12}", "", 0, false, List.of(
                        new InterpreterClient.WorkspaceFile("deck.pptx", "file", "svc-deck"),
                        new InterpreterClient.WorkspaceFile("preview.pdf", "file", "svc-pdf"))));
        when(client.download("svc-pdf")).thenReturn(PDF);

        var served = service.pdf(actor, id);

        assertArrayEquals(PDF, served.content().inputStream().readAllBytes());
        verify(files).storePreview(tenant, id, PDF);
        verify(client).delete("svc-deck");
        verify(client).delete("svc-pdf");
    }

    @Test void aFailedOrNonPdfConversionIsInvalidAndStillCleansUp() throws Exception {
        when(files.presentation(actor, id)).thenReturn(artifact(null));
        when(client.execute(anyString(), anyInt(), anyList())).thenReturn(new InterpreterClient.Execution(
                "{\"converted\": false, \"error\": \"LibreOffice could not open the presentation\"}", "", 1, false,
                List.of(new InterpreterClient.WorkspaceFile("deck.pptx", "file", "svc-deck"))));
        assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class, () -> service.pdf(actor, id)).code());
        verify(client).delete("svc-deck");

        when(client.execute(anyString(), anyInt(), anyList())).thenReturn(new InterpreterClient.Execution("", "", 0, false,
                List.of(new InterpreterClient.WorkspaceFile("preview.pdf", "file", "svc-html"))));
        when(client.download("svc-html")).thenReturn("<html>".getBytes(StandardCharsets.US_ASCII));
        assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class, () -> service.pdf(actor, id)).code());
        verify(client).delete("svc-html");
        verify(files, never()).storePreview(any(), any(), any());
    }

    @Test void anUnavailableOrBusyInterpreterIsReportedAsSuch() throws Exception {
        when(files.presentation(actor, id)).thenReturn(artifact(null));
        when(files.enabled(tenant)).thenReturn(false);
        assertEquals("CHAT_PROVIDER_UNAVAILABLE", assertThrows(ChatException.class, () -> service.pdf(actor, id)).code());

        when(files.enabled(tenant)).thenReturn(true);
        when(client.execute(anyString(), anyInt(), anyList())).thenThrow(new InterpreterClient.BusyException("busy"));
        assertEquals("CHAT_CAPACITY_EXCEEDED", assertThrows(ChatException.class, () -> service.pdf(actor, id)).code());
        verify(client).delete("svc-deck");
    }

    private static ObjectContent content(byte[] bytes) {
        InputStream input = new ByteArrayInputStream(bytes);
        return new ObjectContent() {
            @Override public ObjectMetadata metadata() {
                return new ObjectMetadata(bytes.length, "application/octet-stream", new ContentSha256("a".repeat(64)));
            }
            @Override public InputStream inputStream() { return input; }
            @Override public void close() {}
        };
    }
}
