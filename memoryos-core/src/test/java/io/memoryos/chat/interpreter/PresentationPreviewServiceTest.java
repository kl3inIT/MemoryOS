package io.memoryos.chat.interpreter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.memoryos.chat.ChatException;
import io.memoryos.sandbox.SandboxCommand;
import io.memoryos.sandbox.SandboxCommandResult;
import io.memoryos.sandbox.SandboxException;
import io.memoryos.sandbox.SandboxService;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** MEM-271: the deck converts in a sandbox of no conversation; the scratch sandbox is a recording fake. */
class PresentationPreviewServiceTest {
    private static final byte[] PDF = "%PDF-1.7 preview".getBytes(StandardCharsets.US_ASCII);
    private final InterpreterService files = mock(InterpreterService.class);
    private final SandboxService sandboxes = mock(SandboxService.class);
    private final PresentationPreviewService service = new PresentationPreviewService(files, sandboxes);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID id = UUID.randomUUID();
    private final ObjectKey deck = new ObjectKey("raw/deck");
    private final ObjectKey preview = new ObjectKey("raw/preview");
    /** What the scratch sandbox holds, and the commands it ran. */
    private final Map<String, byte[]> written = new HashMap<>();
    private final List<String> commands = new ArrayList<>();
    private int exit;
    private byte[] converted = PDF;

    @BeforeEach
    void available() {
        when(files.activeTenant(actor)).thenReturn(tenant);
        when(files.enabled(tenant)).thenReturn(true);
        when(files.open(actor, id)).thenAnswer(ignored -> new InterpreterService.Served(content(new byte[] {1}),
                "Báo cáo quý 3.pptx", InterpreterService.PPTX));
        when(files.openObject(preview)).thenAnswer(ignored -> content(PDF));
        when(sandboxes.scratch(eq(tenant), any())).thenAnswer(call ->
                call.<SandboxService.ScratchWork<?>>getArgument(1).apply(new SandboxService.Scratch() {
                    @Override public SandboxCommandResult run(SandboxCommand command) {
                        commands.add(command.command());
                        if (exit == 0) written.put(PresentationPreviewService.PDF, converted);
                        return new SandboxCommandResult(exit, "", "", false, Duration.ofSeconds(1));
                    }
                    @Override public void write(String path, InputStream content) {
                        try {
                            written.put(path, content.readAllBytes());
                        } catch (IOException failed) {
                            throw new UncheckedIOException(failed);
                        }
                    }
                    @Override public byte[] read(String path, long maxBytes) {
                        return written.get(path);
                    }
                }));
    }

    private InterpreterArtifact artifact(ObjectKey cached) {
        return new InterpreterArtifact(deck, "Báo cáo quý 3.pptx", InterpreterService.PPTX, cached);
    }

    @Test void aCachedPreviewIsServedWithoutASandbox() throws Exception {
        when(files.presentation(actor, id)).thenReturn(artifact(preview));

        var served = service.pdf(actor, id);

        assertEquals("Báo cáo quý 3.pdf", served.filename());
        assertEquals("application/pdf", served.mediaType());
        assertArrayEquals(PDF, served.content().inputStream().readAllBytes());
        verify(sandboxes, never()).scratch(any(), any());
    }

    @Test void theFirstPreviewConvertsTheDeckInAScratchSandboxAndStoresThePdf() throws Exception {
        when(files.presentation(actor, id)).thenReturn(artifact(null), artifact(preview));

        var served = service.pdf(actor, id);

        assertArrayEquals(PDF, served.content().inputStream().readAllBytes());
        assertArrayEquals(new byte[] {1}, written.get(PresentationPreviewService.DECK));
        assertEquals(List.of("pptx-to-pdf /workspace/deck.pptx /workspace/preview.pdf"), commands);
        verify(files).storePreview(tenant, id, PDF);
    }

    @Test void aFailedOrNonPdfConversionIsInvalid() {
        when(files.presentation(actor, id)).thenReturn(artifact(null));
        exit = 1;
        assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class, () -> service.pdf(actor, id)).code());

        exit = 0;
        converted = "<html>".getBytes(StandardCharsets.US_ASCII);
        assertEquals("CHAT_INVALID_REQUEST", assertThrows(ChatException.class, () -> service.pdf(actor, id)).code());
        verify(files, never()).storePreview(any(), any(), any());
    }

    @Test void aSwitchedOffTenantOrABusySandboxIsReportedAsSuch() {
        when(files.presentation(actor, id)).thenReturn(artifact(null));
        when(files.enabled(tenant)).thenReturn(false);
        assertEquals("CHAT_PROVIDER_UNAVAILABLE", assertThrows(ChatException.class, () -> service.pdf(actor, id)).code());

        when(files.enabled(tenant)).thenReturn(true);
        when(sandboxes.scratch(eq(tenant), any())).thenThrow(SandboxException.busy(2));
        assertEquals("CHAT_CAPACITY_EXCEEDED", assertThrows(ChatException.class, () -> service.pdf(actor, id)).code());
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
