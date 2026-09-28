package io.memoryos.api.chat.contract;

import io.memoryos.chat.ChatSettingsService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "ChatGuardrailsResponse")
public record ChatGuardrailsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Every built-in topic, in a fixed order") List<ChatGuardrailTopic> topics,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> blockedPhrases,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String blockedPhraseMessage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision) {
    public static ChatGuardrailsResponse from(ChatSettingsService.GuardrailsView view) {
        return new ChatGuardrailsResponse(view.guardrails().allTopics().stream().map(ChatGuardrailTopic::from).toList(),
                view.guardrails().blockedPhrases(), view.guardrails().blockedPhraseMessage(), view.revision());
    }
}
