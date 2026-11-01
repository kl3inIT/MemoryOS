package io.memoryos.api.chat.contract;

import io.memoryos.ai.ReasoningEffort;
import io.memoryos.chat.ChatPersonaService.PersonaInput;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An agent's settings as the editor sends them on create and update. The title and greeting bounds are the service's
 * own, declared here so a violation is reported on its field; the service keeps checking them.
 */
@Schema(name = "PersonaInput")
public record ChatPersonaRequest(
        String name,
        @Schema(types = {"string", "null"}, maxLength = PersonaInput.MAX_TITLE,
                description = "The role line under the name; null or blank clears it")
        @Size(max = PersonaInput.MAX_TITLE) @Nullable String title,
        String description, String instructions,
        @Schema(types = {"string", "null"}, maxLength = PersonaInput.MAX_GREETING,
                description = "What an empty conversation opens with; null or blank shows the default heading")
        @Size(max = PersonaInput.MAX_GREETING) @Nullable String greeting,
        List<String> starterPrompts, List<UUID> sourceIds, @Nullable List<UUID> documentSetIds,
        @Nullable Set<String> tools, @Nullable List<UUID> mcpServerIds,
        @Schema(types = {"string", "null"}, format = "uuid") @Nullable UUID modelConfigurationId,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The agent's own reasoning level; null leaves it to the conversation, the member and the model")
        @Nullable ReasoningEffort reasoningEffort,
        @Nullable List<UUID> fileIds,
        @Schema(types = {"string", "null"}, pattern = PersonaInput.AVATAR_SEED_PATTERN, maxLength = 64,
                description = "The seed of the agent's face; null keeps it, and a new agent gets a random one")
        @Pattern(regexp = PersonaInput.AVATAR_SEED_PATTERN) @Nullable String avatarSeed,
        @Nullable UUID avatarFileId,
        @Schema(types = {"boolean", "null"}, description = "True drops the uploaded image so the face shows")
        @Nullable Boolean removeAvatar,
        @Nullable List<UUID> labelIds,
        @Nullable Boolean grounded
) {
    public PersonaInput toInput() {
        return new PersonaInput(name, title, description, instructions, greeting, starterPrompts, sourceIds, documentSetIds,
                tools, mcpServerIds, modelConfigurationId, reasoningEffort, fileIds, avatarSeed, avatarFileId, removeAvatar,
                labelIds, grounded);
    }
}
