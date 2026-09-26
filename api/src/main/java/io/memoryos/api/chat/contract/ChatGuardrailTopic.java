package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatGuardrails;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One built-in sensitive topic as the Tenant set it. */
@Schema(name = "ChatGuardrailTopic")
public record ChatGuardrailTopic(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull ChatGuardrails.Topic topic,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What the person is told when a question matches")
        @NotNull @Size(max = ChatGuardrails.MAX_MESSAGE_LENGTH) String message) {
    public static ChatGuardrailTopic from(ChatGuardrails.TopicSetting setting) {
        return new ChatGuardrailTopic(setting.topic(), setting.enabled(), setting.message());
    }

    public ChatGuardrails.TopicSetting setting() { return new ChatGuardrails.TopicSetting(topic, enabled, message); }
}
