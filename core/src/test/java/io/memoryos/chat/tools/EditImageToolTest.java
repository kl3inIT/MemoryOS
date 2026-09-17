package io.memoryos.chat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.chat.ChatFileContentService;
import io.memoryos.chat.ChatImageEvent;
import io.memoryos.chat.image.ImageArtifactService;
import io.memoryos.chat.image.ImageConnectionService;
import io.memoryos.chat.image.ImageEditImages;
import io.memoryos.chat.image.ImageProvider;
import io.memoryos.chat.image.ImageProviderClient;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EditImageToolTest {
    private final ImageProviderClient client = mock(ImageProviderClient.class);
    private final ImageArtifactService artifacts = mock(ImageArtifactService.class);
    private final ChatFileContentService files = mock(ChatFileContentService.class);
    private final ActorId actor = new ActorId(UUID.randomUUID());
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final UUID session = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();
    private final List<ChatImageEvent> events = new ArrayList<>();
    private final ImageConnectionService.Connection connection = new ImageConnectionService.Connection(
            UUID.randomUUID(), tenant.value(), ImageProvider.CLOUDFLARE_WORKERS_AI, "https://api.example/accounts/a", "", "encrypted", 1);

    private EditImageTool tool(Set<UUID> attachments, Map<UUID, String> names, int maxCalls) {
        return new EditImageTool(client, connection, artifacts, files, actor, tenant, session, messageId, attachments, names,
                () -> {}, events::add, maxCalls);
    }

    private static byte[] png(int width, int height, Color left, Color right) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, (x < width / 2 ? left : right).getRGB());
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static ImageArtifactService.Image generated(byte[] bytes) {
        return new ImageArtifactService.Image(bytes, "image/png");
    }

    private List<ChatImageEvent.Stage> stages() {
        return events.stream().map(ChatImageEvent::stage).toList();
    }

    @Test void editsAGeneratedImageOfThisSessionAndRecordsItAsTheSource() throws Exception {
        var source = UUID.randomUUID();
        var stored = UUID.randomUUID();
        when(artifacts.sessionImage(actor, tenant, session, source)).thenReturn(Optional.of(generated(png(64, 48, Color.RED, Color.RED))));
        var edited = new ImageProviderClient.Result(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1}, "image/jpeg", null);
        when(client.edit(eq(connection), eq("make the shirt red"), any())).thenReturn(edited);
        when(artifacts.store(tenant, messageId, edited, source, null)).thenReturn(stored);

        var reply = tool(Set.of(), Map.of(), 4).editImage(source.toString(), "make the shirt red", null);

        assertTrue(reply.contains(stored.toString()));
        assertEquals(List.of(ChatImageEvent.Stage.GENERATING, ChatImageEvent.Stage.COMPLETED), stages());
        var working = ArgumentCaptor.forClass(ImageEditImages.Working.class);
        verify(client).edit(eq(connection), eq("make the shirt red"), working.capture());
        assertEquals(64, working.getValue().width());
        assertEquals(48, working.getValue().height());
        verify(files, never()).image(any(), any(), any());
    }

    @Test void editsAnAttachedImageWhenTheIdIsNotAGeneratedOne() throws Exception {
        var file = UUID.randomUUID();
        when(artifacts.sessionImage(actor, tenant, session, file)).thenReturn(Optional.empty());
        when(files.image(actor, tenant, file)).thenReturn(png(32, 32, Color.BLUE, Color.BLUE));
        var edited = new ImageProviderClient.Result(new byte[]{1}, "image/jpeg", null);
        when(client.edit(any(), any(), any())).thenReturn(edited);

        tool(Set.of(file), Map.of(), 4).editImage(file.toString(), "add a hat", null);

        verify(artifacts).store(tenant, messageId, edited, null, file);
    }

    @Test void idsOutsideTheConversationNeverReachTheProvider() throws Exception {
        when(artifacts.sessionImage(any(), any(), any(), any())).thenReturn(Optional.empty());
        var reply = tool(Set.of(), Map.of(), 4).editImage(UUID.randomUUID().toString(), "add a hat", null);
        assertTrue(reply.contains("not in this conversation"));
        var unattachedMask = tool(Set.of(), Map.of(), 4).editImage(UUID.randomUUID().toString(), "add a hat", UUID.randomUUID().toString());
        assertTrue(unattachedMask.contains("maskId"));
        assertTrue(events.isEmpty());
        verifyNoInteractions(client, files);
    }

    @Test void theMaskKeepsEveryPixelOutsideTheSelectionAndNamesTheImageToEdit() throws Exception {
        var source = UUID.randomUUID();
        var mask = UUID.randomUUID();
        when(artifacts.sessionImage(actor, tenant, session, source)).thenReturn(Optional.of(generated(png(32, 32, Color.RED, Color.RED))));
        when(files.image(actor, tenant, mask)).thenReturn(png(32, 32, Color.WHITE, Color.BLACK));
        when(client.edit(any(), any(), any())).thenReturn(new ImageProviderClient.Result(png(32, 32, Color.BLUE, Color.BLUE), "image/png", null));
        when(artifacts.store(eq(tenant), eq(messageId), any(), eq(source), isNull())).thenReturn(UUID.randomUUID());

        // The model guessed another id; the mask's name identifies the image it was painted on.
        tool(Set.of(mask), Map.of(mask, "mask-for-" + source + ".png"), 4)
                .editImage(UUID.randomUUID().toString(), "make the shirt blue", mask.toString());

        var result = ArgumentCaptor.forClass(ImageProviderClient.Result.class);
        verify(artifacts).store(eq(tenant), eq(messageId), result.capture(), eq(source), isNull());
        assertEquals("image/png", result.getValue().mediaType());
        var composed = ImageIO.read(new ByteArrayInputStream(result.getValue().bytes()));
        for (int y = 0; y < 32; y++) for (int x = 16; x < 32; x++) assertEquals(Color.RED.getRGB(), composed.getRGB(x, y));
        assertEquals(Color.BLUE.getRGB(), composed.getRGB(4, 16));
    }

    @Test void anEmptyMaskIsRejectedBeforeAnyProviderCall() throws Exception {
        var source = UUID.randomUUID();
        var mask = UUID.randomUUID();
        when(artifacts.sessionImage(actor, tenant, session, source)).thenReturn(Optional.of(generated(png(32, 32, Color.RED, Color.RED))));
        when(files.image(actor, tenant, mask)).thenReturn(png(32, 32, Color.BLACK, Color.BLACK));

        var reply = tool(Set.of(mask), Map.of(), 4).editImage(source.toString(), "make it blue", mask.toString());

        assertTrue(reply.contains("selects no area"));
        assertTrue(events.isEmpty());
        verifyNoInteractions(client);
    }

    @Test void providerFailureEmitsFailedWithoutDisclosingTheProviderError() throws Exception {
        var source = UUID.randomUUID();
        when(artifacts.sessionImage(actor, tenant, session, source)).thenReturn(Optional.of(generated(png(32, 32, Color.RED, Color.RED))));
        when(client.edit(any(), any(), any())).thenThrow(new IOException("secret-provider-diagnostic"));

        var reply = tool(Set.of(), Map.of(), 4).editImage(source.toString(), "make it blue", null);

        assertFalse(reply.contains("secret"));
        assertEquals(List.of(ChatImageEvent.Stage.GENERATING, ChatImageEvent.Stage.FAILED), stages());
    }

    @Test void perReplyCallCapIsEnforced() throws Exception {
        var source = UUID.randomUUID();
        when(artifacts.sessionImage(actor, tenant, session, source)).thenReturn(Optional.of(generated(png(32, 32, Color.RED, Color.RED))));
        when(client.edit(any(), any(), any())).thenReturn(new ImageProviderClient.Result(new byte[]{1}, "image/jpeg", null));
        when(artifacts.store(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        var tool = tool(Set.of(), Map.of(), 1);
        tool.editImage(source.toString(), "first", null);
        var second = tool.editImage(source.toString(), "second", null);
        assertTrue(second.toLowerCase().contains("limit"));
        verify(client, times(1)).edit(any(), any(), any());
    }
}
