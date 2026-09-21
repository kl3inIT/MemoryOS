package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "ChatMessage")
public record ChatMessageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sessionId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid") @Nullable UUID parentMessageId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "uuid") @Nullable UUID latestChildMessageId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"USER", "ASSISTANT"}) String role,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"COMPLETED", "RUNNING", "CANCELED", "FAILED"}) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, format = "date-time") @Nullable Instant finishedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChatSourceResponse> sources,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<io.memoryos.chat.ChatFileDescriptor> files,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<io.memoryos.chat.ChatArtifact> artifacts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) io.memoryos.chat.ChatActivity activity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ImageRef> images,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<GeneratedFileRef> generatedFiles,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Research research,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"},
                description = "Why a FAILED reply ended, for example CHAT_MODEL_OUTPUT_LIMIT; null otherwise") @Nullable String failureCode) {

    /** A file run_python produced; bytes are served at /api/chat/file-artifacts/{id}/content. */
    public record GeneratedFileRef(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String filename,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mediaType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Chart data is available at /api/chat/file-artifacts/{id}/chart") boolean chart,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The file was deleted from the library; its content routes no longer serve it") boolean deleted) {}

    /** Deep research state: a clarification question makes the next research turn skip clarification. */
    @Schema(name = "ChatMessageResearch")
    public record Research(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean clarification,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String plan,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Agent> agents) {}

    /** One research agent call with its own steps; report citation numbers map to this message's sources. */
    @Schema(name = "ChatMessageResearchAgent")
    public record Agent(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String toolCallId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int cycle,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int tabIndex,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String task,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"RUNNING", "COMPLETED", "FAILED"}) String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"integer", "null"}, format = "int64") @Nullable Long durationMs,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String report,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Citation> citations,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) io.memoryos.chat.ChatActivity activity) {}

    @Schema(name = "ChatMessageResearchCitation")
    public record Citation(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int marker,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int citationId) {}

    /** A generated image attached to an assistant reply; bytes are served at /api/chat/image-artifacts/{id}/content. */
    public record ImageRef(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mediaType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) @Nullable String revisedPrompt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The image was deleted from the library; its content route no longer serves it") boolean deleted) {}
    public static ChatMessageResponse from(ChatMessage message) {
        return from(message, List.of(), List.of());
    }

    public static ChatMessageResponse from(ChatMessage message, List<ImageRef> images) {
        return from(message, images, List.of());
    }

    public static ChatMessageResponse from(ChatMessage message, List<ImageRef> images, List<GeneratedFileRef> generatedFiles) {
        return new ChatMessageResponse(message.id(), message.sessionId(), message.parentMessageId(),
                message.latestChildMessageId(), message.role().name(), message.content() == null ? "" : message.content(), message.status().name(),
                message.createdAt(), message.finishedAt(), message.sources().stream().map(ChatSourceResponse::from).toList(), message.files(), message.artifacts(), message.activity(), images, generatedFiles,
                new Research(message.research().clarification(), message.research().plan(), message.research().agents().stream()
                        .map(agent -> new Agent(agent.toolCallId(), agent.cycle(), agent.tabIndex(), agent.task(), agent.status().name(),
                                agent.durationMs(), agent.report(), agent.citations().stream()
                                .map(citation -> new Citation(citation.marker(), citation.citationId())).toList(), agent.activity())).toList()),
                message.status() == ChatMessage.Status.FAILED ? message.failureCode() : null);
    }
}
