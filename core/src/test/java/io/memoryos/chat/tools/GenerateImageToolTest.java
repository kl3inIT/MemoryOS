package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.chat.ChatImageEvent;
import io.memoryos.chat.image.ImageArtifactService;
import io.memoryos.chat.image.ImageConnectionService;
import io.memoryos.chat.image.ImageProvider;
import io.memoryos.chat.image.ImageProviderClient;
import io.memoryos.iam.tenant.TenantId;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GenerateImageToolTest {
    private final ImageProviderClient client = mock(ImageProviderClient.class);
    private final ImageArtifactService artifacts = mock(ImageArtifactService.class);
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID messageId = UUID.randomUUID();
    private final List<ChatImageEvent> events = new ArrayList<>();
    private final ImageConnectionService.Connection connection = new ImageConnectionService.Connection(
            UUID.randomUUID(), tenant.value(), ImageProvider.OPENAI_IMAGE, "", "gpt-image-1", "encrypted", 1);

    private GenerateImageTool tool(int maxCalls) {
        return new GenerateImageTool(client, connection, artifacts, tenant, messageId, () -> {},
                events::add, maxCalls);
    }

    @Test void successEmitsGeneratingThenCompletedAndReturnsArtifactId() throws Exception {
        var artifactId = UUID.randomUUID();
        when(client.generate(connection, "a red bicycle", "portrait"))
                .thenReturn(new ImageProviderClient.Result(new byte[]{1, 2, 3}, "image/png", "a red racing bicycle at sunset"));
        when(artifacts.store(eq(tenant), eq(messageId), any())).thenReturn(artifactId);

        var reply = tool(4).generateImage("a red bicycle", "portrait");

        assertTrue(reply.contains(artifactId.toString()));
        assertEquals(2, events.size());
        assertEquals(ChatImageEvent.Stage.GENERATING, events.get(0).stage());
        assertEquals(ChatImageEvent.Stage.COMPLETED, events.get(1).stage());
        assertEquals(artifactId, events.get(1).artifactId());
        assertEquals("image/png", events.get(1).mediaType());
        assertEquals("a red racing bicycle at sunset", events.get(1).revisedPrompt());
    }

    @Test void providerFailureEmitsFailedWithoutDisclosingProviderError() throws Exception {
        when(client.generate(any(), any(), any())).thenThrow(new IOException("secret-provider-diagnostic"));
        var reply = tool(4).generateImage("a cat", null);
        assertFalse(reply.contains("secret"));
        assertEquals(ChatImageEvent.Stage.FAILED, events.get(events.size() - 1).stage());
    }

    @Test void blankPromptRejectedBeforeAnyGeneration() {
        var reply = tool(4).generateImage("   ", null);
        assertTrue(reply.toLowerCase().contains("description"));
        assertTrue(events.isEmpty());
        verifyNoInteractions(client);
    }

    @Test void unknownShapeRejectedBeforeAnyGeneration() {
        var reply = tool(4).generateImage("a cat", "panorama");
        assertTrue(reply.contains("square, portrait, or landscape"));
        assertTrue(events.isEmpty());
        verifyNoInteractions(client);
    }

    @Test void perTurnCallCapIsEnforced() throws Exception {
        when(client.generate(any(), any(), any())).thenReturn(new ImageProviderClient.Result(new byte[]{1}, "image/png", null));
        when(artifacts.store(any(), any(), any())).thenReturn(UUID.randomUUID());
        var tool = tool(1);
        tool.generateImage("first", null);
        var second = tool.generateImage("second", null);
        assertTrue(second.toLowerCase().contains("limit"));
        verify(client, times(1)).generate(any(), any(), any());
    }

    @Test
    void onlyADeliveredImageIsAddedToAiUsage() throws Exception {
        when(client.generate(any(), any(), any())).thenReturn(new ImageProviderClient.Result(new byte[]{1}, "image/png", null));
        tool(4).generateImage("a lighthouse", null);
        verify(client).recordImage(any(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(false));
        when(client.generate(any(), any(), any())).thenThrow(new IOException("unavailable"));
        tool(4).generateImage("a lighthouse", null);
        verify(client, times(1)).recordImage(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
