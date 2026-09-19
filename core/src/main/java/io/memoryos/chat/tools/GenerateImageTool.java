package io.memoryos.chat.tools;

import com.embabel.agent.api.annotation.LlmTool;
import io.memoryos.chat.ChatImageEvent;
import io.memoryos.chat.image.ImageArtifactService;
import io.memoryos.chat.image.ImageConnectionService;
import io.memoryos.chat.image.ImageProviderClient;
import io.memoryos.iam.tenant.TenantId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** Per-turn image generation; provider errors and credentials never reach the model or UI. */
public final class GenerateImageTool {
    private static final Set<String> SHAPES = Set.of("square", "portrait", "landscape");
    private final ImageProviderClient client;
    private final ImageConnectionService.Connection connection;
    private final ImageArtifactService artifacts;
    private final TenantId tenant;
    private final UUID messageId;
    private final Runnable active;
    private final Consumer<ChatImageEvent> events;
    private final int maxCalls;
    private final io.memoryos.iam.identity.@Nullable ActorId actor;
    private int calls;

    public GenerateImageTool(ImageProviderClient client, ImageConnectionService.Connection connection, ImageArtifactService artifacts,
                             TenantId tenant, UUID messageId, Runnable active,
                             Consumer<ChatImageEvent> events, int maxCalls) {
        this(client, connection, artifacts, null, tenant, messageId, active, events, maxCalls);
    }

    /** @param actor the person whose reply requested the image, for AI usage */
    public GenerateImageTool(ImageProviderClient client, ImageConnectionService.Connection connection, ImageArtifactService artifacts,
                             io.memoryos.iam.identity.@Nullable ActorId actor, TenantId tenant, UUID messageId, Runnable active,
                             Consumer<ChatImageEvent> events, int maxCalls) {
        this.client = client; this.connection = connection; this.artifacts = artifacts; this.tenant = tenant;
        this.messageId = messageId; this.active = active; this.events = events; this.maxCalls = maxCalls; this.actor = actor;
    }

    @LlmTool(name = "generate_image", description = "Generate a new image from a text description and show it to the user in this reply. Use when the user asks to create, draw, paint, render, or illustrate a picture. Describe the desired image in detail, in English. Not for changing an existing image (use edit_image) and not for charts or diagrams. The generated image is shown to the user automatically; reply with a short confirmation and never output image data or a URL yourself.")
    public synchronized String generateImage(
            @LlmTool.Param(description = "A detailed English description of the image to generate, at most 4000 characters.") String prompt,
            @LlmTool.Param(description = "Optional image shape: square, portrait, or landscape. Omit for the provider default.") @Nullable String shape) {
        active.run();
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000)
            return "Provide a nonempty image description of at most 4000 characters.";
        if (shape != null && !shape.isBlank() && !SHAPES.contains(shape.trim().toLowerCase(Locale.ROOT)))
            return "Use shape square, portrait, or landscape, or omit it.";
        if (++calls > maxCalls) return "The image generation limit for this reply has been reached.";
        String toolCallId = "image-" + UUID.randomUUID();
        events.accept(new ChatImageEvent(toolCallId, ChatImageEvent.Stage.GENERATING, null, null, null));
        try {
            var result = client.generate(connection, prompt, shape);
            client.recordImage(connection, actor, false);
            active.run();
            var id = artifacts.store(tenant, messageId, result);
            events.accept(new ChatImageEvent(toolCallId, ChatImageEvent.Stage.COMPLETED, id, result.mediaType(), result.revisedPrompt()));
            return "Image generated and shown to the user (image_id=" + id + ")."
                    + (result.revisedPrompt() == null ? "" : " The prompt was refined to: " + result.revisedPrompt());
        } catch (Exception failed) {
            active.run(); // Cancellation must propagate, not become an ordinary tool result.
            events.accept(new ChatImageEvent(toolCallId, ChatImageEvent.Stage.FAILED, null, null, null));
            return "Image generation failed or is unavailable. Do not invent an image; explain the limitation to the user.";
        }
    }
}
