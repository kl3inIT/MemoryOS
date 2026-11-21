package io.memoryos.chat;

import io.memoryos.ai.ReasoningEffort;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One member's Chat preferences (Onyx Settings: Work Role, Personal Preferences, Default Model, Default Temperature,
 * Default Reasoning Level, Chat Auto-scroll). Empty text means not set, and a null default defers to the model
 * configuration. {@code pasteAsTile} is Onyx's Collapse Large Pastes: the composer turns a large paste into a tile
 * instead of inserting it (MEM-148).
 */
public record ChatPreferences(String workRole, String personalPreferences, @Nullable UUID defaultModelId,
                              @Nullable Double temperatureDefault, @Nullable ReasoningEffort reasoningEffortDefault,
                              boolean autoScroll, boolean pasteAsTile) {
    public static final int MAX_WORK_ROLE = 200;
    public static final int MAX_PERSONAL_PREFERENCES = 2000;
    public static final ChatPreferences DEFAULT = new ChatPreferences("", "", null, null, null, true, false);

    public ChatPreferences(String workRole, String personalPreferences, @Nullable UUID defaultModelId,
                           @Nullable Double temperatureDefault, @Nullable ReasoningEffort reasoningEffortDefault,
                           boolean autoScroll) {
        this(workRole, personalPreferences, defaultModelId, temperatureDefault, reasoningEffortDefault, autoScroll, false);
    }

    public ChatPreferences {
        workRole = workRole == null ? "" : workRole.strip();
        personalPreferences = personalPreferences == null ? "" : personalPreferences.strip();
    }
}
