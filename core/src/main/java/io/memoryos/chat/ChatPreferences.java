package io.memoryos.chat;

import io.memoryos.ai.ReasoningEffort;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One member's Chat preferences (Onyx Settings: Work Role, Personal Preferences, Default Model, Default Temperature,
 * Default Reasoning Level, Chat Auto-scroll). Empty text means not set, and a null default defers to the model
 * configuration.
 */
public record ChatPreferences(String workRole, String personalPreferences, @Nullable UUID defaultModelId,
                              @Nullable Double temperatureDefault, @Nullable ReasoningEffort reasoningEffortDefault,
                              boolean autoScroll) {
    public static final int MAX_WORK_ROLE = 200;
    public static final int MAX_PERSONAL_PREFERENCES = 2000;
    public static final ChatPreferences DEFAULT = new ChatPreferences("", "", null, null, null, true);

    public ChatPreferences {
        workRole = workRole == null ? "" : workRole.strip();
        personalPreferences = personalPreferences == null ? "" : personalPreferences.strip();
    }
}
