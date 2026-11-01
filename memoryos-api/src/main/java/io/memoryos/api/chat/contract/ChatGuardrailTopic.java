package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatGuardrails;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One sensitive topic of the Tenant (MEM-208), after Amazon Q Business topic controls. */
@Schema(name = "ChatGuardrailTopic")
public record ChatGuardrailTopic(
        @Schema(description = "Absent for a topic this request creates") @Nullable UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = ChatGuardrails.MAX_NAME_LENGTH) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What the model classifies a question by")
        @NotBlank @Size(max = ChatGuardrails.MAX_DESCRIPTION_LENGTH) String description,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Example questions about the topic")
        @NotNull @Size(max = ChatGuardrails.MAX_EXAMPLES) List<@NotNull @Size(max = ChatGuardrails.MAX_EXAMPLE_LENGTH) String> examples,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What the person is told when a question matches")
        @NotNull @Size(max = ChatGuardrails.MAX_MESSAGE_LENGTH) String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled) {
    public static ChatGuardrailTopic from(ChatGuardrails.Topic topic) {
        return new ChatGuardrailTopic(topic.id(), topic.name(), topic.description(), topic.examples(), topic.message(),
                topic.enabled());
    }

    public ChatGuardrails.Topic topic() {
        return new ChatGuardrails.Topic(id == null ? UUID.randomUUID() : id, name, description, examples, message, enabled);
    }
}
