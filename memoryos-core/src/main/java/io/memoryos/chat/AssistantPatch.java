package io.memoryos.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The fields of an assistant's unsaved form that one Agent Builder call changed (MEM-280), each the field's whole new
 * value; a null field is unchanged. An empty {@code title}, {@code greeting} or {@code reasoningEffort} clears it, so
 * the browser can tell "cleared" from "untouched". The browser applies it to the form and draws the before → after
 * card from the values it replaced; nothing is saved. An unchanged field is left out of the JSON, as the published
 * contract declares every field optional and not nullable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantPatch(@Nullable String name, @Nullable String title, @Nullable String description,
                             @Nullable String avatarSeed, @Nullable String instructions, @Nullable String greeting,
                             @Nullable List<String> starterPrompts, @Nullable Set<String> tools,
                             @Nullable List<UUID> sourceIds, @Nullable List<UUID> documentSetIds,
                             @Nullable List<UUID> mcpServerIds, @Nullable UUID modelConfigurationId,
                             @Nullable String reasoningEffort, @Nullable Boolean grounded) {
    public AssistantPatch {
        starterPrompts = starterPrompts == null ? null : List.copyOf(starterPrompts);
        tools = tools == null ? null : Set.copyOf(tools);
        sourceIds = sourceIds == null ? null : List.copyOf(sourceIds);
        documentSetIds = documentSetIds == null ? null : List.copyOf(documentSetIds);
        mcpServerIds = mcpServerIds == null ? null : List.copyOf(mcpServerIds);
    }

    /** Only the instructions, as {@code write_instructions} changes them. */
    public static AssistantPatch instructions(String value) {
        return new AssistantPatch(null, null, null, null, value, null, null, null, null, null, null, null, null, null);
    }

    public boolean empty() {
        return name == null && title == null && description == null && avatarSeed == null && instructions == null
                && greeting == null && starterPrompts == null && tools == null && sourceIds == null
                && documentSetIds == null && mcpServerIds == null && modelConfigurationId == null
                && reasoningEffort == null && grounded == null;
    }
}
