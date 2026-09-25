package io.memoryos.chat.tools;

import com.embabel.agent.api.annotation.LlmTool;
import io.memoryos.chat.ChatException;
import io.memoryos.library.UserFileContentService;
import io.memoryos.chat.ChatImageEvent;
import io.memoryos.chat.image.ImageArtifactService;
import io.memoryos.chat.image.ImageConnectionService;
import io.memoryos.chat.image.ImageEditImages;
import io.memoryos.chat.image.ImageProviderClient;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Per-turn image editing; provider errors and credentials never reach the model or UI. A source is an image
 * generated in this session or an image attached in this turn's context. A mask narrows the change: the
 * provider edits the whole image and only the selected area is taken from its result.
 */
public final class EditImageTool {
    private static final Pattern MASK_NAME = Pattern.compile(
            "mask-for-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.png", Pattern.CASE_INSENSITIVE);
    private final ImageProviderClient client;
    private final ImageConnectionService.Connection connection;
    private final ImageArtifactService artifacts;
    private final UserFileContentService files;
    private final ActorId actor;
    private final TenantId tenant;
    private final UUID sessionId;
    private final UUID messageId;
    private final Set<UUID> attachments;
    private final Map<UUID, String> filenames;
    private final Runnable active;
    private final Consumer<ChatImageEvent> events;
    private final int maxCalls;
    private int calls;

    public EditImageTool(ImageProviderClient client, ImageConnectionService.Connection connection, ImageArtifactService artifacts,
                         UserFileContentService files, ActorId actor, TenantId tenant, UUID sessionId, UUID messageId,
                         Set<UUID> attachments, Map<UUID, String> filenames, Runnable active,
                         Consumer<ChatImageEvent> events, int maxCalls) {
        this.client = client; this.connection = connection; this.artifacts = artifacts; this.files = files;
        this.actor = actor; this.tenant = tenant; this.sessionId = sessionId; this.messageId = messageId;
        this.attachments = Set.copyOf(attachments); this.filenames = Map.copyOf(filenames);
        this.active = active; this.events = events; this.maxCalls = maxCalls;
    }

    private record Source(byte[] bytes, boolean generated) {}

    @LlmTool(name = "edit_image", description = "Change an image that is already in this conversation and show the new version to the user in this reply: an attached image, or an image shown in an earlier answer. Use when the user asks to recolor, restyle, fix, remove, add or otherwise modify that picture while keeping the rest. Not for creating an unrelated new image. The edited image is shown to the user automatically; reply with a short confirmation and never output image data or a URL yourself.")
    public synchronized String editImage(
            @LlmTool.Param(description = "The image to change: the file id of an attached image, or the image_id of an image shown in an earlier answer.") String imageId,
            @LlmTool.Param(description = "An English instruction stating the change and that everything else stays exactly the same, at most 4000 characters.") String prompt,
            @LlmTool.Param(description = "Optional file id of an attached mask named mask-for-<image_id>.png; white marks the area that may change. Omit when the user attached no mask.") @Nullable String maskId) {
        active.run();
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000)
            return "Provide a nonempty English instruction of at most 4000 characters.";
        boolean masked = maskId != null && !maskId.isBlank();
        UUID mask = masked ? parse(maskId) : null;
        if (masked && (mask == null || !attachments.contains(mask)))
            return "maskId must be the file id of a mask attached in this conversation.";
        UUID target = mask == null ? null : maskTarget(mask); // A mask names its image; that beats a guessed id.
        if (target == null) target = parse(imageId);
        if (target == null) return "Provide the imageId of an image in this conversation.";
        Source source;
        ImageEditImages.Working working;
        ImageEditImages.Mask selection = null;
        try {
            source = source(target);
            if (source == null)
                return "That image is not in this conversation. Use the file id of an attached image or an image_id listed for an earlier answer.";
            working = ImageEditImages.prepare(source.bytes());
            if (mask != null) {
                selection = ImageEditImages.mask(files.image(actor, tenant, mask), working.width(), working.height());
                if (selection.coverage() == 0) return "The mask selects no area. Ask the user to paint the part of the image to change.";
            }
        } catch (IOException | ChatException unusable) {
            active.run();
            return "That image cannot be edited. Only PNG or JPEG images that are ready in this conversation can be edited.";
        }
        if (++calls > maxCalls) return "The image editing limit for this reply has been reached.";
        String toolCallId = "image-" + UUID.randomUUID();
        events.accept(new ChatImageEvent(toolCallId, ChatImageEvent.Stage.GENERATING, null, null, null));
        try {
            active.run();
            var edited = client.edit(connection, prompt, working);
            client.recordImage(connection, actor, true);
            active.run();
            var result = selection == null || selection.coverage() >= ImageEditImages.FULL_COVERAGE ? edited
                    : new ImageProviderClient.Result(ImageEditImages.composite(working, edited.bytes(), selection), "image/png", edited.revisedPrompt());
            var id = artifacts.store(tenant, messageId, result, source.generated() ? target : null, source.generated() ? null : target);
            events.accept(new ChatImageEvent(toolCallId, ChatImageEvent.Stage.COMPLETED, id, result.mediaType(), result.revisedPrompt()));
            return "Edited image shown to the user (image_id=" + id + ")."
                    + (result.revisedPrompt() == null ? "" : " The instruction was refined to: " + result.revisedPrompt());
        } catch (io.memoryos.chat.ChatException refused) {
            active.run();
            if (!"CHAT_STORAGE_FULL".equals(refused.code())) throw refused;
            events.accept(new ChatImageEvent(toolCallId, ChatImageEvent.Stage.FAILED, null, null, null));
            return "The image was generated but not kept: the user's file library is full."
                    + " Tell the user to free space in their library and try again.";
        } catch (Exception failed) {
            active.run(); // Cancellation must propagate, not become an ordinary tool result.
            events.accept(new ChatImageEvent(toolCallId, ChatImageEvent.Stage.FAILED, null, null, null));
            return "Image editing failed or is unavailable. Do not invent an image; explain the limitation to the user.";
        }
    }

    /** A generated image of this session first, then an image attached in this turn's context. */
    private @Nullable Source source(UUID id) {
        var generated = artifacts.sessionImage(actor, tenant, sessionId, id);
        if (generated.isPresent()) return new Source(generated.get().bytes(), true);
        return attachments.contains(id) ? new Source(files.image(actor, tenant, id), false) : null;
    }

    private @Nullable UUID maskTarget(UUID mask) {
        String name = filenames.get(mask);
        if (name == null) return null;
        var matcher = MASK_NAME.matcher(name);
        return matcher.matches() ? parse(matcher.group(1)) : null;
    }

    private static @Nullable UUID parse(@Nullable String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
