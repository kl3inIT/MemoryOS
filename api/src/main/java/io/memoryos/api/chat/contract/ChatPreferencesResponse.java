package io.memoryos.api.chat.contract;

import io.memoryos.ai.ReasoningEffort;
import io.memoryos.chat.ChatPreferencesService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Preferences plus the read-only name and email the identity provider reported for this member. */
@Schema(name = "ChatPreferences")
public record ChatPreferencesResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workRole,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String personalPreferences,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable UUID defaultModelId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Double temperatureDefault,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable ReasoningEffort reasoningEffortDefault,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean autoScroll,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String email
) {
    public static ChatPreferencesResponse from(ChatPreferencesService.View view) {
        var value = view.preferences();
        return new ChatPreferencesResponse(value.workRole(), value.personalPreferences(), value.defaultModelId(),
                value.temperatureDefault(), value.reasoningEffortDefault(), value.autoScroll(), view.profile().displayName(),
                view.profile().email());
    }
}
