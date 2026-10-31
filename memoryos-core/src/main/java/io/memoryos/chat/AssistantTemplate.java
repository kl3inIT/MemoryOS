package io.memoryos.chat;

import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One of MemoryOS's assistant templates (MEM-281): the text people read in each account language, the instructions,
 * the tools and whether it answers from documents only. {@link AssistantTemplates} loads them from the catalogue.
 */
public record AssistantTemplate(String key, String avatarSeed, Set<String> tools, boolean grounded,
                                Text vi, Text en, String instructions) {
    public AssistantTemplate {
        tools = Set.copyOf(tools);
    }

    /** What a reader sees of a template, in one language. */
    public record Text(String name, String title, String description, String greeting, List<String> starters) {
        public Text {
            starters = List.copyOf(starters);
        }
    }

    /** The text in an account language: English for {@code en}, else Vietnamese, the language the rows store. */
    public Text text(@Nullable String language) {
        return "en".equals(language) ? en : vi;
    }
}
