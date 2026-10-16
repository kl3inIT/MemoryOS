package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatPersonaService.PersonaInput;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An agent's settings as the editor sends them on create and update. The token limit ranges are the service's own,
 * declared here so a violation is reported on its field; the service keeps checking them.
 */
@Schema(name = "PersonaInput")
public record ChatPersonaRequest(
        String name, String description, String instructions, @Nullable String taskPrompt,
        List<String> starterPrompts, List<UUID> sourceIds, @Nullable List<UUID> documentSetIds,
        @Nullable Set<String> tools, @Nullable List<UUID> mcpServerIds,
        @Schema(types = {"string", "null"}, format = "uuid") @Nullable UUID modelConfigurationId,
        @Schema(types = {"integer", "null"}, format = "int32")
        @Min(PersonaInput.MIN_CONTEXT_TOKEN_LIMIT) @Max(PersonaInput.MAX_CONTEXT_TOKEN_LIMIT) @Nullable Integer contextTokenLimit,
        @Schema(types = {"integer", "null"}, format = "int32")
        @Min(PersonaInput.MIN_OUTPUT_TOKEN_LIMIT) @Max(PersonaInput.MAX_OUTPUT_TOKEN_LIMIT) @Nullable Integer outputTokenLimit,
        @Nullable List<UUID> fileIds,
        @Schema(types = {"string", "null"}, pattern = PersonaInput.AVATAR_SEED_PATTERN, maxLength = 64,
                description = "The seed of the agent's face; null keeps it, and a new agent gets a random one")
        @Pattern(regexp = PersonaInput.AVATAR_SEED_PATTERN) @Nullable String avatarSeed,
        @Nullable UUID avatarFileId,
        @Schema(types = {"boolean", "null"}, description = "True drops the uploaded image so the face shows")
        @Nullable Boolean removeAvatar,
        @Nullable List<UUID> labelIds,
        @Nullable Boolean replaceBaseSystemPrompt, @Nullable Instant knowledgeCutoff, @Nullable Boolean grounded
) {
    public PersonaInput toInput() {
        return new PersonaInput(name, description, instructions, taskPrompt, starterPrompts, sourceIds, documentSetIds, tools,
                mcpServerIds, modelConfigurationId, contextTokenLimit, outputTokenLimit, fileIds, avatarSeed, avatarFileId, removeAvatar, labelIds,
                replaceBaseSystemPrompt, knowledgeCutoff, grounded);
    }
}
